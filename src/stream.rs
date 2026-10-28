use crate::api::{make_signature, now_ms, BASE, FE_VERSION};
use serde_json::Value;
use std::io::{BufRead, BufReader};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::Sender;
use std::sync::Arc;

#[derive(Clone, Debug)]
pub enum StreamEvent {
    /// Cavab mətninin yeni hissəsi
    Delta(String),
    /// Faza: answer / think / search / tool_response / other
    Phase(String),
    /// Axın bitdi
    Done,
    /// Xəta
    Error(String),
    /// Chat yaradıldı (id)
    ChatCreated(String),
    /// Başlıq yeniləndi
    Title(String),
}

pub struct CompletionJob {
    pub stop: Arc<AtomicBool>,
}

/// Tam axın: (lazımsa chats/new) → v2 completions SSE → chat yenilə → başlıq al
#[allow(clippy::too_many_arguments)]
pub fn spawn_completion(
    client: crate::api::Client,
    chat_id: Option<String>,
    prompt: String,
    history: Vec<(String, String)>,            // (role, content) — əvvəlki mesajlar
    history_ids: Vec<(String, String, String)>, // (id, role, content)
    user_msg_id: String,
    assistant_msg_id: String,
    chat_title: String,
    is_first: bool,
    model: String,
    session_id: String,
    ctx: egui::Context,
    tx: Sender<StreamEvent>,
) -> CompletionJob {
    let stop = Arc::new(AtomicBool::new(false));
    let stop2 = stop.clone();
    std::thread::spawn(move || {
        let send = |ev: StreamEvent| {
            let _ = tx.send(ev);
            ctx.request_repaint();
        };
        let result = run_completion(
            &client,
            chat_id,
            &prompt,
            &history,
            &history_ids,
            &user_msg_id,
            &assistant_msg_id,
            &chat_title,
            is_first,
            &model,
            &session_id,
            &stop2,
            &send,
        );
        if let Err(e) = result {
            send(StreamEvent::Error(e));
        }
        send(StreamEvent::Done);
    });
    CompletionJob { stop }
}

fn common_headers(device_id: &str) -> Vec<(&'static str, String)> {
    vec![
        ("X-Device-ID", device_id.to_string()),
        ("X-FE-Version", FE_VERSION.to_string()),
        ("Accept-Language", "en-US".into()),
    ]
}

#[allow(clippy::too_many_arguments)]
fn run_completion(
    client: &crate::api::Client,
    chat_id: Option<String>,
    prompt: &str,
    history: &[(String, String)],
    history_ids: &[(String, String, String)],
    user_msg_id: &str,
    assistant_msg_id: &str,
    chat_title: &str,
    is_first: bool,
    model: &str,
    session_id: &str,
    stop: &AtomicBool,
    send: &impl Fn(StreamEvent),
) -> Result<(), String> {
    let http = &client.http;
    let token = &client.token;
    let device_id = &client.device_id;

    // ---------- 1. Chat yarad ----------
    let chat_id = match chat_id {
        Some(id) => id,
        None => {
            let mut req = http
                .post(format!("{}/api/v1/chats/new", BASE))
                .header("Authorization", format!("Bearer {}", token))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("x-region", "overseas")
                .json(&serde_json::json!({
                    "chat": chat_new_object(prompt, model, user_msg_id)
                }));
            for (k, v) in common_headers(device_id) {
                req = req.header(k, v);
            }
            let resp = req.send().map_err(|e| format!("Şəbəkə: {}", e))?;
            let status = resp.status();
            let body: Value = resp.json().unwrap_or(Value::Null);
            if !status.is_success() {
                return Err(format!(
                    "Chat yaradılmadı (HTTP {}): {}",
                    status,
                    body["detail"].as_str().unwrap_or("bilinməyən")
                ));
            }
            let id = body["id"].as_str().ok_or("chat id yoxdur")?.to_string();
            send(StreamEvent::ChatCreated(id.clone()));
            id
        }
    };

    // ---------- 2. Signature ----------
    let sig = make_signature(prompt, token, &client.user_id);

    // ---------- 3. Tarixçə + gövdə ----------
    let mut messages: Vec<Value> = history
        .iter()
        .map(|(r, c)| serde_json::json!({"role": r, "content": c}))
        .collect();
    messages.push(serde_json::json!({"role": "user", "content": prompt}));

    let last_user_parent = history_ids
        .iter()
        .rev()
        .find(|(_, r, _)| r == "user")
        .map(|(id, _, _)| Value::String(id.clone()))
        .unwrap_or(Value::Null);

    let body = serde_json::json!({
        "stream": true,
        "model": model,
        "messages": messages,
        "signature_prompt": prompt.trim(),
        "params": {},
        "features": {
            "image_generation": false,
            "web_search": false,
            "auto_web_search": false,
            "preview_mode": false,
            "flags": ["general_agent"],
            "vlm_tools_enable": false,
            "vlm_web_search_enable": false,
            "vlm_website_mode": false,
            "enable_thinking": true,
            "reasoning_effort": "high"
        },
        "variables": {},
        "session_id": session_id,
        "chat_id": chat_id,
        "id": assistant_msg_id,
        "current_user_message_id": user_msg_id,
        "current_user_message_parent_id": last_user_parent,
        "background_tasks": {
            "title_generation": is_first,
            "tags_generation": is_first
        }
    });

    let url = format!(
        "{}/api/v2/chat/completions?{}&signature_timestamp={}",
        BASE, sig.url_params, sig.timestamp
    );
    let mut req = http
        .post(&url)
        .header("Authorization", format!("Bearer {}", token))
        .header("Content-Type", "application/json")
        .header("Accept", "text/event-stream")
        .header("X-Signature", &sig.signature)
        .json(&body);
    for (k, v) in common_headers(device_id) {
        req = req.header(k, v);
    }
    let resp = req.send().map_err(|e| format!("Bağlantı xətası: {}", e))?;
    let status = resp.status();
    if !status.is_success() {
        let txt = resp.text().unwrap_or_default();
        let short: String = txt.chars().take(200).collect();
        return Err(format!("HTTP {}: {}", status, short));
    }

    // ---------- 4. SSE oxu ----------
    let reader = BufReader::new(resp);
    let mut full_content = String::new();
    let mut had_error: Option<String> = None;
    let mut stopped = false;
    // Axın «done» hadisəsi almadan bitibsə bu şəbəkə kəsintisidir —
    // qeyd et ki, yuxarıda avtomatik bərpa (retry) işə düşə bilsin.
    let mut got_done = false;
    for line in reader.lines() {
        if stop.load(Ordering::Relaxed) {
            stopped = true;
            break;
        }
        let line = match line {
            Ok(l) => l,
            Err(_) => break, // oxu xətası — aşağıda kəsinti kimi qiymətləndirilir
        };
        let line = line.trim().to_string();
        if line.is_empty() || !line.starts_with("data:") {
            continue;
        }
        let payload = line[5..].trim();
        if payload == "[DONE]" {
            got_done = true;
            break;
        }
        let ev: Value = match serde_json::from_str(payload) {
            Ok(v) => v,
            Err(_) => continue,
        };
        match parse_stream_event(&ev) {
            Some(StreamEvent::Delta(d)) => {
                full_content.push_str(&d);
                send(StreamEvent::Delta(d));
            }
            Some(StreamEvent::Phase(p)) => send(StreamEvent::Phase(p)),
            Some(StreamEvent::Done) => {
                got_done = true;
            }
            Some(StreamEvent::Error(e)) => {
                had_error = Some(e);
                break;
            }
            _ => {}
        }
    }

    // ---------- 5. Chat-i yenilə (veb tətbiqi kimi) ----------
    if !stopped || !full_content.is_empty() {
        let chat_obj = build_chat_update(
            &chat_id,
            chat_title,
            model,
            history_ids,
            user_msg_id,
            assistant_msg_id,
            prompt,
            &full_content,
        );
        let mut req = http
            .post(format!("{}/api/v1/chats/{}", BASE, chat_id))
            .header("Authorization", format!("Bearer {}", token))
            .header("Content-Type", "application/json")
            .json(&serde_json::json!({ "chat": chat_obj }));
        for (k, v) in common_headers(device_id) {
            req = req.header(k, v);
        }
        let _ = req.send();
    }

    if let Some(e) = had_error {
        return Err(e);
    }
    // İstifadəçi dayandırmayıbsa və axın «done» almadan bitibsə — kəsinti
    if !stopped && !got_done {
        return Err("Axın kəsildi (şəbəkə)".into());
    }
    Ok(())
}

/// SSE hadisəsini təhlil et — bir neçə mümkün formatı dəstəkləyir
pub fn parse_stream_event(ev: &Value) -> Option<StreamEvent> {
    let msg = ev
        .pointer("/data/message")
        .or_else(|| ev.pointer("/data/data/message"))
        .or_else(|| ev.pointer("/message"))
        .or_else(|| ev.pointer("/data/data/data"));
    if let Some(m) = msg {
        if let Some(err) = m.get("error").filter(|e| !e.is_null()) {
            let detail = err["detail"]
                .as_str()
                .or_else(|| err["message"].as_str())
                .unwrap_or("Xəta baş verdi");
            let code = err["code"]
                .as_str()
                .or_else(|| err["error_code"].as_str())
                .unwrap_or("");
            return Some(StreamEvent::Error(friendly_error(code, detail)));
        }
        if m["done"].as_bool() == Some(true) {
            return Some(StreamEvent::Done);
        }
        if let Some(p) = m["phase"].as_str() {
            if p != "answer" && p != "other" {
                return Some(StreamEvent::Phase(phase_label(p)));
            }
        }
        if let Some(d) = m["delta_content"].as_str() {
            if !d.is_empty() {
                return Some(StreamEvent::Delta(d.to_string()));
            }
        }
        return None;
    }
    for p in ["/data/error", "/data/data/error", "/error"] {
        if let Some(err) = ev.pointer(p).filter(|e| !e.is_null()) {
            let detail = err["detail"]
                .as_str()
                .or_else(|| err["message"].as_str())
                .unwrap_or("Xəta baş verdi");
            let code = err["code"]
                .as_str()
                .or_else(|| err["error_code"].as_str())
                .unwrap_or("");
            return Some(StreamEvent::Error(friendly_error(code, detail)));
        }
    }
    None
}

fn friendly_error(code: &str, detail: &str) -> String {
    if code.contains("CAPTCHA") || detail.contains("captcha") {
        return "Bu sorğu üçün captcha tələb olundu. Qonaq rejimi məhduddur — \
                öz hesabınızın tokeni ilə daxil olun (telefonda chat.z.ai → daxil ol → \
                brauzer menyusu → 'Saytı araşdır' → Application → Local Storage → token)."
            .into();
    }
    if code == "426" || detail.contains("outdated") {
        return "Proqram versiyası köhnəlib — yeniləyin.".into();
    }
    if detail.contains("user_limit") || detail.contains("rate_limit") {
        return "Limitə çatdınız — bir az gözləyin və yenidən cəhd edin.".into();
    }
    if detail.contains("global_limit") {
        return "Server sıx işlənir — bir az sonra yenidən cəhd edin.".into();
    }
    detail.to_string()
}

fn phase_label(p: &str) -> String {
    match p {
        "think" | "thinking" | "hidden-thinking" | "reasoning" => "Düşünür…".into(),
        "search" | "web_search" => "İnternetdə axtarır…".into(),
        "tool" | "tool_response" | "tool_call" => "Alət işlədir…".into(),
        "code" | "code_interpreter" => "Kod icra edir…".into(),
        "title" => "Başlıq yazır…".into(),
        _ => "Hazırlanır…".into(),
    }
}

/// chats/new üçün chat obyekti
pub fn chat_new_object(prompt: &str, model: &str, user_msg_id: &str) -> Value {
    let ts = now_ms();
    serde_json::json!({
        "id": "",
        "title": "New Chat",
        "models": [model],
        "params": {},
        "history": {
            "messages": {
                user_msg_id: {
                    "id": user_msg_id,
                    "parentId": null,
                    "childrenIds": [],
                    "role": "user",
                    "content": prompt,
                    "timestamp": ts / 1000,
                    "models": [model]
                }
            },
            "currentId": user_msg_id
        },
        "tags": [],
        "flags": ["general_agent"],
        "features": [
            {"server": "web_search_h", "status": "hidden", "type": "web_search"},
            {"server": "tool_selector_h", "status": "hidden", "type": "tool_selector"},
            {"server": "hidden-thinking", "status": "hidden", "type": "hidden-thinking"}
        ],
        "mcp_servers": [],
        "enable_thinking": true,
        "reasoning_effort": "high",
        "auto_web_search": false,
        "message_version": 1,
        "extra": {},
        "timestamp": ts,
        "type": "general_agent"
    })
}

/// Bitdikdən sonra chat-i yeniləmək üçün tam obyekt
#[allow(clippy::too_many_arguments)]
fn build_chat_update(
    chat_id: &str,
    title: &str,
    model: &str,
    history_ids: &[(String, String, String)],
    user_msg_id: &str,
    assistant_msg_id: &str,
    prompt: &str,
    full_content: &str,
) -> Value {
    // ardıcıllıq: history_ids..., user(yeni), assistant(yeni)
    let mut ids: Vec<String> = history_ids.iter().map(|(id, _, _)| id.clone()).collect();
    ids.push(user_msg_id.to_string());
    ids.push(assistant_msg_id.to_string());

    let mut messages = serde_json::Map::new();
    let ts = now_ms() / 1000;
    for (i, (id, role, content)) in history_ids.iter().enumerate() {
        let parent = if i > 0 {
            Value::String(ids[i - 1].clone())
        } else {
            Value::Null
        };
        let children: Vec<String> = ids.get(i + 1).map(|c| vec![c.clone()]).unwrap_or_default();
        messages.insert(
            id.clone(),
            serde_json::json!({
                "id": id, "parentId": parent, "childrenIds": children,
                "role": role, "content": content, "timestamp": ts, "models": [model]
            }),
        );
    }
    let n = ids.len();
    // user mesajı
    messages.insert(
        user_msg_id.to_string(),
        serde_json::json!({
            "id": user_msg_id,
            "parentId": if n >= 2 { Value::String(ids[n - 2].clone()) } else { Value::Null },
            "childrenIds": [assistant_msg_id],
            "role": "user", "content": prompt, "timestamp": ts, "models": [model]
        }),
    );
    // assistant
    messages.insert(
        assistant_msg_id.to_string(),
        serde_json::json!({
            "id": assistant_msg_id,
            "parentId": user_msg_id,
            "childrenIds": [],
            "role": "assistant",
            "content": if full_content.is_empty() { "…" } else { full_content },
            "timestamp": ts,
            "model": model,
            "modelName": model,
            "modelIdx": 0,
            "done": true,
            "error": false
        }),
    );

    serde_json::json!({
        "id": chat_id,
        "title": title,
        "models": [model],
        "params": {},
        "history": { "messages": messages, "currentId": assistant_msg_id },
        "tags": [],
        "flags": ["general_agent"],
        "features": [
            {"server": "web_search_h", "status": "hidden", "type": "web_search"},
            {"server": "tool_selector_h", "status": "hidden", "type": "tool_selector"},
            {"server": "hidden-thinking", "status": "hidden", "type": "hidden-thinking"}
        ],
        "mcp_servers": [],
        "enable_thinking": true,
        "reasoning_effort": "high",
        "auto_web_search": false,
        "message_version": 1,
        "extra": {},
        "timestamp": now_ms(),
        "type": "general_agent"
    })
}
