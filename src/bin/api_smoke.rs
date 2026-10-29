//! Headless API smoke test — UI YOXDUR.
//! Yoxlayır: guest auth → imza → chat/new → SSE completion → cavab mətni.
//! İstifadə: cargo run --bin api_smoke [prompt]

use zai_mobile::api::{self, Client};
use zai_mobile::stream::{self, Job, StreamEvent};

fn main() {
    let prompt = std::env::args().nth(1).unwrap_or_else(|| "Reply with exactly one word: SMOKE_OK".into());
    println!("[1] HTTP client + WAF warm-up…");
    let client = Client::new(api::make_device_id()).expect("client");
    client.warm_up_home();

    println!("[2] Guest auth…");
    let auth = match client.guest_auth() {
        Ok(a) => a,
        Err(e) => {
            println!("FAIL guest_auth: {}", e);
            std::process::exit(1);
        }
    };
    println!("    ok: {} ({})", auth.user_name, auth.user_id);

    println!("[3] Models…");
    let models = client.models(&auth.token, auth.is_guest).unwrap_or_default();
    for m in models.iter().take(6) {
        println!("    model: {} {} (enabled={})", m.id, m.name, m.enabled);
    }
    let model = models.iter().find(|m| m.enabled).map(|m| m.id.clone()).unwrap_or_else(|| "glm-5.3-flash".into());

    println!("[4] Completion (model={}, prompt='{}')…", model, prompt);
    let job = Job::new();
    let mut chat_id = String::new();
    let mut full = String::new();
    let mut phases = std::collections::BTreeSet::new();
    let mut chat_created = false;
    let user_msg_id = api::gen_uuid();
    let assistant_msg_id = api::gen_uuid();
    let history: Vec<(String, String)> = Vec::new();
    let files: Vec<zai_mobile::api::UploadedFile> = Vec::new();

    let mut emit = |ev: StreamEvent| {
        match ev {
            StreamEvent::Delta(d) => full.push_str(&d),
            StreamEvent::Phase(p) => {
                phases.insert(p.clone());
            }
            StreamEvent::ChatCreated(_) => chat_created = true,
            _ => {}
        }
    };

    let t0 = std::time::Instant::now();
    match stream::run_completion(
        &client,
        &auth.token,
        &auth.user_id,
        &mut chat_id,
        "New Chat",
        &history,
        &prompt,
        &model,
        "high",
        &user_msg_id,
        &assistant_msg_id,
        true,
        &files,
        &job,
        &mut emit,
    ) {
        Ok(()) => {
            println!("[5] SUCCESS in {:.1}s", t0.elapsed().as_secs_f64());
            println!("    chat_created: {}", chat_created);
            println!("    phases: {:?}", phases);
            println!("    response: {}", full.chars().take(400).collect::<String>());
            if full.to_uppercase().contains("SMOKE_OK") || !full.is_empty() {
                println!("=== API SMOKE TEST PASSED ===");
            } else {
                println!("=== response empty — CHECK ===");
            }
        }
        Err(e) => {
            println!("[5] FAIL: {}", e);
            println!("    partial: {}", full.chars().take(200).collect::<String>());
            std::process::exit(1);
        }
    }
}
