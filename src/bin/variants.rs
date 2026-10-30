//! Variation test: flags/model/region combos against completions.

use serde_json::{json, Value};
use std::io::{BufRead, BufReader};

fn try_variant(client: &zai_mobile::api::Client, token: &str, user_id: &str, label: &str, flags: Value, model: &str) {
    let prompt = "Say OK";
    let user_msg_id = zai_mobile::api::gen_uuid();
    let chat = zai_mobile::api::chat_new_object(prompt, model, &user_msg_id);
    // override flags in chat object
    let mut chat = chat;
    if let Some(o) = chat.as_object_mut() {
        o.insert("flags".into(), flags.clone());
    }
    // create chat
    let resp = client.send_with_retry(
        || {
            use zai_mobile::api::{FE_VERSION, MOBILE_UA};
            client.http
                .request(reqwest::Method::POST, format!("{}/api/v1/chats/new", zai_mobile::api::base()))
                .header("User-Agent", MOBILE_UA)
                .header("X-Device-ID", &client.device_id)
                .header("X-FE-Version", FE_VERSION)
                .header("Origin", zai_mobile::api::base())
                .header("Referer", "https://chat.z.ai/")
                .header("Authorization", format!("Bearer {}", token))
                .header("Content-Type", "application/json")
                .header("x-region", "overseas")
                .json(&json!({ "chat": chat }))
        },
        4,
    );
    let chat_id = match resp {
        Ok(r) => {
            let v: Value = r.json().unwrap_or(Value::Null);
            v["id"].as_str().unwrap_or("").to_string()
        }
        Err(e) => {
            println!("[{}] chat_new fail: {}", label, e);
            return;
        }
    };
    if chat_id.is_empty() {
        println!("[{}] chat_new returned no id", label);
        return;
    }

    let sig = zai_mobile::api::make_signature(prompt, token, user_id);
    let url = format!(
        "{}/api/v2/chat/completions?{}&signature_timestamp={}",
        zai_mobile::api::base(), sig.url_params, sig.timestamp
    );
    let body = json!({
        "stream": true,
        "model": model,
        "messages": [{"role": "user", "content": prompt}],
        "signature_prompt": prompt,
        "params": {},
        "features": {
            "image_generation": false, "web_search": false, "auto_web_search": false,
            "preview_mode": false, "flags": flags, "vlm_tools_enable": false,
            "vlm_web_search_enable": false, "vlm_website_mode": false,
            "enable_thinking": true, "reasoning_effort": "high"
        },
        "variables": {},
        "session_id": zai_mobile::api::gen_uuid(),
        "chat_id": chat_id,
        "id": zai_mobile::api::gen_uuid(),
        "current_user_message_id": user_msg_id,
        "current_user_message_parent_id": Value::Null,
        "background_tasks": {"title_generation": false, "tags_generation": false}
    });

    match client.completions_post(token, &url, &sig.signature, &body) {
        Ok(resp) => {
            let status = resp.status();
            let mut first = String::new();
            for line in BufReader::new(resp).lines().map_while(Result::ok) {
                let t = line.trim();
                if t.starts_with("data:") && !t.starts_with("data: [DONE]") {
                    first = t.chars().take(180).collect();
                    break;
                }
            }
            println!("[{}] HTTP {} | first: {}", label, status, first);
        }
        Err(e) => println!("[{}] post fail: {}", label, e),
    }
}

fn main() {
    let client = zai_mobile::api::Client::new(zai_mobile::api::make_device_id()).expect("client");
    client.warm_up_home();
    let auth = client.guest_auth().expect("guest");
    println!("guest: {}", auth.user_name);

    use serde_json::json;
    try_variant(&client, &auth.token, &auth.user_id, "general_agent", json!(["general_agent"]), "x-preview-l");
    try_variant(&client, &auth.token, &auth.user_id, "no-flags", json!([]), "x-preview-l");
    try_variant(&client, &auth.token, &auth.user_id, "hidden-thinking-only", json!(["hidden-thinking"]), "x-preview-l");
    try_variant(&client, &auth.token, &auth.user_id, "ga+x-preview", json!(["general_agent", "x-preview"]), "x-preview-l");
}
