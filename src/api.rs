//! chat.z.ai HTTP API — veb tətbiqinin dəqiq protokolu.
//! Bütün sorğular: cookie + Origin/Referer + X-Device-ID/X-FE-Version başlıqları ilə,
//! WAF 405/403 bloklarına qarşı avtomatik retry.

use serde_json::{json, Value};
use std::time::Duration;

/// Baza URL — test/mock üçün ZAI_BASE env ilə üstələnə bilər.
pub fn base() -> &'static str {
    static B: std::sync::OnceLock<String> = std::sync::OnceLock::new();
    B.get_or_init(|| std::env::var("ZAI_BASE").unwrap_or_else(|_| "https://chat.z.ai".into()))
}

pub const FE_VERSION: &str = "prod-fe-1.1.123";
pub const MOBILE_UA: &str = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36";

const SIGNATURE_KEY: &str = "key-@@@@)))()((9))-xxxx&&&%%%%%";

// ---------------- Köməkçilər ----------------

pub fn now_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

pub fn gen_uuid() -> String {
    // uuid crate-inə ehtiyac yoxdur: v4 formatında unikal id (zaman + sayğac +
    // process + thread-local xorshift). Unikallıq — bütün istifadə yerləri üçün
    // (message id, requestId, session_id) kifayətdir.
    use std::cell::Cell;
    thread_local! {
        static STATE: Cell<u64> = const { Cell::new(0) };
        static COUNTER: Cell<u64> = const { Cell::new(0) };
    }
    let mut x = STATE.with(|s| {
        let mut v = s.get();
        if v == 0 {
            v = now_ms() ^ ((std::process::id() as u64) << 32) ^ 0x9E3779B97F4A7C15;
        }
        v ^= v << 13;
        v ^= v >> 7;
        v ^= v << 17;
        s.set(v);
        v
    });
    let ctr = COUNTER.with(|c| {
        let n = c.get().wrapping_add(1);
        c.set(n);
        n
    });
    x ^= ctr.wrapping_mul(0xBF58476D1CE4E5B9);
    let h = format!("{:08x}-{:04x}-{:04x}-{:04x}-{:012x}",
        (x as u32),
        ((x >> 32) as u16),
        (((x >> 48) ^ ctr) as u16),
        (((x >> 19) ^ 0xB) as u16),
        x.rotate_left(29) & 0xFFFF_FFFF_FFFF);
    h
}

/// Retry gecikməsinə ±20% jitter (birdən çox klientin sinxron "thundering
/// herd" retry-larının qarşısını alır)
pub fn jitter_ms(ms: u64) -> u64 {
    use std::cell::Cell;
    thread_local! {
        static J: Cell<u64> = const { Cell::new(0x243F6A8885A308D3) };
    }
    let f = J.with(|j| {
        let mut v = j.get();
        v ^= v << 13;
        v ^= v >> 7;
        v ^= v << 17;
        j.set(v);
        (v % 1000) as f64 / 1000.0 // 0..1
    });
    let delta = (ms as f64 * 0.2) as u64;
    ms + (f * (2 * delta) as f64) as u64 - delta
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

fn iso_now() -> String {
    // 2026-10-28T16:35:01.123Z (UTC) — chrono ilə
    chrono::Utc::now().format("%Y-%m-%dT%H:%M:%S%.3fZ").to_string()
}

fn utc_string_now() -> String {
    chrono::Utc::now().format("%a, %d %b %Y %H:%M:%S GMT").to_string()
}

/// HMAC-SHA256 → hex
fn hmac_sha256_hex(key: &[u8], msg: &[u8]) -> String {
    use sha2::{Digest, Sha256};
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
    inner.update(ipad);
    inner.update(msg);
    let inner_hash = inner.finalize();
    let mut outer = Sha256::new();
    outer.update(opad);
    outer.update(inner_hash);
    outer.finalize().iter().map(|b| format!("{:02x}", b)).collect()
}

// ---------------- Signature ----------------

pub struct SignatureInfo {
    pub signature: String,
    pub timestamp: String,
    pub url_params: String,
}

/// Veb tətbiqinin imza alqoritminin dəqiq Rust tərcüməsi (canlı yoxlanıb)
pub fn make_signature(prompt: &str, token: &str, user_id: &str) -> SignatureInfo {
    let ts = now_ms();
    let ts_str = ts.to_string();
    let request_id = gen_uuid();

    let sorted_payload = format!("requestId,{},timestamp,{},user_id,{}", request_id, ts_str, user_id);

    // base64(utf8(prompt))
    let prompt_b64 = {
        // BASE64 standart kitabxanası
        const T: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        let bytes = prompt.trim().as_bytes();
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

    let data = format!("{}|{}|{}", sorted_payload, prompt_b64, ts_str);

    let g = ts / 300_000;
    let subkey = hmac_sha256_hex(SIGNATURE_KEY.as_bytes(), g.to_string().as_bytes());
    let signature = hmac_sha256_hex(subkey.as_bytes(), data.as_bytes());

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

    SignatureInfo { signature, timestamp: ts_str, url_params }
}

// ---------------- Client ----------------

/// Reqwest blocking Client — cookie store ilə (acw_tc WAF cookie-ləri daşıyır)
pub struct Client {
    pub http: reqwest::blocking::Client,
    pub device_id: String,
}

#[derive(Clone, Debug)]
pub struct AuthInfo {
    pub token: String,
    pub user_id: String,
    pub user_name: String,
    pub is_guest: bool,
}

#[derive(Clone, Debug)]
pub struct ModelInfo {
    pub id: String,
    pub name: String,
    pub enabled: bool,          // guest üçün min_available_level=1 olanlar
    pub effort_levels: Vec<String>,
}

#[derive(Clone, Debug)]
pub struct ConvSummary {
    pub id: String,
    pub title: String,
    pub updated_at: i64,
}

impl Client {
    pub fn new(device_id: String) -> Result<Self, String> {
        let mut builder = reqwest::blocking::Client::builder()
            .user_agent(MOBILE_UA)
            .cookie_store(true)
            // SSE axını üçün ÜMUMİ timeout YOX — yalnız qoşulma limitli.
            .timeout(None)
            .connect_timeout(Duration::from_secs(30))
            // Ölü-bağlantı aşkarlanması: 60s-dən sonra keepalive zondları
            // göndərilir; zondlar (və ya göndərilən data) 180s ACK
            // almayarsa bağlantı xəta ilə bağlanır → bloklanan SSE oxu
            // qayıdır → stream.rs-in RETRY bərpası işə düşür.
            // Əvvəl bu yox idi: tam susan (black-hole) axın QALICI bloklayırdı
            // — stream.rs-dəki 150s idle watchdog yalnız sətir GƏLƏNDƏ yoxlanır,
            // bloklanan oxu zamanı heç vaxt çatmır. Normal «server düşünür»
            // fasiləsində zondlar ACK alınır → yalançı kəsinti YOX.
            .tcp_keepalive(Duration::from_secs(60));
        #[cfg(any(target_os = "android", target_os = "fuchsia", target_os = "linux"))]
        {
            builder = builder.tcp_user_timeout(Duration::from_secs(180));
        }
        let http = builder.build().map_err(|e| e.to_string())?;
        Ok(Client { http, device_id })
    }

    fn req(&self, method: reqwest::Method, url: &str) -> reqwest::blocking::RequestBuilder {
        self.http
            .request(method, url)
            .header("User-Agent", MOBILE_UA)
            .header("X-Device-ID", &self.device_id)
            .header("X-FE-Version", FE_VERSION)
            .header("Accept-Language", "en-US")
            .header("Origin", base())
            .header("Referer", "https://chat.z.ai/")
    }

    /// WAF blok mu? (Alibaba CDN 405/403 HTML səhifəsi qaytarır)
    fn is_waf_status(code: u16) -> bool {
        code == 405 || code == 403 || code == 429 || code == 509
    }

    /// Sorğunu WAF bloklarına qarşı retry ilə göndər.
    /// HTML body = WAF bloku; JSON body = real API cavabı.
    pub fn send_with_retry(
        &self,
        build: impl Fn() -> reqwest::blocking::RequestBuilder,
        attempts: u32,
    ) -> Result<reqwest::blocking::Response, String> {
        let mut delay = 2.5f64;
        let mut last_err = String::from("WAF block");
        for _attempt in 0..attempts {
            let resp = build().send().map_err(|e| format!("Network error: {}", e))?;
            let status = resp.status();
            if Self::is_waf_status(status.as_u16()) {
                // body-ni oxuyub HTML olduğunu yoxla (WAF) — sonra retry
                let body = resp.text().unwrap_or_default();
                if body.contains("<!doctype") || body.contains("<html") {
                    last_err = format!("WAF block (HTTP {})", status);
                    std::thread::sleep(Duration::from_millis(jitter_ms((delay * 1000.0) as u64)));
                    delay = (delay * 1.35).min(9.0);
                    continue;
                }
                // HTML deyil — real API xətasıdı, cavabı geri qaytar
                // (body artıq oxunduğundan yenidən qurmaq lazımdır)
                let retry = build().send().map_err(|e| format!("Network error: {}", e))?;
                return Ok(retry);
            }
            return Ok(resp);
        }
        Err(format!("{}. Please check your connection and try again.", last_err))
    }

    // ------------- Endpointlər -------------

    /// Ana səhifə — WAF cookie-lərini (acw_tc) almaq üçün
    pub fn warm_up_home(&self) {
        let _ = self.req(reqwest::Method::GET, &format!("{}/", base())).send();
    }

    /// Qonaq tokeni: GET /api/v1/auths/
    pub fn guest_auth(&self) -> Result<AuthInfo, String> {
        let resp = self.send_with_retry(
            || self.req(reqwest::Method::GET, &format!("{}/api/v1/auths/", base())),
            6,
        )?;
        let status = resp.status();
        let body: Value = resp.json().map_err(|_| format!("Bad response (HTTP {})", status))?;
        if !status.is_success() {
            return Err(format!("Guest sign-in failed: {}", body["detail"].as_str().unwrap_or("?")));
        }
        Ok(AuthInfo {
            token: body["token"].as_str().ok_or("token missing")?.to_string(),
            user_id: body["id"].as_str().unwrap_or("").to_string(),
            user_name: body["name"].as_str().unwrap_or("Guest").to_string(),
            is_guest: body["role"].as_str() == Some("guest"),
        })
    }

    /// Email + şifrə ilə giriş: POST /api/v1/auths/signin
    pub fn signin(&self, email: &str, password: &str) -> Result<AuthInfo, String> {
        let resp = self.send_with_retry(
            || {
                self.req(reqwest::Method::POST, &format!("{}/api/v1/auths/signin", base()))
                    .header("Content-Type", "application/json")
                    .json(&json!({"email": email, "password": password}))
            },
            6,
        )?;
        let status = resp.status();
        let body: Value = resp.json().unwrap_or(Value::Null);
        if !status.is_success() {
            let detail = body["detail"].as_str().unwrap_or("");
            if detail.contains("captcha") {
                return Err("CAPTCHA_REQUIRED".into());
            }
            if detail.is_empty() {
                return Err("Sign-in failed: check your email and password".into());
            }
            return Err(format!("Sign-in failed: {}", detail));
        }
        Ok(AuthInfo {
            token: body["token"].as_str().ok_or("token missing")?.to_string(),
            user_id: body["id"].as_str().unwrap_or("").to_string(),
            user_name: body["name"].as_str().unwrap_or("User").to_string(),
            is_guest: body["role"].as_str() == Some("guest"),
        })
    }

    /// Token yoxla: GET /api/v1/auths/ + Bearer
    pub fn verify_token(&self, token: &str) -> Result<AuthInfo, String> {
        let resp = self.send_with_retry(
            || {
                self.req(reqwest::Method::GET, &format!("{}/api/v1/auths/", base()))
                    .header("Authorization", format!("Bearer {}", token))
            },
            6,
        )?;
        let status = resp.status();
        if status.as_u16() == 401 || status.as_u16() == 403 {
            return Err("TOKEN_INVALID".into());
        }
        let body: Value = resp.json().map_err(|_| format!("Bad response (HTTP {})", status))?;
        if !status.is_success() {
            return Err("TOKEN_INVALID".into());
        }
        Ok(AuthInfo {
            token: token.to_string(),
            user_id: body["id"].as_str().unwrap_or("").to_string(),
            user_name: body["name"].as_str().unwrap_or("User").to_string(),
            is_guest: body["role"].as_str() == Some("guest"),
        })
    }

    /// Modellər: GET /api/models — guest üçün min_available_level filtri
    pub fn models(&self, token: &str, is_guest: bool) -> Result<Vec<ModelInfo>, String> {
        let resp = self.send_with_retry(
            || {
                self.req(reqwest::Method::GET, &format!("{}/api/models", base()))
                    .header("Authorization", format!("Bearer {}", token))
            },
            6,
        )?;
        let body: Value = resp.json().map_err(|e| e.to_string())?;
        let mut out = Vec::new();
        if let Some(arr) = body["data"].as_array() {
            for m in arr {
                let id = m["id"].as_str().unwrap_or("").to_string();
                if id.is_empty() {
                    continue;
                }
                let name = m["name"].as_str().unwrap_or(&id).to_string();
                let min_level = m["info"]["min_available_level"].as_i64().unwrap_or(1);
                let enabled = !is_guest || min_level <= 1;
                let effort_levels: Vec<String> = m["info"]["meta"]["info"]["thinking_effort_level"]
                    .as_array()
                    .map(|a| a.iter().filter_map(|v| v.as_str().map(String::from)).collect())
                    .unwrap_or_default();
                out.push(ModelInfo { id, name, enabled, effort_levels });
            }
        }
        if out.is_empty() {
            out.push(ModelInfo {
                id: "x-preview-l".into(),
                name: "GLM-5.3-Flash".into(),
                enabled: true,
                effort_levels: vec!["low".into(), "high".into(), "max".into()],
            });
        }
        Ok(out)
    }

    /// Söhbət siyahısı: GET /api/v1/chats/?page=N&type=default — bütün
    /// səhifələr (əvvəl yalnız 1-ci səhifə çəkilirdi, tarixçə kəsilirdi).
    /// Qoruyucular: API səhifələməni görmədiyində eyni cavab təkrar gələrsə
    /// (ilk id üst-üstə düşərsə) dayan; maksimum 10 səhifə / 300 söhbət.
    pub fn conversations(&self, token: &str) -> Result<Vec<ConvSummary>, String> {
        let mut out: Vec<ConvSummary> = Vec::new();
        let mut prev_first_id: Option<String> = None;
        let mut first_page_len: Option<usize> = None;
        let mut page = 1u32;
        loop {
            let resp = self.send_with_retry(
                || {
                    self.req(
                        reqwest::Method::GET,
                        &format!("{}/api/v1/chats/?page={}&type=default", base(), page),
                    )
                    .header("Authorization", format!("Bearer {}", token))
                },
                6,
            )?;
            let body: Value = match resp.json() {
                Ok(v) => v,
                Err(_) => break,
            };
            let arr = body.as_array().cloned().unwrap_or_default();
            if arr.is_empty() {
                break;
            }
            // API səhifəni iqnor edibsə eyni siyahı qayıdır → təkrarı dayandır
            let cur_first_id = arr[0]["id"].as_str().map(|s| s.to_string());
            if page > 1 && cur_first_id.is_some() && cur_first_id == prev_first_id {
                break;
            }
            prev_first_id = cur_first_id;
            for c in arr {
                let id = c["id"].as_str().unwrap_or("").to_string();
                if id.is_empty() {
                    continue;
                }
                out.push(ConvSummary {
                    id,
                    title: c["title"].as_str().unwrap_or("Chat").to_string(),
                    updated_at: c["updated_at"].as_i64().unwrap_or(0),
                });
            }
            let n = out.len();
            match first_page_len {
                None => first_page_len = Some(n),
                Some(fl) => {
                    // sonrakı səhifə tam deyilsə — bu, sonuncudur
                    if n - fl < fl {
                        break;
                    }
                }
            }
            page += 1;
            if page > 10 || out.len() > 300 {
                break;
            }
        }
        Ok(out)
    }

    /// Söhbət detalı: GET /api/v1/chats/{id}
    pub fn chat_detail(&self, token: &str, id: &str) -> Result<Value, String> {
        let resp = self.send_with_retry(
            || {
                self.req(reqwest::Method::GET, &format!("{}/api/v1/chats/{}", base(), id))
                    .header("Authorization", format!("Bearer {}", token))
            },
            6,
        )?;
        let body: Value = resp.json().map_err(|e| e.to_string())?;
        Ok(body)
    }

    /// Söhbət sil: DELETE /api/v1/chats/{id}
    pub fn chat_delete(&self, token: &str, id: &str) -> Result<(), String> {
        let resp = self.send_with_retry(
            || {
                self.req(reqwest::Method::DELETE, &format!("{}/api/v1/chats/{}", base(), id))
                    .header("Authorization", format!("Bearer {}", token))
            },
            4,
        )?;
        if resp.status().is_success() {
            Ok(())
        } else {
            Err(format!("Delete failed (HTTP {})", resp.status()))
        }
    }

    /// Yeni söhbət (əlavələrlə): POST /api/v1/chats/new — content massiv ilə
    pub fn chat_new_with_files(&self, token: &str, prompt: &str, model: &str, user_msg_id: &str, files: &[UploadedFile]) -> Result<String, String> {
        let mut chat = chat_new_object(prompt, model, user_msg_id);
        if !files.is_empty() {
            let mut parts: Vec<Value> = vec![json!({"type": "text", "text": prompt})];
            for f in files {
                let url = if f.direct_url.is_empty() { &f.id } else { &f.direct_url };
                match f.media.as_str() {
                    "image" => parts.push(json!({"type": "image_url", "image_url": {"url": url}})),
                    "video" => parts.push(json!({"type": "video_url", "video_url": {"url": url}})),
                    _ => parts.push(json!({"type": "file_url", "file_url": {"url": url}})),
                }
            }
            if let Some(m) = chat["history"]["messages"]
                .as_object_mut()
                .and_then(|o| o.get_mut(user_msg_id))
                .and_then(|v| v.as_object_mut())
            {
                m.insert("content".into(), Value::Array(parts));
            }
        }
        let resp = self.send_with_retry(
            || {
                self.req(reqwest::Method::POST, &format!("{}/api/v1/chats/new", base()))
                    .header("Authorization", format!("Bearer {}", token))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("x-region", "overseas")
                    .json(&json!({ "chat": chat }))
            },
            12,
        )?;
        let status = resp.status();
        let body: Value = resp.json().unwrap_or(Value::Null);
        if !status.is_success() {
            return Err(format!(
                "Chat could not be created (HTTP {}): {}",
                status,
                body["detail"].as_str().unwrap_or("?")
            ));
        }
        body["id"].as_str().map(|s| s.to_string()).ok_or_else(|| "chat id missing".into())
    }

    /// Yeni söhbət: POST /api/v1/chats/new
    pub fn chat_new(&self, token: &str, prompt: &str, model: &str, user_msg_id: &str) -> Result<String, String> {
        let chat = chat_new_object(prompt, model, user_msg_id);
        let resp = self.send_with_retry(
            || {
                self.req(reqwest::Method::POST, &format!("{}/api/v1/chats/new", base()))
                    .header("Authorization", format!("Bearer {}", token))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("x-region", "overseas")
                    .json(&json!({ "chat": chat }))
            },
            12,
        )?;
        let status = resp.status();
        let body: Value = resp.json().unwrap_or(Value::Null);
        if !status.is_success() {
            return Err(format!(
                "Chat could not be created (HTTP {}): {}",
                status,
                body["detail"].as_str().unwrap_or("?")
            ));
        }
        body["id"].as_str().map(|s| s.to_string()).ok_or_else(|| "chat id missing".into())
    }

    /// Söhbəti yenilə (tarixçəni saxla): POST /api/v1/chats/{id}
    pub fn chat_update(&self, token: &str, chat_obj: &Value) -> Result<(), String> {
        let id = chat_obj["id"].as_str().unwrap_or("").to_string();
        let resp = self.send_with_retry(
            || {
                self.req(reqwest::Method::POST, &format!("{}/api/v1/chats/{}", base(), id))
                    .header("Authorization", format!("Bearer {}", token))
                    .header("Content-Type", "application/json")
                    .json(&json!({ "chat": chat_obj }))
            },
            3,
        )?;
        let _ = resp;
        Ok(())
    }

    /// Completion POST (SSE) — WAF retry ilə. Cavabın özini qaytarır.
    pub fn completions_post(
        &self,
        token: &str,
        url: &str,
        sig: &str,
        body: &Value,
    ) -> Result<reqwest::blocking::Response, String> {
        let resp = self.send_with_retry(
            || {
                self.req(reqwest::Method::POST, url)
                    .header("Authorization", format!("Bearer {}", token))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .header("X-Signature", sig)
                    .json(body)
            },
            12,
        )?;
        Ok(resp)
    }
    /// Fayl yüklə: POST /api/v1/storage/pfs/files/upload (multipart "file")
    /// Cavab: {code:0, data:{file:{id, filename, type, meta:{size}, ...}}}
    pub fn upload_file(&self, token: &str, bytes: Vec<u8>, filename: &str, mime: &str) -> Result<UploadedFile, String> {
        // Böyük şəkillər yükləmədən əvvəl endirilir (RAM/trafik qənaəti)
        let (bytes, mime, filename) = maybe_downscale_image(bytes, mime, filename);
        let bytes: &[u8] = &bytes;
        let mime: &str = &mime;
        let filename: &str = &filename;
        let resp = self.send_with_retry(
            || {
                let part = reqwest::blocking::multipart::Part::bytes(bytes.to_vec())
                    .file_name(filename.to_string())
                    .mime_str(mime)
                    .unwrap_or_else(|_| reqwest::blocking::multipart::Part::bytes(bytes.to_vec()).file_name(filename.to_string()));
                let form = reqwest::blocking::multipart::Form::new().part("file", part);
                self.req(reqwest::Method::POST, &format!("{}/api/v1/storage/pfs/files/upload", base()))
                    .header("Authorization", format!("Bearer {}", token))
                    .header("Accept", "application/json")
                    .multipart(form)
            },
            4,
        )?;
        let status = resp.status();
        let body: Value = resp.json().map_err(|_| format!("Upload failed (HTTP {})", status))?;
        if !status.is_success() || body["code"].as_i64() != Some(0) {
            let detail = body["detail"].as_str()
                .or_else(|| body["message"].as_str())
                .unwrap_or("upload failed");
            return Err(format!("Upload failed: {}", detail));
        }
        let f = &body["data"]["file"];
        let id = f["id"].as_str()
            .or_else(|| body["data"]["file"]["file_id"].as_str())
            .ok_or("file id missing")?
            .to_string();
        let media_type = media_kind(mime, filename);
        let size = f["meta"]["size"].as_i64()
            .or_else(|| body["data"]["file"]["size"].as_i64())
            .unwrap_or(bytes.len() as i64);
        Ok(UploadedFile {
            id,
            name: filename.to_string(),
            media: media_type.to_string(),
            size,
            direct_url: f["direct_url"].as_str().unwrap_or("").to_string(),
        })
    }
}

/// Böyük şəkli endir (downscale): >1MB PNG/JPEG və tərəflərindən biri
/// >2048px → 2048px-ə sığdır, JPEG q82. Yalnız nəticə kiçikdirsə qəbul.
fn maybe_downscale_image(bytes: Vec<u8>, mime: &str, filename: &str) -> (Vec<u8>, String, String) {
    const MAX_SIDE: u32 = 2048;
    const THRESHOLD: usize = 1024 * 1024;
    let keep = |b: Vec<u8>| (b, mime.to_string(), filename.to_string());
    if !mime.starts_with("image/") || mime == "image/gif" || bytes.len() <= THRESHOLD {
        return keep(bytes);
    }
    let dims = image::ImageReader::with_format(std::io::Cursor::new(&bytes), image::ImageFormat::Png)
        .into_dimensions()
        .ok()
        .or_else(|| {
            image::ImageReader::with_format(std::io::Cursor::new(&bytes), image::ImageFormat::Jpeg)
                .into_dimensions()
                .ok()
        });
    let (w, h) = match dims {
        Some(d) => d,
        None => return keep(bytes),
    };
    if w <= MAX_SIDE && h <= MAX_SIDE {
        return keep(bytes);
    }
    let Ok(img) = image::load_from_memory(&bytes) else {
        return keep(bytes);
    };
    let scaled = img.thumbnail(MAX_SIDE, MAX_SIDE);
    let mut out = std::io::Cursor::new(Vec::new());
    if scaled.write_to(&mut out, image::ImageFormat::Jpeg).is_err() {
        return keep(bytes);
    }
    let out = out.into_inner();
    if out.len() >= bytes.len() {
        return keep(bytes);
    }
    eprintln!(
        "[upload] şəkil endirildi: {}x{} ({}KB) → {}x{} ({}KB)",
        w, h, bytes.len() / 1024, scaled.width(), scaled.height(), out.len() / 1024
    );
    let name = if filename.to_ascii_lowercase().ends_with(".png") {
        format!("{}.jpg", filename.trim_end_matches(".png"))
    } else {
        filename.to_string()
    };
    (out, "image/jpeg".to_string(), name)
}

/// Yüklənmiş fayl məlumatı
#[derive(Clone, Debug)]
pub struct UploadedFile {
    pub id: String,
    pub name: String,
    pub media: String,     // "image" | "video" | "doc"
    pub size: i64,
    pub direct_url: String,
}

/// MIME/ad → media növü (veb tətbiqinin təsnifatı)
pub fn media_kind(mime: &str, name: &str) -> &'static str {
    let m = mime.to_ascii_lowercase();
    if m.starts_with("image/") {
        "image"
    } else if m.starts_with("video/") {
        "video"
    } else {
        let n = name.to_ascii_lowercase();
        if n.ends_with(".png") || n.ends_with(".jpg") || n.ends_with(".jpeg") || n.ends_with(".gif") || n.ends_with(".webp") || n.ends_with(".bmp") {
            "image"
        } else if n.ends_with(".mp4") || n.ends_with(".mov") || n.ends_with(".avi") || n.ends_with(".mkv") || n.ends_with(".webm") {
            "video"
        } else {
            "doc"
        }
    }
}

/// İnsan oxunan ölçü
pub fn human_size(bytes: i64) -> String {
    if bytes < 1024 {
        format!("{} B", bytes)
    } else if bytes < 1024 * 1024 {
        format!("{:.1} KB", bytes as f64 / 1024.0)
    } else if bytes < 1024 * 1024 * 1024 {
        format!("{:.1} MB", bytes as f64 / (1024.0 * 1024.0))
    } else {
        format!("{:.1} GB", bytes as f64 / (1024.0 * 1024.0 * 1024.0))
    }
}

/// chats/new üçün chat obyekti (veb formatı)
pub fn chat_new_object(prompt: &str, model: &str, user_msg_id: &str) -> Value {
    let ts = now_ms();
    json!({
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

/// Device ID: uid_ + 13 əlifba-rəqəm (brauzer formatı)
pub fn make_device_id() -> String {
    use std::hash::{BuildHasher, Hasher, RandomState};
    let mut h = RandomState::new().build_hasher();
    h.write_u64(now_ms());
    h.write_usize(std::process::id() as usize);
    let v = h.finish();
    const CS: &[u8] = b"abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    let mut out = String::from("uid_");
    let mut x = v ^ (v >> 29);
    for _ in 0..13 {
        x = x.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
        out.push(CS[((x >> 33) as usize) % CS.len()] as char);
    }
    out
}
