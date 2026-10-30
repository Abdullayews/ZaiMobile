//! Raw completions test — shows the exact server error, tries flag variations.

use serde_json::{json, Value};
use std::io::{BufRead, BufReader};

fn main() {
    let client = zai_mobile::api::Client::new(zai_mobile::api::make_device_id()).expect("client");
    client.warm_up_home();
    let auth = client.guest_auth().expect("guest");
    println!("guest ok: {}", auth.user_name);

    let prompt = "Reply with exactly one word: SMOKE_OK";
    let user_msg_id = zai_mobile::api::gen_uuid();

    // v1: saytın dəqiq formatı (general_agent + features)
    let _chat = zai_mobile::api::chat_new_object(prompt, "x-preview-l", &user_msg_id);
    let chat_id = client
        .chat_new(&auth.token, prompt, "x-preview-l", &user_msg_id)
        .expect("chat_new");
    println!("chat created: {}", chat_id);

    let sig = zai_mobile::api::make_signature(prompt, &auth.token, &auth.user_id);
    let which = std::env::args().nth(1).unwrap_or_else(|| "v2".into());
    let base_path = if which == "v1" { "/api/chat/completions" } else { "/api/v2/chat/completions" };
    let url = format!(
        "{}{}?{}&signature_timestamp={}",
        zai_mobile::api::base(), base_path, sig.url_params, sig.timestamp
    );
    println!("URL variant: {}", which);

    let body = json!({
        "stream": true,
        "model": "x-preview-l",
        "messages": [{"role": "user", "content": prompt}],
        "signature_prompt": prompt,
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
        "session_id": zai_mobile::api::gen_uuid(),
        "chat_id": chat_id,
        "id": zai_mobile::api::gen_uuid(),
        "current_user_message_id": user_msg_id,
        "current_user_message_parent_id": Value::Null,
        "background_tasks": {"title_generation": true, "tags_generation": true}
    });

    let resp = client
        .completions_post(&auth.token, &url, &sig.signature, &body)
        .expect("post");
    let status = resp.status();
    println!("HTTP {}", status);
    let reader = BufReader::new(resp);
    let mut got = String::new();
    let mut n = 0;
    for line in reader.lines() {
        let line = match line {
            Ok(l) => l,
            Err(_) => break,
        };
        n += 1;
        if n <= 12 || line.contains("error") || line.contains("CAPTCHA") {
            println!("  | {}", line.chars().take(220).collect::<String>());
        }
        let t = line.trim();
        if let Some(rest) = t.strip_prefix("data:") {
            if let Ok(v) = serde_json::from_str::<Value>(rest.trim()) {
                if let Some(d) = v.pointer("/data/message/delta_content").and_then(|x| x.as_str()) {
                    got.push_str(d);
                }
            }
        }
    }
    println!("--- lines: {}, answer: {}", n, got.chars().take(300).collect::<String>());
}
