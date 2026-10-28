//! Controller: Slint callback-lərinin qoşulması, arxa plan işləri (worker),
//! state sinxronizasiyası və bütün ekran axınları.

use crate::api::{self, AuthInfo, Client, ConvSummary, ModelInfo};
use crate::store::{self, Persisted};
use crate::stream::{self, Job, StreamEvent};
use serde_json::Value;
use slint::{ComponentHandle, Weak};
use slint::Model as _;
use std::rc::Rc;
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::{Duration, Instant};

use crate::{App, AppWindow, ChatMessage, ConvItem, MessageBlock, ModelItem};

// ---------------- Daxili state ----------------

#[derive(Clone, Debug)]
pub struct RawMsg {
    pub id: String,
    pub role: String,
    pub text: String,
    pub streaming: bool,
    pub error: bool,
    pub stamp: String,
}

pub struct Core {
    pub client: Client,
    pub persisted: Mutex<Persisted>,
    pub chat_id: Mutex<String>,
    pub chat_title: Mutex<String>,
    pub messages: Mutex<Vec<RawMsg>>,
    pub current_job: Mutex<Option<Arc<Job>>>,
    pub models: Mutex<Vec<ModelInfo>>,
    pub boot_done: Mutex<bool>,
}

impl Core {
    fn new() -> Arc<Self> {
        let mut p = store::load();
        if p.device_id.is_empty() {
            p = Persisted::new();
        }
        let client = Client::new(p.device_id.clone()).expect("HTTP client");
        Arc::new(Core {
            client,
            persisted: Mutex::new(p),
            chat_id: Mutex::new(String::new()),
            chat_title: Mutex::new("New Chat".into()),
            messages: Mutex::new(Vec::new()),
            current_job: Mutex::new(None),
            models: Mutex::new(Vec::new()),
            boot_done: Mutex::new(false),
        })
    }
}

/// Zəhərlənmiş lock-i dözümlü oxu
fn l<T>(m: &Mutex<T>) -> MutexGuard<'_, T> {
    match m.lock() {
        Ok(g) => g,
        Err(e) => e.into_inner(),
    }
}

// ---------------- UI köməkçiləri ----------------

fn blocks_model(text: &str) -> slint::ModelRc<MessageBlock> {
    let blocks = stream::blocks_of(text);
    let vm: Rc<slint::VecModel<MessageBlock>> = Rc::new(slint::VecModel::default());
    for b in blocks {
        vm.push(MessageBlock {
            kind: b.kind,
            text: b.text.into(),
            lang: b.lang.into(),
        });
    }
    vm.into()
}

fn raw_to_ui(m: &RawMsg) -> ChatMessage {
    ChatMessage {
        id: m.id.clone().into(),
        role: m.role.clone().into(),
        text: m.text.clone().into(),
        blocks: if m.error || m.role == "user" {
            slint::ModelRc::default()
        } else {
            blocks_model(&m.text)
        },
        streaming: m.streaming,
        error: m.error,
        stamp: m.stamp.clone().into(),
    }
}

fn push_all_messages(core: &Core, weak: &Weak<AppWindow>) {
    let raws = l(&core.messages).clone();
    let _ = slint::invoke_from_event_loop({
        let weak = weak.clone();
        move || {
            if let Some(app) = weak.upgrade() {
                let g = app.global::<App>();
                let vm: Rc<slint::VecModel<ChatMessage>> = Rc::new(slint::VecModel::default());
                for r in &raws {
                    vm.push(raw_to_ui(r));
                }
                g.set_messages(vm.into());
            }
        }
    });
}

/// Yalnız son mesajı yenilə (axın zamanı — performans)
fn update_last_message(core: &Core, weak: &Weak<AppWindow>) {
    let raws = l(&core.messages);
    let last = match raws.last() {
        Some(m) => m.clone(),
        None => return,
    };
    let count = raws.len();
    drop(raws);
    let _ = slint::invoke_from_event_loop({
        let weak = weak.clone();
        move || {
            if let Some(app) = weak.upgrade() {
                let g = app.global::<App>();
                let model = g.get_messages();
                if let Some(vm) = model.as_any().downcast_ref::<slint::VecModel<ChatMessage>>() {
                    if vm.iter().count() == count {
                        vm.set_row_data(count - 1, raw_to_ui(&last));
                    }
                }
            }
        }
    });
}

fn set_streaming(core: &Core, weak: &Weak<AppWindow>, on: bool) {
    let _ = core;
    let _ = slint::invoke_from_event_loop({
        let weak = weak.clone();
        move || {
            if let Some(app) = weak.upgrade() {
                app.global::<App>().set_streaming(on);
            }
        }
    });
}

fn set_phase(weak: &Weak<AppWindow>, phase: &str) {
    let phase = phase.to_string();
    let _ = slint::invoke_from_event_loop({
        let weak = weak.clone();
        move || {
            if let Some(app) = weak.upgrade() {
                app.global::<App>().set_phase(phase.into());
            }
        }
    });
}

fn toast(weak: &Weak<AppWindow>, msg: &str) {
    let msg = msg.to_string();
    let msg_set = msg.clone();
    let _ = slint::invoke_from_event_loop({
        let weak = weak.clone();
        move || {
            if let Some(app) = weak.upgrade() {
                app.global::<App>().set_toast(msg_set.into());
            }
        }
    });
    // 2.4s sonra təmizlə
    let weak2 = weak.clone();
    std::thread::spawn(move || {
        std::thread::sleep(Duration::from_millis(2400));
        let _ = slint::invoke_from_event_loop(move || {
            if let Some(app) = weak2.upgrade() {
                let g = app.global::<App>();
                if &*g.get_toast() == msg.as_str() {
                    g.set_toast("".into());
                }
            }
        });
    });
}

fn banner(weak: &Weak<AppWindow>, msg: &str) {
    let msg = msg.to_string();
    let _ = slint::invoke_from_event_loop({
        let weak = weak.clone();
        move || {
            if let Some(app) = weak.upgrade() {
                app.global::<App>().set_banner(msg.into());
            }
        }
    });
}

// ---------------- Auth axınları ----------------

fn apply_auth(core: &Core, weak: &Weak<AppWindow>, auth: &AuthInfo) {
    {
        let mut p = l(&core.persisted);
        p.token = auth.token.clone();
        p.user_id = auth.user_id.clone();
        p.user_name = auth.user_name.clone();
        p.is_guest = auth.is_guest;
        store::save(&p);
    }
    let name = auth.user_name.clone();
    let guest = auth.is_guest;
    let _ = slint::invoke_from_event_loop({
        let weak = weak.clone();
        move || {
            if let Some(app) = weak.upgrade() {
                let g = app.global::<App>();
                g.set_user_name(name.into());
                g.set_is_guest(guest);
                g.set_signed_in(!guest);
                g.set_signin_open(false);
                g.set_signin_busy(false);
                g.set_signin_error("".into());
                g.set_email("".into());
                g.set_password("".into());
            }
        }
    });
}

fn spawn_load_models(core: Arc<Core>, weak: Weak<AppWindow>) {
    std::thread::spawn(move || {
        let (token, guest) = {
            let p = l(&core.persisted);
            (p.token.clone(), p.is_guest)
        };
        let models = core.client.models(&token, guest).unwrap_or_default();
        if models.is_empty() {
            return;
        }
        *l(&core.models) = models.clone();
        let idx = (*l(&core.persisted)).model_index.min(models.len() - 1);
        let m = &models[idx];
        let items: Vec<ModelItem> = models
            .iter()
            .map(|m| ModelItem {
                id: m.id.clone().into(),
                name: m.name.clone().into(),
                enabled: m.enabled,
            })
            .collect();
        let name = m.name.clone();
        let effort_labels = effort_labels_of(m);
        let _ = slint::invoke_from_event_loop({
            let weak = weak.clone();
            move || {
                if let Some(app) = weak.upgrade() {
                    let g = app.global::<App>();
                    let vm: Rc<slint::VecModel<ModelItem>> = Rc::new(slint::VecModel::default());
                    for it in items {
                        vm.push(it);
                    }
                    g.set_models(vm.into());
                    g.set_model_index(idx as i32);
                    g.set_effort(2.min(effort_labels.len().saturating_sub(1) as i32));
                    let _ = name;
                    apply_effort_labels(&g, &effort_labels);
                }
            }
        });
    });
}

fn effort_labels_of(m: &ModelInfo) -> Vec<String> {
    if m.effort_levels.is_empty() {
        vec!["low".into(), "medium".into(), "high".into()]
    } else {
        m.effort_levels.clone()
    }
}

fn apply_effort_labels(g: &App, levels: &[String]) {
    let cap = |s: &str| -> String {
        let mut c = s.chars();
        match c.next() {
            Some(f) => f.to_uppercase().collect::<String>() + c.as_str(),
            None => String::new(),
        }
    };
    let l0 = levels.first().map(|s| cap(s)).unwrap_or_default();
    let l1 = levels.get(1).map(|s| cap(s)).unwrap_or_default();
    let l2 = levels.get(2).map(|s| cap(s)).unwrap_or_default();
    g.set_effort_0(l0.into());
    g.set_effort_1(l1.into());
    g.set_effort_2(l2.into());
}

fn spawn_load_conversations(core: Arc<Core>, weak: Weak<AppWindow>) {
    let _ = slint::invoke_from_event_loop({
        let weak = weak.clone();
        move || {
            if let Some(app) = weak.upgrade() {
                app.global::<App>().set_history_loading(true);
            }
        }
    });
    std::thread::spawn(move || {
        let token = l(&core.persisted).token.clone();
        let is_guest = l(&core.persisted).is_guest;
        let convs: Vec<ConvSummary> = if is_guest {
            Vec::new()
        } else {
            core.client.conversations(&token).unwrap_or_default()
        };
        let active_id = l(&core.chat_id).clone();
        let items: Vec<ConvItem> = {
            let mut c = convs;
            c.sort_by(|a, b| b.updated_at.cmp(&a.updated_at));
            c.iter()
                .map(|x| ConvItem {
                    id: x.id.clone().into(),
                    title: if x.title.is_empty() { "New Chat".into() } else { x.title.clone().into() },
                    stamp: rel_time(x.updated_at).into(),
                    active: x.id == active_id,
                })
                .collect()
        };
        let _ = slint::invoke_from_event_loop({
            let weak = weak.clone();
            move || {
                if let Some(app) = weak.upgrade() {
                    let g = app.global::<App>();
                    let vm: Rc<slint::VecModel<ConvItem>> = Rc::new(slint::VecModel::default());
                    for it in items {
                        vm.push(it);
                    }
                    g.set_conversations(vm.into());
                    g.set_history_loading(false);
                }
            }
        });
    });
}

fn rel_time(ts: i64) -> String {
    if ts <= 0 {
        return String::new();
    }
    let now = api::now_ms() as i64 / 1000;
    let d = now - ts;
    if d < 60 {
        "just now".into()
    } else if d < 3600 {
        format!("{}m ago", d / 60)
    } else if d < 86400 {
        format!("{}h ago", d / 3600)
    } else if d < 86400 * 7 {
        format!("{}d ago", d / 86400)
    } else {
        chrono::DateTime::from_timestamp(ts, 0)
            .map(|t| t.format("%b %d").to_string())
            .unwrap_or_default()
    }
}

// ---------------- Boot ----------------

fn spawn_boot(core: Arc<Core>, weak: Weak<AppWindow>) {
    std::thread::spawn(move || {
        // WAF cookie-lərini al (brauzerin açılışına bənzər)
        core.client.warm_up_home();

        let saved = l(&core.persisted).clone();
        let mut auth: Option<AuthInfo> = None;
        let mut token_invalid = false;

        if !saved.token.is_empty() {
            match core.client.verify_token(&saved.token) {
                Ok(a) => auth = Some(a),
                Err(_) => {
                    token_invalid = true;
                }
            }
        }
        if auth.is_none() {
            match core.client.guest_auth() {
                Ok(a) => auth = Some(a),
                Err(e) => {
                    let msg = format!("Network error: {}", e);
                    let _ = slint::invoke_from_event_loop({
                        let weak = weak.clone();
                        move || {
                            if let Some(app) = weak.upgrade() {
                                app.global::<App>().set_banner(msg.into());
                            }
                        }
                    });
                    // yenidən cəhd 10 saniyədən sonra
                    std::thread::sleep(Duration::from_secs(10));
                    match core.client.guest_auth() {
                        Ok(a) => auth = Some(a),
                        Err(_) => {
                            let _ = slint::invoke_from_event_loop({
                                let weak = weak.clone();
                                move || {
                                    if let Some(app) = weak.upgrade() {
                                        app.global::<App>().set_booting(false);
                                    }
                                }
                            });
                            return;
                        }
                    }
                }
            }
        }
        if let Some(a) = &auth {
            apply_auth(&core, &weak, a);
        }
        *l(&core.boot_done) = true;
        spawn_load_models(core.clone(), weak.clone());
        let guest = l(&core.persisted).is_guest;
        if !guest {
            spawn_load_conversations(core.clone(), weak.clone());
        }
        if token_invalid {
            toast(&weak, "Session expired — signed in as guest");
        }
        let _ = slint::invoke_from_event_loop({
            let weak = weak.clone();
            move || {
                if let Some(app) = weak.upgrade() {
                    app.global::<App>().set_booting(false);
                }
            }
        });
    });
}

// ---------------- Göndərmə axını ----------------

fn now_hm() -> String {
    chrono::Local::now().format("%H:%M").to_string()
}

fn current_model(core: &Core) -> (String, String, usize) {
    let models = l(&core.models).clone();
    let idx = (*l(&core.persisted)).model_index.min(models.len().saturating_sub(1));
    if let Some(m) = models.get(idx) {
        (m.id.clone(), m.name.clone(), idx)
    } else {
        ("x-preview-l".into(), "GLM-5.3-Flash".into(), 0)
    }
}

fn effort_string(core: &Core) -> String {
    let models = l(&core.models).clone();
    let p = l(&core.persisted);
    let idx = p.model_index.min(models.len().saturating_sub(1));
    let eff_idx = p.effort_index;
    drop(p);
    if let Some(m) = models.get(idx) {
        let levels = effort_labels_of(m);
        if let Some(l) = levels.get(eff_idx.min(levels.len().saturating_sub(1))) {
            return l.clone();
        }
    }
    "high".into()
}

fn collect_history(msgs: &[RawMsg]) -> Vec<(String, String)> {
    msgs.iter()
        .filter(|m| !m.error && !m.text.is_empty() && m.role != "streaming")
        .map(|m| (m.role.clone(), m.text.clone()))
        .collect()
}

fn spawn_send(core: Arc<Core>, weak: Weak<AppWindow>, prompt: String) {
    // İşı göstər
    {
        let um = RawMsg {
            id: api::gen_uuid(),
            role: "user".into(),
            text: prompt.clone(),
            streaming: false,
            error: false,
            stamp: now_hm(),
        };
        let am = RawMsg {
            id: api::gen_uuid(),
            role: "assistant".into(),
            text: String::new(),
            streaming: true,
            error: false,
            stamp: now_hm(),
        };
        l(&core.messages).push(um);
        l(&core.messages).push(am);
        push_all_messages(&core, &weak);
        set_streaming(&core, &weak, true);
        set_phase(&weak, "");
    }

    let job = Job::new();
    *l(&core.current_job) = Some(job.clone());

    std::thread::spawn(move || {
        let result = run_send(&core, &weak, &prompt, &job);
        match result {
            Ok(()) => {}
            Err(e) if e == "CAPTCHA" => {
                finish_stream(&core, &weak, true,
                    "Server requires captcha verification for this network.\nSign in with your account (Sign in) or paste your account token — then try again.");
                banner(&weak, "Captcha required — sign in with your account to continue");
            }
            Err(e) if e == "UNAUTHORIZED" => {
                // qonaq tokeni vaxtı bitib — yenilə və bir dəfə təkrar cəhd et
                if l(&core.persisted).is_guest {
                    if let Ok(a) = core.client.guest_auth() {
                        apply_auth(&core, &weak, &a);
                        // sonuncu boş assistant mesajını sıfırla
                        {
                            let mut msgs = l(&core.messages);
                            if let Some(last) = msgs.last_mut() {
                                last.text.clear();
                                last.streaming = true;
                                last.error = false;
                            }
                        }
                        set_streaming(&core, &weak, true);
                        let _ = run_send(&core, &weak, &prompt, &job);
                        return;
                    }
                } else {
                    // istifadəçi tokeni etibarsız — qonağa düş
                    if let Ok(a) = core.client.guest_auth() {
                        apply_auth(&core, &weak, &a);
                        toast(&weak, "Session expired — signed in as guest");
                    }
                }
                finish_stream(&core, &weak, true, "Session expired. Please sign in again.");
            }
            Err(e) => {
                finish_stream(&core, &weak, true, &e);
            }
        }
    });
}

fn run_send(core: &Arc<Core>, weak: &Weak<AppWindow>, prompt: &str, job: &Arc<Job>) -> Result<(), String> {
    // Axın zamanı gələn user/assistant mesajları (yeni göndəriş üçün)
    let history: Vec<(String, String)> = {
        let msgs = l(&core.messages);
        // sonuncu assistant (boş placeholder) və sonuncu user mesajını çıxart
        let mut h = collect_history(&msgs);
        if h.len() >= 2 {
            h.truncate(h.len() - 2);
        } else {
            h.clear();
        }
        h
    };

    let (token, user_id) = {
        let p = l(&core.persisted);
        (p.token.clone(), p.user_id.clone())
    };
    let (model_id, _model_name, _) = current_model(core);
    let effort = effort_string(core);

    let user_msg_id = api::gen_uuid();
    let assistant_msg_id = {
        let msgs = l(&core.messages);
        msgs.last().map(|m| m.id.clone()).unwrap_or_else(api::gen_uuid)
    };
    let mut chat_id = l(&core.chat_id).clone();
    let chat_title = l(&core.chat_title).clone();
    let is_first = chat_id.is_empty();

    // Axın zamanı fasiləsiz yeniləmə üçün throttle
    let mut last_push = Instant::now() - Duration::from_secs(1);
    let mut phase_now = String::new();

    let core2 = core.clone();
    let weak2 = weak.clone();
    let mut emit = |ev: StreamEvent| {
        match ev {
            StreamEvent::Delta(d) => {
                {
                    let mut msgs = l(&core2.messages);
                    if let Some(last) = msgs.last_mut() {
                        last.text.push_str(&d);
                    }
                }
                if last_push.elapsed() >= Duration::from_millis(90) {
                    last_push = Instant::now();
                    update_last_message(&core2, &weak2);
                }
            }
            StreamEvent::Phase(p) => {
                if p != phase_now {
                    phase_now = p.clone();
                    set_phase(&weak2, &p);
                }
            }
            StreamEvent::ChatCreated(id) => {
                *l(&core2.chat_id) = id;
            }
            StreamEvent::Done | StreamEvent::Error(_) | StreamEvent::CaptchaRequired | StreamEvent::Unauthorized => {}
        }
    };

    let result = stream::run_completion(
        &core.client,
        &token,
        &user_id,
        &mut chat_id,
        &chat_title,
        &history,
        prompt,
        &model_id,
        &effort,
        &user_msg_id,
        &assistant_msg_id,
        is_first,
        job,
        &mut emit,
    );

    match result {
        Ok(()) => {
            finish_stream(core, weak, false, "");
            // başlıq yaradılıbsa siyahını yenilə
            if is_first && !l(&core.persisted).is_guest {
                spawn_load_conversations(core.clone(), weak.clone());
            }
            Ok(())
        }
        Err(e) => Err(e),
    }
}

/// Axını bitir: son mesajı final vəziyyətə sal
fn finish_stream(core: &Core, weak: &Weak<AppWindow>, is_error: bool, error_text: &str) {
    {
        let mut msgs = l(&core.messages);
        if let Some(last) = msgs.last_mut() {
            if is_error {
                last.error = true;
                last.text = error_text.to_string();
            }
            last.streaming = false;
        }
    }
    set_phase(weak, "");
    set_streaming(core, weak, false);
    *l(&core.current_job) = None;
    push_all_messages(core, weak);
}

// ---------------- Söhbət açma ----------------

fn content_to_text(c: &Value) -> String {
    match c {
        Value::String(s) => s.clone(),
        Value::Array(arr) => arr
            .iter()
            .filter_map(|p| {
                let t = p["type"].as_str().unwrap_or("");
                match t {
                    "text" => p["text"].as_str().map(String::from),
                    "image_url" | "video_url" | "file_url" => Some("[attachment]".into()),
                    _ => None,
                }
            })
            .collect::<Vec<_>>()
            .join("\n"),
        _ => String::new(),
    }
}

fn spawn_open_conversation(core: Arc<Core>, weak: Weak<AppWindow>, id: String) {
    toast(&weak, "Loading chat…");
    std::thread::spawn(move || {
        let token = l(&core.persisted).token.clone();
        match core.client.chat_detail(&token, &id) {
            Ok(v) => {
                let title = v["title"].as_str().unwrap_or("Chat").to_string();
                let messages = v["history"]["messages"].clone();
                let current = v["history"]["currentId"].as_str().unwrap_or("").to_string();
                // parent zəncirini yığ
                let mut chain: Vec<Value> = Vec::new();
                let mut cur = current;
                for _ in 0..500 {
                    if cur.is_empty() {
                        break;
                    }
                    let m = &messages[&cur];
                    if m.is_null() {
                        break;
                    }
                    chain.push(m.clone());
                    cur = m["parentId"].as_str().unwrap_or("").to_string();
                }
                chain.reverse();
                let mut raws: Vec<RawMsg> = Vec::new();
                for m in &chain {
                    let role = m["role"].as_str().unwrap_or("user").to_string();
                    if role != "user" && role != "assistant" {
                        continue;
                    }
                    let text = content_to_text(&m["content"]);
                    if text.is_empty() {
                        continue;
                    }
                    let ts = m["timestamp"].as_i64().unwrap_or(0);
                    let stamp = chrono::DateTime::from_timestamp(ts, 0)
                        .map(|t| t.with_timezone(&chrono::Local).format("%H:%M").to_string())
                        .unwrap_or_default();
                    raws.push(RawMsg {
                        id: m["id"].as_str().unwrap_or("").to_string(),
                        role,
                        text,
                        streaming: false,
                        error: false,
                        stamp,
                    });
                }
                *l(&core.chat_id) = id;
                *l(&core.chat_title) = title.clone();
                *l(&core.messages) = raws;
                let _ = slint::invoke_from_event_loop({
                    let weak = weak.clone();
                    let title2 = title;
                    move || {
                        if let Some(app) = weak.upgrade() {
                            let g = app.global::<App>();
                            g.set_chat_title(title2.into());
                            g.set_sidebar_open(false);
                            g.set_auto_scroll(true);
                        }
                    }
                });
                push_all_messages(&core, &weak);
                spawn_load_conversations(core, weak);
            }
            Err(e) => {
                toast(&weak, &format!("Could not load: {}", e));
            }
        }
    });
}

// ---------------- Kök init ----------------

pub fn run() {
    let app = AppWindow::new().expect("UI yaradıla bilmədi");
    let core = Core::new();
    let weak = app.as_weak();

    // ---- köməkçi closure-lar ----
    {
        let g = app.global::<App>();

        // send
        let core_c = core.clone();
        let weak_c = weak.clone();
        g.on_send(move |text| {
            let t = text.trim().to_string();
            if t.is_empty() {
                return;
            }
            let app = weak_c.upgrade().unwrap();
            let g = app.global::<App>();
            g.set_input_text("".into());
            g.set_auto_scroll(true);
            spawn_send(core_c.clone(), weak_c.clone(), t);
        });

        // stop
        let core_c = core.clone();
        g.on_stop_generation(move || {
            if let Some(job) = &*l(&core_c.current_job) {
                job.stop.store(true, std::sync::atomic::Ordering::Relaxed);
            }
        });

        // regenerate
        let core_c = core.clone();
        let weak_c = weak.clone();
        g.on_regenerate(move || {
            // son assistant mesajını sil, son user mətnini yenidən göndər
            let prompt = {
                let mut msgs = l(&core_c.messages);
                while let Some(last) = msgs.last() {
                    if last.role == "assistant" {
                        msgs.pop();
                    } else {
                        break;
                    }
                }
                let p = msgs.last().filter(|m| m.role == "user").map(|m| m.text.clone());
                drop(msgs);
                p
            };
            if let Some(p) = prompt {
                push_all_messages(&core_c, &weak_c);
                spawn_send(core_c.clone(), weak_c.clone(), p);
            }
        });

        // new chat
        let core_c = core.clone();
        let weak_c = weak.clone();
        g.on_new_chat(move || {
            *l(&core_c.chat_id) = String::new();
            *l(&core_c.chat_title) = "New Chat".into();
            *l(&core_c.messages) = Vec::new();
            let app = weak_c.upgrade().unwrap();
            let g = app.global::<App>();
            g.set_chat_title("New Chat".into());
            g.set_sidebar_open(false);
            g.set_auto_scroll(true);
            g.set_input_text("".into());
            let empty: Rc<slint::VecModel<ChatMessage>> = Rc::new(slint::VecModel::default());
            g.set_messages(empty.into());
        });

        // open conversation
        let core_c = core.clone();
        let weak_c = weak.clone();
        g.on_open_conversation(move |id| {
            spawn_open_conversation(core_c.clone(), weak_c.clone(), id.to_string());
        });

        // delete conversation
        let core_c = core.clone();
        let weak_c = weak.clone();
        g.on_delete_conversation(move |id| {
            let core_cc = core_c.clone();
            let weak_cc = weak_c.clone();
            let id_s = id.to_string();
            std::thread::spawn(move || {
                let token = l(&core_cc.persisted).token.clone();
                let _ = core_cc.client.chat_delete(&token, &id_s);
                spawn_load_conversations(core_cc, weak_cc);
            });
        });

        // refresh
        let core_c = core.clone();
        let weak_c = weak.clone();
        g.on_refresh_conversations(move || {
            spawn_load_conversations(core_c.clone(), weak_c.clone());
        });

        // sign in (email)
        let core_c = core.clone();
        let weak_c = weak.clone();
        g.on_do_sign_in(move |email, password| {
            let app = weak_c.upgrade().unwrap();
            let g = app.global::<App>();
            g.set_signin_busy(true);
            g.set_signin_error("".into());
            let email_s = email.to_string();
            let pass_s = password.to_string();
            let core_cc = core_c.clone();
            let weak_cc = weak_c.clone();
            std::thread::spawn(move || {
                match core_cc.client.signin(&email_s, &pass_s) {
                    Ok(a) => {
                        apply_auth(&core_cc, &weak_cc, &a);
                        toast(&weak_cc, &format!("Welcome, {}", a.user_name));
                        *l(&core_cc.chat_id) = String::new();
                        spawn_load_models(core_cc.clone(), weak_cc.clone());
                        spawn_load_conversations(core_cc, weak_cc);
                    }
                    Err(e) if e == "CAPTCHA_REQUIRED" => {
                        let app = weak_cc.upgrade().unwrap();
            let g = app.global::<App>();
                        g.set_signin_busy(false);
                        g.set_signin_error(
                            "This network requires captcha. Open chat.z.ai in your phone browser, sign in there, then paste your token (second tab).".into(),
                        );
                    }
                    Err(e) => {
                        let app = weak_cc.upgrade().unwrap();
            let g = app.global::<App>();
                        g.set_signin_busy(false);
                        g.set_signin_error(e.into());
                    }
                }
            });
        });

        // sign in (token)
        let core_c = core.clone();
        let weak_c = weak.clone();
        g.on_do_token_sign_in(move |token| {
            let app = weak_c.upgrade().unwrap();
            let g = app.global::<App>();
            g.set_signin_busy(true);
            g.set_signin_error("".into());
            let token_s = token.to_string();
            let core_cc = core_c.clone();
            let weak_cc = weak_c.clone();
            std::thread::spawn(move || match core_cc.client.verify_token(&token_s) {
                Ok(a) => {
                    apply_auth(&core_cc, &weak_cc, &a);
                    toast(&weak_cc, &format!("Welcome, {}", a.user_name));
                    *l(&core_cc.chat_id) = String::new();
                    spawn_load_models(core_cc.clone(), weak_cc.clone());
                    spawn_load_conversations(core_cc, weak_cc);
                }
                Err(_) => {
                    let app = weak_cc.upgrade().unwrap();
            let g = app.global::<App>();
                    g.set_signin_busy(false);
                    g.set_signin_error("Token is invalid or expired.".into());
                }
            });
        });

        // guest
        let core_c = core.clone();
        let weak_c = weak.clone();
        g.on_continue_as_guest(move || {
            let app = weak_c.upgrade().unwrap();
            let g = app.global::<App>();
            g.set_signin_busy(true);
            let core_cc = core_c.clone();
            let weak_cc = weak_c.clone();
            std::thread::spawn(move || match core_cc.client.guest_auth() {
                Ok(a) => {
                    apply_auth(&core_cc, &weak_cc, &a);
                    toast(&weak_cc, "Continuing as guest");
                    spawn_load_models(core_cc, weak_cc);
                }
                Err(e) => {
                    let app = weak_cc.upgrade().unwrap();
            let g = app.global::<App>();
                    g.set_signin_busy(false);
                    g.set_signin_error(e.into());
                }
            });
        });

        // sign out
        let core_c = core.clone();
        let weak_c = weak.clone();
        g.on_sign_out(move || {
            let core_cc = core_c.clone();
            let weak_cc = weak_c.clone();
            std::thread::spawn(move || {
                {
                    let mut p = l(&core_cc.persisted);
                    p.token = String::new();
                    p.user_id = String::new();
                    p.user_name = "Guest".into();
                    p.is_guest = true;
                    store::save(&p);
                }
                if let Ok(a) = core_cc.client.guest_auth() {
                    apply_auth(&core_cc, &weak_cc, &a);
                }
                *l(&core_cc.chat_id) = String::new();
                *l(&core_cc.chat_title) = "New Chat".into();
                *l(&core_cc.messages) = Vec::new();
                let _ = slint::invoke_from_event_loop({
                    let weak = weak_cc.clone();
                    move || {
                        if let Some(app) = weak.upgrade() {
                            let g = app.global::<App>();
                            g.set_chat_title("New Chat".into());
                            g.set_sidebar_open(false);
                            let empty: Rc<slint::VecModel<ChatMessage>> = Rc::new(slint::VecModel::default());
                            g.set_messages(empty.into());
                            let econv: Rc<slint::VecModel<ConvItem>> = Rc::new(slint::VecModel::default());
                            g.set_conversations(econv.into());
                        }
                    }
                });
                spawn_load_models(core_cc.clone(), weak_cc.clone());
                toast(&weak_cc, "Signed out");
            });
        });

        // model seçimi
        let core_c = core.clone();
        let weak_c = weak.clone();
        g.on_select_model(move |idx| {
            let models = l(&core_c.models).clone();
            let idx_us = idx.max(0) as usize;
            if let Some(m) = models.get(idx_us) {
                if !m.enabled {
                    return;
                }
                {
                    let mut p = l(&core_c.persisted);
                    p.model_index = idx_us;
                    store::save(&p);
                }
                let labels = effort_labels_of(m);
                let name = m.name.clone();
                let app = weak_c.upgrade().unwrap();
                let g = app.global::<App>();
                g.set_model_index(idx);
                g.set_effort(2.min(labels.len().saturating_sub(1) as i32));
                apply_effort_labels(&g, &labels);
                g.set_model_popup_open(false);
                let _ = name;
            }
        });

        // effort
        let core_c = core.clone();
        let weak_e = weak.clone();
        g.on_set_effort(move |e| {
            let mut p = l(&core_c.persisted);
            p.effort_index = e.max(0) as usize;
            store::save(&p);
            let app = weak_e.upgrade().unwrap();
            app.global::<App>().set_effort(e);
        });

        // clipboard
        g.on_copy_to_clipboard(|t| {
            crate::clipboard::copy_text(&t);
        });
    }

    // ---- dot animasiyası (axın zamanı) ----
    {
        let weak_dot = weak.clone();
        std::thread::spawn(move || loop {
            std::thread::sleep(Duration::from_millis(350));
            let _ = slint::invoke_from_event_loop({
                let weak = weak_dot.clone();
                move || {
                    if let Some(app) = weak.upgrade() {
                        let g = app.global::<App>();
                        if g.get_streaming() {
                            g.set_dot_tick(g.get_dot_tick() + 1);
                        }
                    }
                }
            });
        });
    }

    // ---- boot ----
    spawn_boot(core.clone(), weak.clone());

    // ---- TEST hook (ZAI_TEST_SEND env) ----
    if let Ok(msg) = std::env::var("ZAI_TEST_SEND") {
        let core_c = core.clone();
        let weak_c = weak.clone();
        std::thread::spawn(move || {
            // boot bitənə qədər gözlə
            for _ in 0..60 {
                if *l(&core_c.boot_done) {
                    break;
                }
                std::thread::sleep(Duration::from_millis(500));
            }
            std::thread::sleep(Duration::from_millis(800));
            let _ = slint::invoke_from_event_loop({
                let weak = weak_c.clone();
                let msg = msg.clone();
                move || {
                    if let Some(app) = weak.upgrade() {
                        let g = app.global::<App>();
                        g.invoke_send(msg.into());
                    }
                }
            });
        });
    }

    app.run().expect("Hadisə dövrəsi xətası");
}
