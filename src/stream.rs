//! SSE completion axını: chat yaratma → completions → parçalı yeniləmə →
//! avtomatik bərpa (şəbəkə kəsilməsi, WAF bloku, 401) → serverdə saxlama.
//!
//! Mühüm dərs (v2.1-dən): reqwest blocking-də `timeout()` bütün axını kəsir.
//! Buna görə: timeout(None) + hər SSE oxunuşu üçün ayrı read-timeout streami
//! öz threadində idarə edirik (watchdog: 150s fasilə = kəsinti sayılır).

use crate::api::{self, Client, SignatureInfo, UploadedFile};
use crate::markdown;
use serde_json::{json, Value};
use std::io::{BufRead, BufReader};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

/// Axın hadisələri — UI-yə çatdırılır
#[derive(Clone, Debug)]
pub enum StreamEvent {
    Delta(String),
    Phase(String),
    Done,
    Error(String),
    CaptchaRequired,
    Unauthorized,
    ChatCreated(String),
}

/// İşin idarəsi (stop bayrağı + son fəaliyyət vaxtı)
pub struct Job {
    pub stop: Arc<AtomicBool>,
    pub last_activity: Arc<Mutex<Instant>>,
}

impl Job {
    pub fn new() -> Arc<Self> {
        Arc::new(Job {
            stop: Arc::new(AtomicBool::new(false)),
            last_activity: Arc::new(Mutex::new(Instant::now())),
        })
    }
    pub fn is_stopped(&self) -> bool {
        self.stop.load(Ordering::Relaxed)
    }
    fn touch(&self) {
        *self.last_activity.lock().unwrap() = Instant::now();
    }
    fn idle_secs(&self) -> u64 {
        self.last_activity.lock().unwrap().elapsed().as_secs()
    }
}

/// Axın məzmununun təhlili (bir neçə format dəstəklənir)
fn parse_stream_event(ev: &Value) -> Option<StreamEvent> {
    let msg = ev
        .pointer("/data/message")
        .or_else(|| ev.pointer("/data/data/message"))
        .or_else(|| ev.pointer("/message"))
        .or_else(|| ev.pointer("/data/data/data"));
    if let Some(m) = msg {
        if let Some(err) = m.get("error").filter(|e| !e.is_null()) {
            let code = err["code"].as_str().or_else(|| err["error_code"].as_str()).unwrap_or("");
            let detail = err["detail"].as_str().or_else(|| err["message"].as_str()).unwrap_or("Error");
            if code.contains("CAPTCHA") || detail.contains("captcha") {
                return Some(StreamEvent::CaptchaRequired);
            }
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
            let code = err["code"].as_str().or_else(|| err["error_code"].as_str()).unwrap_or("");
            let detail = err["detail"].as_str().or_else(|| err["message"].as_str()).unwrap_or("Error");
            if code.contains("CAPTCHA") || detail.contains("captcha") {
                return Some(StreamEvent::CaptchaRequired);
            }
            return Some(StreamEvent::Error(friendly_error(code, detail)));
        }
    }
    None
}

fn friendly_error(code: &str, detail: &str) -> String {
    if code == "426" || detail.contains("outdated") {
        return "App version is outdated — please update.".into();
    }
    if detail.contains("user_limit") || detail.contains("rate_limit") {
        return "RETRYABLE:Rate limit reached — retrying automatically…".into();
    }
    if detail.contains("global_limit") {
        return "RETRYABLE:The server is busy — retrying automatically…".into();
    }
    if detail.contains("busy") || detail.contains("overloaded") || detail.contains("temporarily") {
        return "RETRYABLE:Server is busy — retrying automatically…".into();
    }
    detail.to_string()
}

/// Xəta avtomatik yenidən cəhd üçün uyğundurmu?
pub fn is_retryable_error(msg: &str) -> bool {
    msg.starts_with("RETRYABLE:")
        || msg.contains("Stream interrupted")
        || msg.contains("WAF block")
        || msg.contains("HTTP 502")
        || msg.contains("HTTP 503")
        || msg.contains("HTTP 504")
        || msg.contains("HTTP 429")
}

/// RETRYABLE: prefiksini təmizlə
pub fn clean_error(msg: &str) -> String {
    msg.trim_start_matches("RETRYABLE:").to_string()
}

fn phase_label(p: &str) -> String {
    match p {
        "think" | "thinking" | "hidden-thinking" | "reasoning" => "Thinking…".into(),
        "search" | "web_search" => "Searching the web…".into(),
        "tool" | "tool_response" | "tool_call" => "Using tools…".into(),
        "code" | "code_interpreter" => "Running code…".into(),
        "title" => "Writing title…".into(),
        _ => "Preparing…".into(),
    }
}

/// Tam completion icrası. Nəticə hadisələri `emit` ilə UI-yə göndərilir.
#[allow(clippy::too_many_arguments)]
pub fn run_completion(
    client: &Client,
    token: &str,
    user_id: &str,
    chat_id: &mut String,
    chat_title: &str,
    history: &[(String, String)],       // (role, content) — keçmiş mesajlar
    prompt: &str,
    model: &str,
    effort: &str,
    user_msg_id: &str,
    assistant_msg_id: &str,
    is_first: bool,
    files: &[UploadedFile],             // istifadəçinin əlavələri (silinmir!)
    job: &Job,
    emit: &mut dyn FnMut(StreamEvent),
) -> Result<(), String> {
    // ---------- 1. Chat yarat (lazımsa) ----------
    if chat_id.is_empty() {
        let id = client.chat_new_with_files(token, prompt, model, user_msg_id, files)?;
        *chat_id = id.clone();
        emit(StreamEvent::ChatCreated(id));
    }

    // ---------- 2. Əsas axın (retry ilə — avtomatik bərpa) ----------
    let mut attempt = 0u32;
    let max_attempts = 4u32;
    loop {
        attempt += 1;
        match run_once(
            client, token, user_id, chat_id, chat_title, history, prompt, model, effort,
            user_msg_id, assistant_msg_id, is_first, files, job, emit,
        ) {
            Ok(()) => return Ok(()),
            Err(e) if e == "UNAUTHORIZED" => {
                return Err("UNAUTHORIZED".into());
            }
            Err(e) if e == "CAPTCHA" => {
                return Err("CAPTCHA".into());
            }
            Err(e) if e.starts_with("RETRY:") || is_retryable_error(&e) => {
                let inner = if e.starts_with("RETRY:") {
                    e.trim_start_matches("RETRY:").to_string()
                } else {
                    clean_error(&e)
                };
                if attempt >= max_attempts || job.is_stopped() {
                    return Err(inner);
                }
                emit(StreamEvent::Phase("Retrying…".into()));
                std::thread::sleep(Duration::from_millis(1200u64 + 600 * attempt as u64));
                continue;
            }
            Err(e) => return Err(e),
        }
    }
}

#[allow(clippy::too_many_arguments)]
fn run_once(
    client: &Client,
    token: &str,
    user_id: &str,
    chat_id: &str,
    chat_title: &str,
    history: &[(String, String)],
    prompt: &str,
    model: &str,
    effort: &str,
    user_msg_id: &str,
    assistant_msg_id: &str,
    is_first: bool,
    files: &[UploadedFile],
    job: &Job,
    emit: &mut dyn FnMut(StreamEvent),
) -> Result<(), String> {
    // ---------- signature ----------
    let sig: SignatureInfo = api::make_signature(prompt, token, user_id);

    // ---------- body ----------
    let mut messages: Vec<Value> = history
        .iter()
        .map(|(r, c)| json!({"role": r, "content": c}))
        .collect();
    // istifadəçi mesajı: fayl varsa content massiv (veb formatı)
    if files.is_empty() {
        messages.push(json!({"role": "user", "content": prompt}));
    } else {
        let mut parts: Vec<Value> = vec![json!({"type": "text", "text": prompt})];
        for f in files {
            let url = if f.direct_url.is_empty() { &f.id } else { &f.direct_url };
            match f.media.as_str() {
                "image" => parts.push(json!({"type": "image_url", "image_url": {"url": url}})),
                "video" => parts.push(json!({"type": "video_url", "video_url": {"url": url}})),
                _ => parts.push(json!({"type": "file_url", "file_url": {"url": url}})),
            }
        }
        messages.push(json!({"role": "user", "content": parts}));
    }

    let body = json!({
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
            "reasoning_effort": effort
        },
        "variables": {},
        "session_id": api::gen_uuid(),
        "chat_id": chat_id,
        "id": assistant_msg_id,
        "current_user_message_id": user_msg_id,
        "current_user_message_parent_id": Value::Null,
        "background_tasks": {
            "title_generation": is_first,
            "tags_generation": is_first
        }
    });

    let url = format!(
        "{}/api/v2/chat/completions?{}&signature_timestamp={}",
        api::BASE, sig.url_params, sig.timestamp
    );

    // ---------- POST (WAF retry daxilidir) ----------
    job.touch();
    let resp = client.completions_post(token, &url, &sig.signature, &body)?;
    let status = resp.status();
    if status.as_u16() == 401 || status.as_u16() == 403 {
        // bəzi hallarda WAF 403 JSON qaytarır — captcha yoxdursa unauthorized
        let txt = resp.text().unwrap_or_default();
        if txt.contains("captcha") || txt.contains("CAPTCHA") {
            return Err("CAPTCHA".into());
        }
        return Err("UNAUTHORIZED".into());
    }
    if !status.is_success() {
        let txt = resp.text().unwrap_or_default();
        if txt.contains("captcha") || txt.contains("CAPTCHA") {
            return Err("CAPTCHA".into());
        }
        let short: String = txt.chars().take(180).collect();
        return Err(format!("HTTP {}: {}", status, short));
    }

    // ---------- SSE oxu ----------
    let reader = BufReader::new(resp);
    let mut full = String::new();
    let mut got_done = false;
    let mut had_error: Option<String> = None;
    let mut stopped = false;

    for line in reader.lines() {
        if job.is_stopped() {
            stopped = true;
            break;
        }
        if job.idle_secs() > 150 {
            // donmuş axın — kəsinti kimi bərpa olunacaq
            break;
        }
        let line = match line {
            Ok(l) => l,
            Err(_) => break, // oxu xətası — aşağıda kəsinti kimi
        };
        job.touch();
        let line = line.trim();
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
                full.push_str(&d);
                emit(StreamEvent::Delta(d));
            }
            Some(StreamEvent::Phase(p)) => emit(StreamEvent::Phase(p)),
            Some(StreamEvent::Done) => got_done = true,
            Some(StreamEvent::Error(e)) => {
                had_error = Some(e);
                break;
            }
            Some(StreamEvent::CaptchaRequired) => return Err("CAPTCHA".into()),
            Some(StreamEvent::Unauthorized) => return Err("UNAUTHORIZED".into()),
            _ => {}
        }
        if got_done {
            break;
        }
    }

    // ---------- serverdə saxla ----------
    if !stopped || !full.is_empty() {
        let chat_obj = build_chat_update(
            chat_id, chat_title, model, history, user_msg_id, assistant_msg_id, prompt, &full, files,
        );
        let _ = client.chat_update(token, &chat_obj);
    }

    if let Some(e) = had_error {
        return Err(e);
    }
    if !stopped && !got_done {
        // «done» alınmadan bitdi → şəbəkə kəsintisi → retry
        return Err("RETRY:Stream interrupted (network).".into());
    }
    Ok(())
}

/// Bitdikdən sonra chat-i yeniləmək üçün tam obyekt (veb formatı)
#[allow(clippy::too_many_arguments)]
fn build_chat_update(
    chat_id: &str,
    title: &str,
    model: &str,
    history: &[(String, String)],
    user_msg_id: &str,
    assistant_msg_id: &str,
    prompt: &str,
    full_content: &str,
    files: &[UploadedFile],
) -> Value {
    let mut ids: Vec<String> = Vec::new();
    // history üçün id-lər yoxdur — sadə saxlama formatı: synthetic id-lər
    for i in 0..history.len() {
        ids.push(format!("msg-{}", i));
    }
    ids.push(user_msg_id.to_string());
    ids.push(assistant_msg_id.to_string());

    let ts = api::now_ms() / 1000;
    let mut messages = serde_json::Map::new();
    for (i, (role, content)) in history.iter().enumerate() {
        let id = &ids[i];
        let parent = if i > 0 { Value::String(ids[i - 1].clone()) } else { Value::Null };
        let children: Vec<String> = ids.get(i + 1).map(|c| vec![c.clone()]).unwrap_or_default();
        messages.insert(
            id.clone(),
            json!({
                "id": id, "parentId": parent, "childrenIds": children,
                "role": role, "content": content, "timestamp": ts, "models": [model]
            }),
        );
    }
    let n = ids.len();
    let user_content: Value = if files.is_empty() {
        Value::String(prompt.to_string())
    } else {
        let mut parts: Vec<Value> = vec![json!({"type": "text", "text": prompt})];
        for f in files {
            let url = if f.direct_url.is_empty() { &f.id } else { &f.direct_url };
            match f.media.as_str() {
                "image" => parts.push(json!({"type": "image_url", "image_url": {"url": url}})),
                "video" => parts.push(json!({"type": "video_url", "video_url": {"url": url}})),
                _ => parts.push(json!({"type": "file_url", "file_url": {"url": url}})),
            }
        }
        Value::Array(parts)
    };
    messages.insert(
        user_msg_id.to_string(),
        json!({
            "id": user_msg_id,
            "parentId": if n >= 2 { Value::String(ids[n - 2].clone()) } else { Value::Null },
            "childrenIds": [assistant_msg_id],
            "role": "user", "content": user_content, "timestamp": ts, "models": [model]
        }),
    );
    messages.insert(
        assistant_msg_id.to_string(),
        json!({
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

    json!({
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
        "timestamp": api::now_ms(),
        "type": "general_agent"
    })
}

/// Markdown bloklarını hazır formatda qaytarır (UI-yə köçürmə üçün)
pub fn blocks_of(text: &str) -> Vec<markdown::Block> {
    markdown::parse(text)
}
