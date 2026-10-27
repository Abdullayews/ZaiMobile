use std::time::{SystemTime, UNIX_EPOCH};

// ---------- Sabitlər ----------
pub const BASE: &str = "https://chat.z.ai";
pub const FE_VERSION: &str = "prod-fe-1.1.121";
pub const MOBILE_UA: &str = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36";

const SIGNATURE_KEY: &str = "key-@@@@)))()((9))-xxxx&&&%%%%%";

// ---------- Köməkçi funksiyalar ----------

pub fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

pub fn gen_uuid() -> String {
    uuid::Uuid::new_v4().to_string()
}

/// URLSearchParams (application/x-www-form-urlencoded) kodlaşdırması
fn form_encode(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for b in s.bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'*' | b'-' | b'.' | b'_' => {
                out.push(b as char)
            }
            b' ' => out.push('+'),
            _ => out.push_str(&format!("%{:02X}", b)),
        }
    }
    out
}

/// JS: new Date().toISOString()
fn iso_now() -> String {
    // 2026-10-27T21:30:00.123Z — UTC
    let ms = now_ms();
    let secs = ms / 1000;
    let days = secs / 86400;
    let (y, mo, d, h, mi, s) = civil_from_days(days as i64);
    format!(
        "{:04}-{:02}-{:02}T{:02}:{:02}:{:02}.{:03}Z",
        y, mo, d, h, mi, s,
        ms % 1000
    )
}

/// JS: new Date().toUTCString() — "Tue, 28 Oct 2026 01:30:00 GMT"
fn utc_string_now() -> String {
    let secs = now_ms() / 1000;
    let days = (secs / 86400) as i64;
    let (y, mo, d, h, mi, s) = civil_from_days(days);
    const WD: [&str; 7] = ["Thu", "Fri", "Sat", "Sun", "Mon", "Tue", "Wed"];
    const MO: [&str; 12] = [
        "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    ];
    let wd = WD[(days % 7) as usize];
    format!(
        "{}, {:02} {} {:04} {:02}:{:02}:{:02} GMT",
        wd, d, MO[(mo - 1) as usize], y, h, mi, s
    )
}

/// Gün sayından mülki tarixə (Howard Hinnant alqoritmi)
fn civil_from_days(z: i64) -> (i64, u32, u32, u32, u32, u32) {
    let z = z + 719468;
    let era = if z >= 0 { z } else { z - 146096 } / 146097;
    let doe = (z - era * 146097) as u64;
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
    let y = yoe as i64 + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let m = if mp < 10 { mp + 3 } else { mp - 9 } as u32;
    let y = if m <= 2 { y + 1 } else { y };
    let secs_of_day = (now_ms() / 1000) % 86400;
    (
        y,
        m,
        d,
        (secs_of_day / 3600) as u32,
        ((secs_of_day % 3600) / 60) as u32,
        (secs_of_day % 60) as u32,
    )
}

/// HMAC-SHA256 → hex (kiçik hərf)
fn hmac_sha256_hex(key: &[u8], msg: &[u8]) -> String {
    use sha2::{Digest, Sha256};
    // RFC 2104
    let block = 64usize;
    let mut k = [0u8; 64];
    if key.len() > block {
        let h = Sha256::digest(key);
        k[..32].copy_from_slice(&h);
    } else {
        k[..key.len()].copy_from_slice(key);
    }
    let mut ipad = [0x36u8; 64];
    let mut opad = [0x5cu8; 64];
    for i in 0..64 {
        ipad[i] ^= k[i];
        opad[i] ^= k[i];
    }
    let mut inner = Sha256::new();
    inner.update(&ipad);
    inner.update(msg);
    let inner_hash = inner.finalize();
    let mut outer = Sha256::new();
    outer.update(&opad);
    outer.update(&inner_hash);
    let out = outer.finalize();
    out.iter().map(|b| format!("{:02x}", b)).collect()
}

// ---------- Signature (X-Signature) ----------

pub struct SignatureInfo {
    pub signature: String,
    pub timestamp: String,
    pub request_id: String,
    pub url_params: String,
}

/// Veb tətbiqinin Zse() + Kse() funksiyalarının dəqiq Rust tərcüməsi
pub fn make_signature(prompt: &str, token: &str, user_id: &str) -> SignatureInfo {
    let ts = now_ms();
    let ts_str = ts.to_string();
    let request_id = gen_uuid();

    // sortedPayload: Object.entries({timestamp, requestId, user_id}).sort().join(",")
    // sıralama: requestId < timestamp < user_id
    let sorted_payload = format!("requestId,{},timestamp,{},user_id,{}", request_id, ts_str, user_id);

    // base64(utf8(prompt))
    let prompt_b64 = {
        let bytes = prompt.trim().as_bytes();
        const T: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        let mut out = String::new();
        for chunk in bytes.chunks(3) {
            let b = [chunk[0], *chunk.get(1).unwrap_or(&0), *chunk.get(2).unwrap_or(&0)];
            let n = ((b[0] as u32) << 16) | ((b[1] as u32) << 8) | b[2] as u32;
            out.push(T[(n >> 18) as usize & 63] as char);
            out.push(T[(n >> 12) as usize & 63] as char);
            out.push(if chunk.len() > 1 { T[(n >> 6) as usize & 63] as char } else { '=' });
            out.push(if chunk.len() > 2 { T[n as usize & 63] as char } else { '=' });
        }
        out
    };

    // data = sortedPayload | base64(prompt) | timestamp
    let data = format!("{}|{}|{}", sorted_payload, prompt_b64, ts_str);

    // g = floor(ts / (5*60*1000))
    let g = ts / 300_000;
    let subkey = hmac_sha256_hex(SIGNATURE_KEY.as_bytes(), g.to_string().as_bytes());
    let signature = hmac_sha256_hex(subkey.as_bytes(), data.as_bytes());

    // urlParams — Kse-in o obyekti (mobile brauzer dəyərləri ilə)
    let fields: Vec<(&str, String)> = vec![
        ("timestamp", ts_str.clone()),
        ("requestId", request_id.clone()),
        ("user_id", user_id.to_string()),
        ("version", "0.0.1".into()),
        ("platform", "web".into()),
        ("token", token.to_string()),
        ("user_agent", MOBILE_UA.into()),
        ("language", "en-US".into()),
        ("languages", "en-US".into()),
        ("timezone", "Asia/Baku".into()),
        ("cookie_enabled", "true".into()),
        ("screen_width", "1080".into()),
        ("screen_height", "2400".into()),
        ("screen_resolution", "1080x2400".into()),
        ("viewport_height", "800".into()),
        ("viewport_width", "400".into()),
        ("viewport_size", "400x800".into()),
        ("color_depth", "24".into()),
        ("pixel_ratio", "2.625".into()),
        ("current_url", "https://chat.z.ai/".into()),
        ("pathname", "/".into()),
        ("search", String::new()),
        ("hash", String::new()),
        ("host", "chat.z.ai".into()),
        ("hostname", "chat.z.ai".into()),
        ("protocol", "https:".into()),
        ("referrer", String::new()),
        ("title", "Z.ai - Advanced AI Chatbot & Agent powered by GLM-5.3-Flash".into()),
        ("timezone_offset", "-240".into()),
        ("local_time", iso_now()),
        ("utc_time", utc_string_now()),
        ("is_mobile", "true".into()),
        ("is_touch", "true".into()),
        ("max_touch_points", "5".into()),
        ("browser_name", "Chrome".into()),
        ("os_name", "Android".into()),
    ];
    let url_params = fields
        .iter()
        .map(|(k, v)| format!("{}={}", k, form_encode(v)))
        .collect::<Vec<_>>()
        .join("&");

    SignatureInfo {
        signature,
        timestamp: ts_str,
        request_id,
        url_params,
    }
}

// ---------- HTTP klienti ----------

pub struct Client {
    pub http: reqwest::blocking::Client,
    pub token: String,
    pub user_id: String,
    pub user_name: String,
    pub is_guest: bool,
    pub device_id: String,
}

impl Client {
    pub fn new() -> Result<Self, String> {
        let http = reqwest::blocking::Client::builder()
            .user_agent(MOBILE_UA)
            .cookie_store(true)
            .timeout(std::time::Duration::from_secs(30))
            .build()
            .map_err(|e| e.to_string())?;
        Ok(Client {
            http,
            token: String::new(),
            user_id: String::new(),
            user_name: String::new(),
            is_guest: true,
            device_id: make_device_id(),
        })
    }

    /// Ucuz klon (reqwest Client Arc-dır)
    pub fn shallow_clone(&self) -> Client {
        Client {
            http: self.http.clone(),
            token: self.token.clone(),
            user_id: self.user_id.clone(),
            user_name: self.user_name.clone(),
            is_guest: self.is_guest,
            device_id: self.device_id.clone(),
        }
    }

    fn common_headers(&self) -> Vec<(&str, String)> {
        vec![
            ("X-Device-ID", self.device_id.clone()),
            ("X-FE-Version", FE_VERSION.to_string()),
            ("Accept-Language", "en-US".into()),
        ]
    }

    /// Qonaq tokeni al: GET /api/v1/auths/
    pub fn guest_auth(&mut self) -> Result<(), String> {
        let mut req = self.http.get(format!("{}/api/v1/auths/", BASE));
        for (k, v) in self.common_headers() {
            req = req.header(k, v);
        }
        let resp = req.send().map_err(|e| format!("Şəbəkə xətası: {}", e))?;
        let status = resp.status();
        let body: serde_json::Value = resp
            .json()
            .map_err(|_| format!("Cavab oxunmadı (HTTP {})", status))?;
        if !status.is_success() {
            return Err(format!(
                "Giriş alınmadı: {}",
                body["detail"].as_str().unwrap_or("bilinməyən xəta")
            ));
        }
        self.token = body["token"]
            .as_str()
            .ok_or("token tapılmadı")?
            .to_string();
        self.user_id = body["id"].as_str().unwrap_or("").to_string();
        self.user_name = body["name"].as_str().unwrap_or("Qonaq").to_string();
        self.is_guest = body["role"].as_str() == Some("guest");
        Ok(())
    }

    /// Mövcud tokeni yoxlа: GET /api/v1/auths/ Authorization ilə
    pub fn verify_token(&mut self, token: &str) -> Result<(), String> {
        let mut req = self
            .http
            .get(format!("{}/api/v1/auths/", BASE))
            .header("Authorization", format!("Bearer {}", token));
        for (k, v) in self.common_headers() {
            req = req.header(k, v);
        }
        let resp = req.send().map_err(|e| format!("Şəbəkə xətası: {}", e))?;
        let status = resp.status();
        if status.as_u16() == 403 || status.as_u16() == 401 {
            return Err("Token etibarsızdır və ya vaxtı bitib".into());
        }
        let body: serde_json::Value = resp
            .json()
            .map_err(|_| format!("Cavab oxunmadı (HTTP {})", status))?;
        self.token = token.to_string();
        self.user_id = body["id"].as_str().unwrap_or("").to_string();
        self.user_name = body["name"].as_str().unwrap_or("İstifadəçi").to_string();
        self.is_guest = body["role"].as_str() == Some("guest");
        Ok(())
    }

    /// Brauzerin açılış sorğularını təkrarla (risk motoruna qarşı "warm-up")
    pub fn warm_up(&self) {
        let urls = [
            format!("{}/api/config", BASE),
            format!("{}/api/models", BASE),
            format!("{}/api/v1/users/user/settings/update", BASE),
            format!("{}/api/v1/user-access/accessible?resource=qrcode-bind", BASE),
            format!("{}/api/v1/scene-cfg/", BASE),
        ];
        for (i, url) in urls.iter().enumerate() {
            let mut req = if i == 2 {
                self.http
                    .post(url)
                    .header("Authorization", format!("Bearer {}", self.token))
                    .header("Content-Type", "application/json")
                    .json(&serde_json::json!({"ui": {}}))
            } else {
                self.http
                    .get(url)
                    .header("Authorization", format!("Bearer {}", self.token))
            };
            for (k, v) in self.common_headers() {
                req = req.header(k, v);
            }
            let _ = req.send();
        }
    }

    /// Modellər: GET /api/models
    pub fn models(&self) -> Result<Vec<(String, String)>, String> {
        let mut req = self
            .http
            .get(format!("{}/api/models", BASE))
            .header("Authorization", format!("Bearer {}", self.token));
        for (k, v) in self.common_headers() {
            req = req.header(k, v);
        }
        let resp = req.send().map_err(|e| e.to_string())?;
        let body: serde_json::Value = resp.json().map_err(|e| e.to_string())?;
        let mut out = Vec::new();
        if let Some(arr) = body["data"].as_array() {
            for m in arr {
                if let (Some(id), Some(name)) = (m["id"].as_str(), m["name"].as_str()) {
                    out.push((id.to_string(), name.to_string()));
                }
            }
        }
        Ok(out)
    }

    /// Chat siyahısı: GET /api/v1/chats/?page=0&type=default
    pub fn chat_list(&self) -> Result<Vec<ChatSummary>, String> {
        let mut req = self
            .http
            .get(format!("{}/api/v1/chats/?page=0&type=default", BASE))
            .header("Authorization", format!("Bearer {}", self.token));
        for (k, v) in self.common_headers() {
            req = req.header(k, v);
        }
        let resp = req.send().map_err(|e| e.to_string())?;
        let body: serde_json::Value = resp.json().map_err(|e| e.to_string())?;
        let mut out = Vec::new();
        if let Some(arr) = body.as_array() {
            for c in arr {
                let id = c["id"].as_str().unwrap_or("").to_string();
                if id.is_empty() {
                    continue;
                }
                out.push(ChatSummary {
                    id,
                    title: c["title"].as_str().unwrap_or("Söhbət").to_string(),
                    updated_at: c["updated_at"].as_i64().unwrap_or(0),
                });
            }
        }
        Ok(out)
    }

    /// Chat detalı: GET /api/v1/chats/{id}
    pub fn chat_detail(&self, id: &str) -> Result<serde_json::Value, String> {
        let mut req = self
            .http
            .get(format!("{}/api/v1/chats/{}", BASE, id))
            .header("Authorization", format!("Bearer {}", self.token));
        for (k, v) in self.common_headers() {
            req = req.header(k, v);
        }
        let resp = req.send().map_err(|e| e.to_string())?;
        let body: serde_json::Value = resp.json().map_err(|e| e.to_string())?;
        Ok(body)
    }

    /// Yeni chat: POST /api/v1/chats/new
    pub fn new_chat(&self, prompt: &str, model: &str, user_msg_id: &str) -> Result<String, String> {
        let ts = now_ms();
        let chat = serde_json::json!({
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
        });
        let mut req = self
            .http
            .post(format!("{}/api/v1/chats/new", BASE))
            .header("Authorization", format!("Bearer {}", self.token))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("x-region", "overseas")
            .json(&serde_json::json!({ "chat": chat }));
        for (k, v) in self.common_headers() {
            req = req.header(k, v);
        }
        let resp = req.send().map_err(|e| format!("Şəbəkə: {}", e))?;
        let status = resp.status();
        let body: serde_json::Value = resp.json().unwrap_or(serde_json::Value::Null);
        if !status.is_success() {
            return Err(format!(
                "Chat yaradılmadı (HTTP {}): {}",
                status,
                body["detail"].as_str().unwrap_or("?")
            ));
        }
        body["id"]
            .as_str()
            .map(|s| s.to_string())
            .ok_or_else(|| "chat id yoxdur".into())
    }

    /// Chat-i yenilə (history saxla): POST /api/v1/chats/{id}
    pub fn update_chat(&self, id: &str, chat_obj: &serde_json::Value) -> Result<(), String> {
        let mut req = self
            .http
            .post(format!("{}/api/v1/chats/{}", BASE, id))
            .header("Authorization", format!("Bearer {}", self.token))
            .header("Content-Type", "application/json")
            .json(&serde_json::json!({ "chat": chat_obj }));
        for (k, v) in self.common_headers() {
            req = req.header(k, v);
        }
        let resp = req.send().map_err(|e| e.to_string())?;
        if !resp.status().is_success() {
            return Err(format!("Chat yenilənmədi (HTTP {})", resp.status()));
        }
        Ok(())
    }
}

fn make_device_id() -> String {
    use std::hash::{BuildHasher, Hasher, RandomState};
    let mut h = RandomState::new().build_hasher();
    h.write_u64(now_ms());
    h.write_usize(std::process::id() as usize);
    let v = h.finish();
    // uid_ + 13 əlifba-rəqəm (brauzer formatı: /^uid_[A-Za-z0-9]{7,}$/)
    const CS: &[u8] = b"abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    let mut out = String::from("uid_");
    let mut x = v ^ (v >> 29);
    for _ in 0..13 {
        x = x.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
        out.push(CS[((x >> 33) as usize) % CS.len()] as char);
    }
    out
}

#[derive(Clone)]
pub struct ChatSummary {
    pub id: String,
    pub title: String,
    pub updated_at: i64,
}
