//! Şəbəkə protokolunun Rust implementasiyasının canlı testi
//! İstifadə: cargo run --bin smoke --release

use zai_mobile::api::{gen_uuid, make_signature, Client};
use zai_mobile::stream::{parse_stream_event, StreamEvent};

fn main() {
    println!("=== 1. Qonaq auth ===");
    let c = match Client::new().and_then(|mut c| {
        c.guest_auth()?;
        Ok(c)
    }) {
        Ok(c) => c,
        Err(e) => {
            println!("XETA: {}", e);
            return;
        }
    };
    println!("OK: {} (guest={})", c.user_name, c.is_guest);

    println!("=== 2. Warm-up ===");
    c.warm_up();
    println!("OK");

    println!("=== 3. Modellər ===");
    match c.models() {
        Ok(m) => {
            for (id, name) in &m {
                println!("  {} = {}", id, name);
            }
        }
        Err(e) => println!("models xeta: {}", e),
    }

    let prompt = "Salam! Yalnız bir cümlə cavab ver: bağlantı uğurludurmu?";

    println!("=== 4. chats/new ===");
    let umid = gen_uuid();
    let chat_id = match c.new_chat(prompt, "x-preview-l", &umid) {
        Ok(id) => {
            println!("OK: {}", id);
            Some(id)
        }
        Err(e) => {
            println!("XETA (WAF?): {}", e);
            None
        }
    };

    println!("=== 5. Signature ===");
    let sig = make_signature(prompt, &c.token, &c.user_id);
    println!("sig = {}", &sig.signature[..32.min(sig.signature.len())]);
    println!("ts  = {}", sig.timestamp);

    println!("=== 6. Completions (SSE) ===");
    let http = c.http.clone();
    let url = format!(
        "{}/api/v2/chat/completions?{}&signature_timestamp={}",
        zai_mobile::api::BASE, sig.url_params, sig.timestamp
    );
    let body = serde_json::json!({
        "stream": true,
        "model": "x-preview-l",
        "messages": [{"role": "user", "content": prompt}],
        "signature_prompt": prompt.trim(),
        "params": {},
        "features": {
            "image_generation": false, "web_search": false, "auto_web_search": false,
            "preview_mode": false, "flags": ["general_agent"],
            "vlm_tools_enable": false, "vlm_web_search_enable": false,
            "vlm_website_mode": false, "enable_thinking": true, "reasoning_effort": "high"
        },
        "variables": {},
        "session_id": format!("web-{}", gen_uuid()),
        "chat_id": chat_id.clone().unwrap_or_else(gen_uuid),
        "id": gen_uuid(),
        "current_user_message_id": umid,
        "current_user_message_parent_id": serde_json::Value::Null,
        "background_tasks": {"title_generation": false, "tags_generation": false},
    });
    let req = http
        .post(&url)
        .header("Authorization", format!("Bearer {}", c.token))
        .header("Content-Type", "application/json")
        .header("Accept", "text/event-stream")
        .header("X-Signature", &sig.signature)
        .header("X-Device-ID", &c.device_id)
        .header("X-FE-Version", zai_mobile::api::FE_VERSION)
        .header("Accept-Language", "en-US")
        .json(&body);
    match req.send() {
        Ok(resp) => {
            println!("STATUS: {}", resp.status());
            if !resp.status().is_success() {
                println!(
                    "BODY: {}",
                    resp.text()
                        .unwrap_or_default()
                        .chars()
                        .take(400)
                        .collect::<String>()
                );
                return;
            }
            use std::io::{BufRead, BufReader};
            let reader = BufReader::new(resp);
            let mut n = 0;
            let mut answer = String::new();
            for line in reader.lines() {
                let line = match line {
                    Ok(l) => l,
                    Err(_) => break,
                };
                let line = line.trim().to_string();
                if line.is_empty() || !line.starts_with("data:") {
                    continue;
                }
                let payload = &line[5..];
                if payload == "[DONE]" {
                    println!("  [DONE]");
                    break;
                }
                n += 1;
                if let Ok(ev) = serde_json::from_str::<serde_json::Value>(payload) {
                    if let Some(e) = parse_stream_event(&ev) {
                        match e {
                            StreamEvent::Delta(d) => {
                                answer.push_str(&d);
                                if n <= 10 {
                                    let cut = &d[..d.len().min(60)];
                                    println!("  #{} DELTA: {}", n, cut);
                                }
                            }
                            StreamEvent::Phase(p) => println!("  #{} PHASE: {}", n, p),
                            StreamEvent::Done => println!("  #{} DONE", n),
                            StreamEvent::Error(e) => println!("  #{} ERROR: {}", n, e),
                            _ => {}
                        }
                    } else if n <= 6 {
                        let cut = &payload[..payload.len().min(150)];
                        println!("  #{} (naməlum): {}", n, cut);
                    }
                }
                if n > 500 {
                    break;
                }
            }
            println!("CƏMİ {} hadisə", n);
            if !answer.is_empty() {
                let cut = &answer[..answer.len().min(300)];
                println!("CAVAB: {}", cut);
            }
        }
        Err(e) => println!("Bağlantı xətası: {}", e),
    }
}
