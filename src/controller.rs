//! Controller: Slint callback-lərinin qoşulması, arxa plan işləri (worker),
//! state sinxronizasiyası, giriş axınları, əlavələr və avtomatik retry.

use crate::api::{self, AuthInfo, Client, ConvSummary, ModelInfo, UploadedFile};
use crate::markdown;
use crate::picker::{self, MediaFile};
use crate::store::{self, Persisted};
use crate::stream::{self, Job, StreamEvent};
use serde_json::Value;
use slint::{ComponentHandle, Weak};
use slint::Model as _;
use std::cell::RefCell;
use std::rc::Rc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::{Duration, Instant};

use crate::{App, AppWindow, Attachment, ChatMessage, ConvItem, MessageBlock, ModelItem, PickedFile};

// ---------------- Daxili state ----------------

#[derive(Clone, Debug)]
pub struct RawMsg {
    pub id: String,
    pub role: String,
    pub text: String,
    pub files: Vec<UploadedFile>,
    pub streaming: bool,
    pub error: bool,
    pub retrying: bool,
    pub stamp: String,
}

/// Composer-dəki əlavə: upload vəziyyəti + nəticə VAHİD strukturdadır.
/// (Əvvəl ayrı-ayrı `uploads` + `upload_state` siyahıları var idi — UI indeksi
/// `uploads`-a, status isə `upload_state`-ə görə düzülürdü və fərqli
/// quruluşlarına görə silmə yanlış elementi silirdi; üstəlik pending
/// upload-lar UI-da heç göstərilmirdi, removal isə UI modelini yeniləmirdi.)
#[derive(Clone, Debug)]
pub struct UploadEntry {
    pub local_id: u64,               // unikal id (eyni adlı fayllar üçün də)
    pub name: String,
    pub media: String,               // image|video|doc
    pub size: i64,
    pub status: i32,                 // 0=yüklənir 1=hazır 2=xəta
    pub file: Option<UploadedFile>,  // uğurlu upload nəticəsi
}

/// Yerli unikal id generatoru (composer çipləri üçün)
static UPLOAD_ID: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(1);
fn next_upload_id() -> u64 {
    UPLOAD_ID.fetch_add(1, std::sync::atomic::Ordering::Relaxed)
}

pub struct Core {
    pub client: Client,
    pub persisted: Mutex<Persisted>,
    pub chat_id: Mutex<String>,
    pub chat_title: Mutex<String>,
    /// Arc: push_all_messages siyahını kopyalayanda mətnlər deyil,
    /// yalnız göstəricilər çoxalır (uzun söhbətdə MB-larla clone yox).
    pub messages: Mutex<Vec<Arc<RawMsg>>>,
    pub current_job: Mutex<Option<Arc<Job>>>,
    pub models: Mutex<Vec<ModelInfo>>,
    pub boot_done: Mutex<bool>,
    /// Ucuz oxunan axın bayrağı (animasiya thread-i event loop-u boş-boşuna
    /// oyatmasın deyə)
    pub streaming_flag: Arc<AtomicBool>,
    /// composer-dəki əlavələr (vəziyyət + upload nəticəsi birlikdə)
    pub uploads: Mutex<Vec<UploadEntry>>,
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
            streaming_flag: Arc::new(AtomicBool::new(false)),
            uploads: Mutex::new(Vec::new()),
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

/// Bu job hələ də AKTİV (current) job-durmu? Söhbət dəyişəndə köhnə
/// axının gecikən hadisələri yeni söhbətin mesajlarına yazmamalıdır —
/// emit/finish/retry yolları bunu yoxlayır.
fn is_current_job(core: &Core, job: &Arc<Job>) -> bool {
    l(&core.current_job)
        .as_ref()
        .map(|j| Arc::ptr_eq(j, job))
        .unwrap_or(false)
}

/// Aktiv axını dayandır və current_job-i təmizlə (söhbət dəyişəndə,
/// yeni chat, sign-out, yeni göndəriş). İşçi thread stop bayrağını
/// gec də olsa görəcək; ona qədər gələn hadisələr is_current_job
/// yoxlaması ilə udulur.
fn stop_current_job(core: &Core) {
    if let Some(j) = l(&core.current_job).take() {
        j.stop.store(true, Ordering::Relaxed);
    }
}

/// Açılış/axın vaxt xəttini logla (adb logcat ilə ölçmək üçün).
/// Yalnız ZAI_TIMING=1 aktivləşir — adi istifadədə susur.
pub(crate) fn timing_enabled() -> bool {
    static T: std::sync::OnceLock<bool> = std::sync::OnceLock::new();
    *T.get_or_init(|| std::env::var("ZAI_TIMING").ok().as_deref() == Some("1"))
}

// ---------------- UI köməkçiləri ----------------

fn blocks_model(text: &str) -> slint::ModelRc<MessageBlock> {
    let blocks = markdown::parse(text);
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

fn files_model(files: &[UploadedFile]) -> slint::ModelRc<Attachment> {
    let vm: Rc<slint::VecModel<Attachment>> = Rc::new(slint::VecModel::default());
    for f in files {
        vm.push(Attachment {
            id: f.id.clone().into(),
            name: f.name.clone().into(),
            media: f.media.clone().into(),
            status: 1,
            size: api::human_size(f.size).into(),
        });
    }
    vm.into()
}

/// `eager=true`: bloklar dərhal parse olunur (yalnız tək sətir yenilənəndə —
/// axın bitişində; init callback-i yenidən atəşlənmədiyi üçün lazımdır).
/// `eager=false`: BOŞ, amma həqiqi VecModel (axın flush-u downcast edə bilsin
/// deyə) — ListView sətri görünəndə `ensure-blocks-parsed` ilə tək-tək parse
/// olunur. Uzun söhbət açılışında bütün tarixçə üçün markdown parse
/// YOXDUR — yalnız görünən sətirlər.
fn raw_to_ui(m: &RawMsg, eager: bool) -> ChatMessage {
    let blocks: slint::ModelRc<MessageBlock> = if m.error || m.role == "user" {
        slint::ModelRc::default()
    } else if eager {
        blocks_model(&m.text)
    } else {
        Rc::new(slint::VecModel::<MessageBlock>::default()).into()
    };
    ChatMessage {
        id: m.id.clone().into(),
        role: m.role.clone().into(),
        text: m.text.clone().into(),
        blocks,
        files: if m.role == "user" { files_model(&m.files) } else { slint::ModelRc::default() },
        streaming: m.streaming,
        error: m.error,
        retrying: m.retrying,
        stamp: m.stamp.clone().into(),
        // user/xəta sətirləri heç vaxt parse olunmur → hazır sayılır;
        // assistant: eager → hazır, lazy → sətir görünəndə parse olunacaq
        parsed: m.error || m.role == "user" || eager,
    }
}

/// Mesajların tam siyahısını UI-a köçür (yalnız açılış/göndərmə/bitirmə kimi
/// nadir hallarda çağırılır — axın zamanı YOX). Arc sayəsində mətnlər
/// kopyalanmur, yalnız göstəricilər.
fn push_all_messages(core: &Core, weak: &Weak<AppWindow>) {
    let raws = l(&core.messages).clone();
    let _ = slint::invoke_from_event_loop({
        let weak = weak.clone();
        move || {
            if let Some(app) = weak.upgrade() {
                let g = app.global::<App>();
                let vm: Rc<slint::VecModel<ChatMessage>> = Rc::new(slint::VecModel::default());
                for r in &raws {
                    vm.push(raw_to_ui(r, false));
                }
                g.set_messages(vm.into());
                // axın keşi köhnə modelə işarə edir — ləğv et
                clear_stream_cache();
            }
        }
    });
}

/// UI thread-dəki axın-blok keşini ləğv et (model əvəzlənəndə)
fn clear_stream_cache() {
    STREAM_BLOCKS.with(|c| *c.borrow_mut() = None);
}

thread_local! {
    /// (mesaj id, həmin mesajın blok VecModel-i) — yalnız UI thread-də.
    /// Axın zamanı ChatMessage əvəzlənmir; yalnız bu modelin son sıraları yenilənir.
    static STREAM_BLOCKS: RefCell<Option<(String, slint::ModelRc<MessageBlock>)>> =
        const { RefCell::new(None) };
}

/// Yalnız son mesajı yenilə (axın bitişində / xəta / retry vəziyyətində)
fn update_last_message(core: &Core, weak: &Weak<AppWindow>) {
    let last = {
        let raws = l(&core.messages);
        match raws.last() {
            Some(m) => m.clone(),
            None => return,
        }
    };
    let count = l(&core.messages).len();
    let _ = slint::invoke_from_event_loop({
        let weak = weak.clone();
        move || {
            if let Some(app) = weak.upgrade() {
                let g = app.global::<App>();
                let model = g.get_messages();
                if let Some(vm) = model.as_any().downcast_ref::<slint::VecModel<ChatMessage>>() {
                    if vm.row_count() == count {
                        vm.set_row_data(count - 1, raw_to_ui(&last, true));
                        clear_stream_cache();
                    }
                }
            }
        }
    });
}

/// AXIN FLUSHI (performans əsası): worker tərəfdə artımlı parse olunmuş
/// blokları UI-a tətbiq et. ChatMessage əvəzlənmir, bütün list yenidən
/// qurulmur — yalnız son mesajın blok modelinin `base`-dən sonrakı sıraları.
fn flush_stream_blocks(weak: &Weak<AppWindow>, msg_id: String, base: usize, blocks: Vec<markdown::Block>) {
    let _ = slint::invoke_from_event_loop({
        let weak = weak.clone();
        move || {
            let Some(app) = weak.upgrade() else { return };
            let g = app.global::<App>();
            let messages = g.get_messages();
            let Some(vm) = messages.as_any().downcast_ref::<slint::VecModel<ChatMessage>>() else { return };
            let rows = vm.row_count();
            if rows == 0 {
                return;
            }
            // YARIŞ QORUMASI: flush event loop-a yazıldıqdan sonra söhbət
            // dəyişə bilər (keş təmizlənər, son sıra ARTIQ BAŞQA mesajdır).
            // Son sıranın id-si axın mesajının id-si deyilsə — heç nə yazma.
            let last_id = vm.row_data(rows - 1).map(|m| m.id.to_string());
            if last_id.as_deref() != Some(msg_id.as_str()) {
                return;
            }
            // keş: eyni mesaj üçün mövcud blok modeli
            let blocks_model: slint::ModelRc<MessageBlock> = STREAM_BLOCKS.with(|c| {
                let mut c = c.borrow_mut();
                let hit = c.as_ref().map(|(id, _)| id == &msg_id).unwrap_or(false);
                if hit {
                    let (_, m) = c.as_ref().unwrap();
                    m.clone()
                } else {
                    // son sıranın hazır (boş) modelini götür və ya yeni yarat
                    let m = vm
                        .row_data(rows - 1)
                        .map(|msg| msg.blocks)
                        .unwrap_or_default();
                    *c = Some((msg_id, m.clone()));
                    m
                }
            });
            let Some(bm) = blocks_model.as_any().downcast_ref::<slint::VecModel<MessageBlock>>() else { return };
            let n = blocks.len();
            // artıq sıraları sondan sil (mətn Reset/kiçilmə halında)
            let rc = bm.row_count();
            for i in (base + n..rc).rev() {
                bm.remove(i);
            }
            // base-dən etibarən tətbiq et: mövcudları yerində yenilə, yenilərini əlavə et
            for (i, b) in blocks.into_iter().enumerate() {
                let row = MessageBlock { kind: b.kind, text: b.text.into(), lang: b.lang.into() };
                let idx = base + i;
                if idx < bm.row_count() {
                    bm.set_row_data(idx, row);
                } else {
                    bm.push(row);
                }
            }
        }
    });
}

fn set_streaming(core: &Core, weak: &Weak<AppWindow>, on: bool) {
    core.streaming_flag.store(on, Ordering::Relaxed);
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
                g.set_authed(true);
                g.set_login_busy(false);
                g.set_login_error("".into());
                g.set_email("".into());
                g.set_password("".into());
                g.set_token_input("".into());
                g.set_login_step(0);
            }
        }
    });
}

/// Uğurlu girişdən sonra qonaq sessiyasının izlərini təmizlə: köhnə qonaq
/// mesajları yeni hesabın yeni söhbətində QALMAMALI və kontekst kimi yeni
/// chat-ə GETMƏMƏLİDİR (əvvəl yalnız chat_id sıfırlanırdı — mesajlar
/// ekranda qalırdı və tarixçəyə düşürdü).
fn reset_conversation(core: &Core, weak: &Weak<AppWindow>) {
    stop_current_job(core);
    *l(&core.chat_id) = String::new();
    *l(&core.chat_title) = "New Chat".into();
    *l(&core.messages) = Vec::new();
    let _ = slint::invoke_from_event_loop({
        let weak = weak.clone();
        move || {
            if let Some(app) = weak.upgrade() {
                let g = app.global::<App>();
                g.set_chat_title("New Chat".into());
                g.set_messages(Rc::new(slint::VecModel::<ChatMessage>::default()).into());
                g.set_input_text("".into());
                g.set_auto_scroll(true);
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
        let idx = l(&core.persisted).model_index.min(models.len() - 1);
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
            c.sort_by_key(|x| std::cmp::Reverse(x.updated_at));
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

/// Açılış: İLK İNTERAKTİV KADR şəbəkəni GÖZLƏMİR.
/// 1) Saxlanmış sessiya (varsa) dərhal tətbiq olunur → chat açılır.
/// 2) Token etibarlılığı FON-DA yoxlanılır; etibarsızdırsa qonaq fallback
///    (əvvəlki davranış) + "Session expired" toast.
/// 3) WAF warm-up ayrı thread-də (kritik yol deyil).
/// 4) Modellər və tarixçə fon-da yüklənir.
fn spawn_boot(core: Arc<Core>, weak: Weak<AppWindow>) {
    std::thread::spawn(move || {
        let t0 = Instant::now();
        let saved = l(&core.persisted).clone();

        // WAF cookie-ləri fon-da — açılış yolunda deyil
        {
            let c = core.clone();
            std::thread::spawn(move || c.client.warm_up_home());
        }

        // Optimistiq sessiya: saxlanmış token dərhal UI-a tətbiq olunur.
        if !saved.token.is_empty() {
            let a = AuthInfo {
                token: saved.token.clone(),
                user_id: saved.user_id.clone(),
                user_name: {
                    let n = saved.user_name.clone();
                    if n.is_empty() { "Guest".into() } else { n }
                },
                is_guest: saved.is_guest,
            };
            apply_auth(&core, &weak, &a);

            // İlk ekran DƏRHAL (qayıdan istifadəçi — şəbəkə gözləmirdən asılı deyil)
            let _ = slint::invoke_from_event_loop({
                let weak = weak.clone();
                move || {
                    if let Some(app) = weak.upgrade() {
                        app.global::<App>().set_booting(false);
                    }
                }
            });
            *l(&core.boot_done) = true;
            if timing_enabled() {
                eprintln!("[timing] ilk ekran hazır: {:?} (optimistiq sessiya)", t0.elapsed());
            }

            // fon-da yoxla
            match core.client.verify_token(&saved.token) {
                Ok(a) => {
                    apply_auth(&core, &weak, &a);
                }
                Err(_) => {
                    match core.client.guest_auth() {
                        Ok(a) => {
                            apply_auth(&core, &weak, &a);
                            if !saved.is_guest {
                                toast(&weak, "Session expired — please sign in");
                            }
                        }
                        Err(_) => {
                            banner(&weak, "Network error — working offline.");
                        }
                    }
                }
            }
            spawn_load_models(core.clone(), weak.clone());
            if !l(&core.persisted).is_guest {
                spawn_load_conversations(core, weak);
            }
            return;
        }

        // Sessiya yoxdur (ilk açılış): köhnə axın — splash qonaq tokeni gözləyir,
        // sonra birbaşa chat (veb saytın özü də belə davranır)
        let mut authed = false;
        match core.client.guest_auth() {
            Ok(a) => {
                apply_auth(&core, &weak, &a);
                authed = true;
            }
            Err(e) => {
                // 6 saniyə sonra bir dəfə təkrar (əvvəlki davranış)
                let msg = format!("Network error: {}", e);
                banner(&weak, &msg);
                std::thread::sleep(Duration::from_secs(6));
                if let Ok(a) = core.client.guest_auth() {
                    apply_auth(&core, &weak, &a);
                    authed = true;
                }
            }
        }
        let _ = slint::invoke_from_event_loop({
            let weak = weak.clone();
            move || {
                if let Some(app) = weak.upgrade() {
                    app.global::<App>().set_booting(false);
                }
            }
        });
        *l(&core.boot_done) = true;
        if timing_enabled() {
            eprintln!("[timing] ilk ekran hazır: {:?} (yeni qonaq, authed={})", t0.elapsed(), authed);
        }
        spawn_load_models(core, weak);
    });
}

// ---------------- Göndərmə axını ----------------

fn now_hm() -> String {
    chrono::Local::now().format("%H:%M").to_string()
}

fn current_model(core: &Core) -> (String, String, usize) {
    let models = l(&core.models).clone();
    let idx = l(&core.persisted).model_index.min(models.len().saturating_sub(1));
    if let Some(m) = models.get(idx) {
        (m.id.clone(), m.name.clone(), idx)
    } else {
        ("glm-5.3-flash".into(), "GLM-5.3-Flash".into(), 0)
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

fn collect_history(msgs: &[Arc<RawMsg>]) -> Vec<(String, String)> {
    msgs.iter()
        .filter(|m| !m.error && !m.text.is_empty())
        .map(|m| (m.role.clone(), m.text.clone()))
        .collect()
}

/// composer-dəki əlavələri UI-da göstər (upload statusları ilə — vahid
/// siyahıdan: pending "…", hazırsa ✓, xətaya düşərsə qırmızı ad)
fn refresh_attachment_ui(core: &Core, weak: &Weak<AppWindow>) {
    let entries = l(&core.uploads).clone();
    let _ = slint::invoke_from_event_loop({
        let weak = weak.clone();
        move || {
            if let Some(app) = weak.upgrade() {
                let g = app.global::<App>();
                let vm: Rc<slint::VecModel<Attachment>> = Rc::new(slint::VecModel::default());
                for e in &entries {
                    vm.push(Attachment {
                        id: e.local_id.to_string().into(),
                        name: e.name.clone().into(),
                        media: e.media.clone().into(),
                        status: e.status,
                        size: api::human_size(e.size).into(),
                    });
                }
                g.set_attachments(vm.into());
            }
        }
    });
}

fn spawn_send(core: Arc<Core>, weak: Weak<AppWindow>, prompt: String, files: Vec<UploadedFile>) {
    // Yeni axın başlayır — əvvəlki job (nəsə işləyirsə) arxaikdir: dayandır.
    // Normalda UI buna imkan vermir (can-send !streaming tələb edir), bu
    // müdafiədir — regenerate/retry_send yolları üçün də tək nöqtədir.
    stop_current_job(&core);
    // İşı göstər
    {
        let um = RawMsg {
            id: api::gen_uuid(),
            role: "user".into(),
            text: prompt.clone(),
            files: files.clone(),
            streaming: false,
            error: false,
            retrying: false,
            stamp: now_hm(),
        };
        let am = RawMsg {
            id: api::gen_uuid(),
            role: "assistant".into(),
            text: String::new(),
            files: Vec::new(),
            streaming: true,
            error: false,
            retrying: false,
            stamp: now_hm(),
        };
        l(&core.messages).push(Arc::new(um));
        l(&core.messages).push(Arc::new(am));
        push_all_messages(&core, &weak);
        set_streaming(&core, &weak, true);
        set_phase(&weak, "");
        if timing_enabled() {
            eprintln!("[timing] send başladı ({} bayt mətn, {} fayl)", prompt.len(), files.len());
        }
    }

    let job = Job::new();
    *l(&core.current_job) = Some(job.clone());

    std::thread::spawn(move || {
        let result = run_send(&core, &weak, &prompt, &files, &job);
        match result {
            Ok(()) => {}
            Err(e) if e == "CAPTCHA" => {
                finish_stream(&core, &weak, &job, true,
                    "Server requires verification for this network.\nSign in with your account (Sign in) and try again.");
                banner(&weak, "Verification required — sign in with your account to continue");
            }
            Err(e) if e == "UNAUTHORIZED" => {
                // qonaq tokeni vaxtı bitib — yenilə və bir dəfə təkrar cəhd et
                if l(&core.persisted).is_guest {
                    // söhbət dəyişibsə yenidən GÖNDƏRMƏ — köhnə prompt
                    // yeni söhbətin chat_id-si ilə getməməli
                    if !is_current_job(&core, &job) {
                        return;
                    }
                    if let Ok(a) = core.client.guest_auth() {
                        apply_auth(&core, &weak, &a);
                        {
                            let mut msgs = l(&core.messages);
                            if let Some(last) = msgs.last_mut() {
                                let last = Arc::make_mut(last);
                                last.text.clear();
                                last.streaming = true;
                                last.error = false;
                            }
                        }
                        set_streaming(&core, &weak, true);
                        let _ = run_send(&core, &weak, &prompt, &files, &job);
                        return;
                    }
                } else {
                    if let Ok(a) = core.client.guest_auth() {
                        apply_auth(&core, &weak, &a);
                        toast(&weak, "Session expired — signed in as guest");
                    }
                }
                finish_stream(&core, &weak, &job, true, "Session expired. Please sign in again.");
            }
            Err(e) => {
                // AVTOMATİK YENİDƏN GÖNDƏRMƏ: fayllar saxlanılır, mesaj təkrarlanır
                if stream::is_retryable_error(&e) {
                    mark_retrying(&core, &weak, &job, true, &stream::clean_error(&e));
                    let mut delay = 2500u64;
                    for attempt in 0..3 {
                        std::thread::sleep(Duration::from_millis(api::jitter_ms(delay)));
                        if job.is_stopped() {
                            break;
                        }
                        // söhbət dəyişibsə təkrar GÖNDƏRMƏ — köhnə axın yeni
                        // söhbətin chat_id-sinə yazmasın
                        if !is_current_job(&core, &job) {
                            return;
                        }
                        // sonuncu assistant mesajını sıfırla
                        {
                            let mut msgs = l(&core.messages);
                            if let Some(last) = msgs.last_mut() {
                                let last = Arc::make_mut(last);
                                last.text.clear();
                                last.streaming = true;
                                last.error = false;
                                last.retrying = false;
                            }
                        }
                        set_streaming(&core, &weak, true);
                        match run_send(&core, &weak, &prompt, &files, &job) {
                            Ok(()) => return,
                            Err(e2) if e2 == "CAPTCHA" || e2 == "UNAUTHORIZED" => {
                                finish_stream(&core, &weak, &job, true, &stream::clean_error(&e2));
                                return;
                            }
                            Err(e2) => {
                                mark_retrying(&core, &weak, &job, true, &stream::clean_error(&e2));
                                delay = (delay * 2).min(15000);
                                let _ = attempt;
                                continue;
                            }
                        }
                    }
                    finish_stream(&core, &weak, &job, true, "Could not send after several retries. Tap to retry manually.");
                } else {
                    finish_stream(&core, &weak, &job, true, &e);
                }
            }
        }
    });
}

/// Son mesajı «avtomatik yenidən cəhd» vəziyyətinə sal
fn mark_retrying(core: &Core, weak: &Weak<AppWindow>, job: &Arc<Job>, on: bool, msg: &str) {
    if !is_current_job(core, job) {
        return; // söhbət dəyişib — köhnə axın yeni mesaja yazmasın
    }
    {
        let mut msgs = l(&core.messages);
        if let Some(last) = msgs.last_mut() {
            let last = Arc::make_mut(last);
            last.retrying = on;
            last.error = true;
            last.streaming = false;
            if !msg.is_empty() {
                last.text = msg.to_string();
            }
        }
    }
    update_last_message(core, weak);
}

fn run_send(core: &Arc<Core>, weak: &Weak<AppWindow>, prompt: &str, files: &[UploadedFile], job: &Arc<Job>) -> Result<(), String> {
    // Tarixçə: son İKİ sətir yeni göndərişin özüdür (göndərilən user mesajı +
    // boş assistant placeholder) — kontekstə daxil deyil. Əvvəlki kod bütün
    // siyahı üzərində collect_history + truncate(len-2) edirdi; amma
    // collect_history boş placeholder-i ZATƏN süzür → truncate əlavə olaraq
    // son REAL assistant cavabını da kəsirdi (model öz əvvəlki cavabını
    // görmürdü, cavabın keyfiyyəti hər növbəti turda düşürdü).
    let history: Vec<(String, String)> = {
        let msgs = l(&core.messages);
        let upto = msgs.len().saturating_sub(2);
        collect_history(&msgs[..upto])
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
    let flush_id = assistant_msg_id.clone();

    // Axın zamanı fasiləsiz yeniləmə üçün: 60 ms pəncərə + ARTIMLILIQ.
    // stable = son açıq bloqun bayt ofseti (yalnız quyruq parse olunur),
    // base   = bağlanmış blok sayı (UI modelində tətbiq başlanğıcı).
    let t_send = Instant::now();
    let mut last_push = Instant::now() - Duration::from_secs(1);
    let mut phase_now = String::new();
    let mut stable = 0usize;
    let mut base = 0usize;
    let mut first_delta = true;

    let core2 = core.clone();
    let weak2 = weak.clone();
    let job_g = job.clone();
    let mut emit = |ev: StreamEvent| {
        // Söhbət dəyişibsə bu axın artıq «yad»dır: hadisələri ud — köhnə
        // axın yeni söhbətin mesajlarına/chat_id-nə yazmasın (stop bayrağı
        // bloklanan oxu zamanı gec görülür, bu yoxlama dərhal işləyir).
        if !is_current_job(&core2, &job_g) {
            return;
        }
        match ev {
            StreamEvent::Delta(d) => {
                if first_delta {
                    first_delta = false;
                    if timing_enabled() {
                        eprintln!("[timing] ilk token gecikməsi (client): {:?}", t_send.elapsed());
                    }
                }
                {
                    let mut msgs = l(&core2.messages);
                    if let Some(last) = msgs.last_mut() {
                        Arc::make_mut(last).text.push_str(&d);
                    }
                }
                if last_push.elapsed() >= Duration::from_millis(60) {
                    last_push = Instant::now();
                    // ARTIMLILIQ: yalnız 'stable'-dan sonrakı quyruq parse olunur
                    let mut out_blocks: Vec<markdown::Block> = Vec::new();
                    let mut out_base = 0usize;
                    {
                        let msgs = l(&core2.messages);
                        if let Some(last) = msgs.last() {
                            if last.text.len() > stable {
                                let tail = &last.text[stable..];
                                let p = markdown::parse_spanned(tail);
                                let n = p.blocks.len();
                                let closed = if p.open_from.is_some() { n.saturating_sub(1) } else { n };
                                out_blocks = p.blocks.into_iter().map(|sb| sb.block).collect();
                                match p.open_from {
                                    Some(o) => stable += o,
                                    None => stable = last.text.len(),
                                }
                                out_base = base;
                                base += closed;
                            }
                        }
                    }
                    if !out_blocks.is_empty() {
                        flush_stream_blocks(&weak2, flush_id.clone(), out_base, out_blocks);
                    }
                }
            }
            StreamEvent::Reset => {
                // Axın sıfırdan başlayır (şəbəkə kəsintisi + avtomatik bərpa):
                // parse ofsetləri VƏ mətnin özü sıfırlanmalıdır. Əvvəl yalnız
                // ofsetlər sıfırlanırdı → bərpa axını eyni cavabı yenidən
                // göndərəndə mətn ikiləşirdi ("Hello" + "Hello world…").
                stable = 0;
                base = 0;
                {
                    let mut msgs = l(&core2.messages);
                    if let Some(last) = msgs.last_mut() {
                        Arc::make_mut(last).text.clear();
                    }
                }
                // UI-da köhnə blokları dərhal təmizlə (növbəti flush ≤60ms)
                update_last_message(&core2, &weak2);
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
        files,
        job,
        &mut emit,
    );
    drop(emit);
    if timing_enabled() {
        eprintln!("[timing] axın bitdi: {:?} ({} bayt)", t_send.elapsed(), {
            let msgs = l(&core.messages);
            msgs.last().map(|m| m.text.len()).unwrap_or(0)
        });
    }

    match result {
        Ok(()) => {
            finish_stream(core, weak, job, false, "");
            // başlıq yaradılıbsa siyahını yenilə
            if is_first && !l(&core.persisted).is_guest {
                spawn_load_conversations(core.clone(), weak.clone());
            }
            Ok(())
        }
        Err(e) => Err(e),
    }
}

/// Axını bitir: son mesajı final vəziyyətə sal (yalnız son sıra yenilənir —
/// bütün siyahı deyil). Job artıq current deyilsə (söhbət dəyişib) — heç nə
/// yazma: yeni söhbətin streaming vəziyyətini korlamayın.
fn finish_stream(core: &Core, weak: &Weak<AppWindow>, job: &Arc<Job>, is_error: bool, error_text: &str) {
    if !is_current_job(core, job) {
        return;
    }
    {
        let mut msgs = l(&core.messages);
        if let Some(last) = msgs.last_mut() {
            let last = Arc::make_mut(last);
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
    update_last_message(core, weak);
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

fn content_to_files(c: &Value) -> Vec<UploadedFile> {
    let mut out = Vec::new();
    if let Value::Array(arr) = c {
        for p in arr {
            let t = p["type"].as_str().unwrap_or("");
            let media = match t {
                "image_url" => "image",
                "video_url" => "video",
                "file_url" => "doc",
                _ => continue,
            };
            let url = p[t]["url"].as_str().unwrap_or("").to_string();
            if url.is_empty() {
                continue;
            }
            out.push(UploadedFile {
                id: url.clone(),
                name: if media == "image" { "Image".into() } else if media == "video" { "Video".into() } else { "File".into() },
                media: media.into(),
                size: 0,
                direct_url: String::new(),
            });
        }
    }
    out
}

fn spawn_open_conversation(core: Arc<Core>, weak: Weak<AppWindow>, id: String) {
    // aktiv axın varsa dayandır — köhnə söhbətin axını yeni söhbətə
    // "qan qarışdırmamalı" (Phase 1.4: keçid zamanı köhnə axını dayandır)
    stop_current_job(&core);
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
                    let files = if role == "user" { content_to_files(&m["content"]) } else { Vec::new() };
                    let ts = m["timestamp"].as_i64().unwrap_or(0);
                    let stamp = chrono::DateTime::from_timestamp(ts, 0)
                        .map(|t| t.with_timezone(&chrono::Local).format("%H:%M").to_string())
                        .unwrap_or_default();
                    raws.push(RawMsg {
                        id: m["id"].as_str().unwrap_or("").to_string(),
                        role,
                        text,
                        files,
                        streaming: false,
                        error: false,
                        retrying: false,
                        stamp,
                    });
                }
                *l(&core.chat_id) = id;
                *l(&core.chat_title) = title.clone();
                *l(&core.messages) = raws.into_iter().map(Arc::new).collect();
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

// ---------------- Fayl seçici + upload ----------------

fn spawn_open_picker(core: Arc<Core>, weak: Weak<AppWindow>) {
    if l(&core.persisted).is_guest {
        toast(&weak, "Sign in to attach files");
        return;
    }
    let _ = slint::invoke_from_event_loop({
        let weak = weak.clone();
        move || {
            if let Some(app) = weak.upgrade() {
                app.global::<App>().set_picker_loading(true);
                app.global::<App>().set_picker_files(Rc::new(slint::VecModel::<PickedFile>::default()).into());
                app.global::<App>().set_picker_open(true);
            }
        }
    });
    std::thread::spawn(move || {
        // icazə istə (ilk dəfə) + siyahı
        picker::request_storage_permission();
        let files: Vec<MediaFile> = picker::query_media_files(40);
        let items: Vec<PickedFile> = files
            .iter()
            .map(|f| PickedFile {
                id: f.id as i32,
                name: f.name.clone().into(),
                media: f.media.clone().into(),
                size: api::human_size(f.size).into(),
                uri: f.uri.clone().into(),
            })
            .collect();
        let _ = slint::invoke_from_event_loop({
            let weak = weak.clone();
            move || {
                if let Some(app) = weak.upgrade() {
                    let g = app.global::<App>();
                    let vm: Rc<slint::VecModel<PickedFile>> = Rc::new(slint::VecModel::default());
                    for it in items {
                        vm.push(it);
                    }
                    g.set_picker_files(vm.into());
                    g.set_picker_loading(false);
                }
            }
        });
    });
}

fn spawn_pick_file(core: Arc<Core>, weak: Weak<AppWindow>, uri: String) {
    // seçici bağla
    let _ = slint::invoke_from_event_loop({
        let weak = weak.clone();
        move || {
            if let Some(app) = weak.upgrade() {
                app.global::<App>().set_picker_open(false);
            }
        }
    });
    std::thread::spawn(move || {
        let token = l(&core.persisted).token.clone();
        // 1) oxu
        let (bytes, name, mime) = match picker::read_uri_bytes(&uri) {
            Ok(v) => v,
            Err(e) => {
                toast(&weak, &format!("Could not read file: {}", e));
                return;
            }
        };
        if bytes.is_empty() {
            toast(&weak, "File is empty");
            return;
        }
        if bytes.len() > 60 * 1024 * 1024 {
            toast(&weak, "File too large (max 60 MB)");
            return;
        }
        // 2) VAHİD siyahıda «yüklənir» vəziyyəti ilə göstər (bytes KÖÇÜRÜLÜR —
        // clone yox). İstifadəçi faylı seçən kimi çip görünür.
        let local_id = next_upload_id();
        let media = api::media_kind(&mime, &name).to_string();
        {
            let mut ups = l(&core.uploads);
            ups.push(UploadEntry {
                local_id,
                name: name.clone(),
                media,
                size: bytes.len() as i64,
                status: 0,
                file: None,
            });
        }
        refresh_attachment_ui(&core, &weak);
        let t_up = Instant::now();
        if timing_enabled() {
            eprintln!("[timing] upload başladı: {} ({} bayt)", name, bytes.len());
        }
        match core.client.upload_file(&token, bytes, &name, &mime) {
            Ok(mut up) => {
                if timing_enabled() {
                    eprintln!("[timing] upload bitdi: {:?}", t_up.elapsed());
                }
                if up.name.is_empty() || up.name == "file" {
                    up.name = name.clone();
                }
                {
                    let mut ups = l(&core.uploads);
                    if let Some(e) = ups.iter_mut().find(|e| e.local_id == local_id) {
                        e.status = 1;
                        e.size = if up.size > 0 { up.size } else { e.size };
                        e.name = if up.name.is_empty() { e.name.clone() } else { up.name.clone() };
                        e.file = Some(up);
                    }
                }
                refresh_attachment_ui(&core, &weak);
                toast(&weak, "Attached");
            }
            Err(e) => {
                {
                    let mut ups = l(&core.uploads);
                    if let Some(e2) = ups.iter_mut().find(|e2| e2.local_id == local_id) {
                        e2.status = 2;
                    }
                }
                refresh_attachment_ui(&core, &weak);
                toast(&weak, &format!("Upload failed: {}", e));
                // xətalı əlavəni təmizlə (yerli unikal id ilə — ad deyil)
                std::thread::sleep(Duration::from_millis(1800));
                {
                    let mut ups = l(&core.uploads);
                    ups.retain(|e| e.local_id != local_id);
                }
                refresh_attachment_ui(&core, &weak);
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

        // send (əlavələrlə birlikdə)
        let core_c = core.clone();
        let weak_c = weak.clone();
        g.on_send(move |text| {
            let t = text.trim().to_string();
            // yalnız HAZIR (upload bitmiş) əlavələr göndərilir; pending/xətalı
            // entry-lər atılır
            let files: Vec<UploadedFile> = {
                let ups = l(&core_c.uploads);
                ups.iter().filter_map(|e| e.file.clone()).collect()
            };
            if t.is_empty() && files.is_empty() {
                return;
            }
            let Some(app) = weak_c.upgrade() else { return };
            let g = app.global::<App>();
            g.set_input_text("".into());
            g.set_auto_scroll(true);
            // əlavələri göndərişə bağla, composer-i təmizlə
            *l(&core_c.uploads) = Vec::new();
            g.set_attachments(Rc::new(slint::VecModel::<Attachment>::default()).into());
            spawn_send(core_c.clone(), weak_c.clone(), t, files);
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
            // son assistant mesajını sil, son user mətnini + fayllarını yenidən göndər
            let prompt = {
                let mut msgs = l(&core_c.messages);
                while let Some(last) = msgs.last() {
                    if last.role == "assistant" {
                        msgs.pop();
                    } else {
                        break;
                    }
                }
                let p = msgs.last().filter(|m| m.role == "user").map(|m| (m.text.clone(), m.files.clone()));
                drop(msgs);
                p
            };
            if let Some((p, files)) = prompt {
                push_all_messages(&core_c, &weak_c);
                spawn_send(core_c.clone(), weak_c.clone(), p, files);
            }
        });

        // retry-send (xəta sonrası əl ilə)
        let core_c = core.clone();
        let weak_c = weak.clone();
        g.on_retry_send(move || {
            let prompt = {
                let msgs = l(&core_c.messages);
                msgs.last()
                    .filter(|m| m.role == "user")
                    .map(|m| (m.text.clone(), m.files.clone()))
            };
            if let Some((p, files)) = prompt {
                spawn_send(core_c.clone(), weak_c.clone(), p, files);
            }
        });

        // new chat
        let core_c = core.clone();
        let weak_c = weak.clone();
        g.on_new_chat(move || {
            // axın işləyirsə dayandır (yeni söhbətə yazmasın)
            stop_current_job(&core_c);
            *l(&core_c.chat_id) = String::new();
            *l(&core_c.chat_title) = "New Chat".into();
            *l(&core_c.messages) = Vec::new();
            let Some(app) = weak_c.upgrade() else { return };
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

        // sign in (email + şifrə — adi hesab girişi)
        let core_c = core.clone();
        let weak_c = weak.clone();
        g.on_do_sign_in(move |email, password| {
            let Some(app) = weak_c.upgrade() else { return };
            let g = app.global::<App>();
            g.set_login_busy(true);
            g.set_login_error("".into());
            let email_s = email.to_string();
            let pass_s = password.to_string();
            let core_cc = core_c.clone();
            let weak_cc = weak_c.clone();
            std::thread::spawn(move || {
                match core_cc.client.signin(&email_s, &pass_s) {
                    Ok(a) => {
                        apply_auth(&core_cc, &weak_cc, &a);
                        toast(&weak_cc, &format!("Welcome, {}", a.user_name));
                        reset_conversation(&core_cc, &weak_cc);
                        spawn_load_models(core_cc.clone(), weak_cc.clone());
                        spawn_load_conversations(core_cc, weak_cc);
                    }
                    Err(e) if e == "CAPTCHA_REQUIRED" => {
                        // captcha lazımdır → fallback ekranı (brauzer + token)
                        let _ = slint::invoke_from_event_loop({
                            let weak = weak_cc.clone();
                            move || {
                                if let Some(app) = weak.upgrade() {
                                    let g = app.global::<App>();
                                    g.set_login_busy(false);
                                    g.set_login_error(
                                        "Z.ai requires slide verification for sign-in from this app. Complete it once in your browser, then come back.".into(),
                                    );
                                    g.set_login_step(2);
                                }
                            }
                        });
                    }
                    Err(e) => {
                        let _ = slint::invoke_from_event_loop({
                            let weak = weak_cc.clone();
                            move || {
                                if let Some(app) = weak.upgrade() {
                                    let g = app.global::<App>();
                                    g.set_login_busy(false);
                                    g.set_login_error(e.into());
                                }
                            }
                        });
                    }
                }
            });
        });

        // token ilə giriş (clipboard / əl ilə)
        let core_c = core.clone();
        let weak_c = weak.clone();
        g.on_do_token_sign_in(move |token| {
            let app = weak_c.upgrade().unwrap();
            let g = app.global::<App>();
            g.set_login_busy(true);
            g.set_login_error("".into());
            let token_s = token.trim().to_string();
            let core_cc = core_c.clone();
            let weak_cc = weak_c.clone();
            std::thread::spawn(move || match core_cc.client.verify_token(&token_s) {
                Ok(a) => {
                    apply_auth(&core_cc, &weak_cc, &a);
                    toast(&weak_cc, &format!("Welcome, {}", a.user_name));
                    reset_conversation(&core_cc, &weak_cc);
                    spawn_load_models(core_cc.clone(), weak_cc.clone());
                    spawn_load_conversations(core_cc, weak_cc);
                }
                Err(_) => {
                    let _ = slint::invoke_from_event_loop({
                        let weak = weak_cc.clone();
                        move || {
                            if let Some(app) = weak.upgrade() {
                                let g = app.global::<App>();
                                g.set_login_busy(false);
                                g.set_login_error("Token is invalid or expired.".into());
                            }
                        }
                    });
                }
            });
        });

        // clipboard token yoxlaması
        let core_c = core.clone();
        let weak_c = weak.clone();
        g.on_check_clipboard_token(move || {
            let clip = picker::read_clipboard_text().unwrap_or_default();
            let clip = clip.trim().to_string();
            if clip.starts_with("eyJ") && clip.len() > 60 {
                let clip2 = clip.clone();
                let _ = slint::invoke_from_event_loop({
                    let weak = weak_c.clone();
                    move || {
                        if let Some(app) = weak.upgrade() {
                            let g = app.global::<App>();
                            g.set_token_auto_detected(true);
                            g.set_token_input(clip2.into());
                            g.set_login_busy(true);
                        }
                    }
                });
                // avtomatik yoxla
                let core_cc = core_c.clone();
                let weak_cc = weak_c.clone();
                std::thread::spawn(move || {
                    match core_cc.client.verify_token(&clip) {
                        Ok(a) => {
                            apply_auth(&core_cc, &weak_cc, &a);
                            toast(&weak_cc, &format!("Welcome, {}", a.user_name));
                            reset_conversation(&core_cc, &weak_cc);
                            spawn_load_models(core_cc.clone(), weak_cc.clone());
                            spawn_load_conversations(core_cc, weak_cc);
                        }
                        Err(_) => {
                            let _ = slint::invoke_from_event_loop({
                                let weak = weak_cc.clone();
                                move || {
                                    if let Some(app) = weak.upgrade() {
                                        let g = app.global::<App>();
                                        g.set_login_busy(false);
                                        g.set_token_auto_detected(false);
                                        g.set_login_error("Clipboard token is invalid — copy it again from the site.".into());
                                    }
                                }
                            });
                        }
                    }
                });
            } else {
                let _ = slint::invoke_from_event_loop({
                    let weak = weak_c.clone();
                    move || {
                        if let Some(app) = weak.upgrade() {
                            let g = app.global::<App>();
                            g.set_login_error("No token in clipboard. Copy the 'token' value from chat.z.ai first.".into());
                        }
                    }
                });
            }
        });

        // qonaq kimi davam
        let core_c = core.clone();
        let weak_c = weak.clone();
        g.on_continue_as_guest(move || {
            let Some(app) = weak_c.upgrade() else { return };
            let g = app.global::<App>();
            g.set_login_busy(true);
            let core_cc = core_c.clone();
            let weak_cc = weak_c.clone();
            std::thread::spawn(move || {
                // artıq boot zamanı qonaq tokeni alınıb — yoxla, yoxdursa al
                let has_token = !l(&core_cc.persisted).token.is_empty();
                if has_token {
                    apply_auth_guest(&core_cc, &weak_cc);
                } else {
                    match core_cc.client.guest_auth() {
                        Ok(a) => {
                            apply_auth(&core_cc, &weak_cc, &a);
                            toast(&weak_cc, "Continuing as guest");
                        }
                        Err(e) => {
                            let _ = slint::invoke_from_event_loop({
                                let weak = weak_cc.clone();
                                move || {
                                    if let Some(app) = weak.upgrade() {
                                        app.global::<App>().set_login_busy(false);
                                        app.global::<App>().set_login_error(e.into());
                                    }
                                }
                            });
                        }
                    }
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
                // aktiv axını dayandır
                stop_current_job(&core_cc);
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
                // söhbəti tam sıfırla (mesajlar, başlıq, UI modeli)
                reset_conversation(&core_cc, &weak_cc);
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
                            // login ekranına qayıt
                            g.set_authed(false);
                            g.set_login_step(0);
                            g.set_login_error("".into());
                        }
                    }
                });
                spawn_load_models(core_cc.clone(), weak_cc.clone());
                toast(&weak_cc, "Signed out");
            });
        });

        // brauzer aç
        g.on_open_browser(move |url| {
            picker::open_url(&url);
        });

        // fayl seçici
        let core_c = core.clone();
        let weak_c = weak.clone();
        g.on_open_picker(move || {
            spawn_open_picker(core_c.clone(), weak_c.clone());
        });
        g.on_close_picker(move || {});

        let core_c = core.clone();
        let weak_c = weak.clone();
        g.on_pick_file(move |uri| {
            spawn_pick_file(core_c.clone(), weak_c.clone(), uri.to_string());
        });

        // əlavəni sil (UI indeksi vahid siyahıya uyğundur; yerli id ilə silinir
        // ki, eyni adlı fayllar yanlışlıqla birlikdə silinməsin). UI dərhal
        // yenilənir (əvvəl chip ekranda qalırdı).
        let core_r = core.clone();
        let weak_r = weak.clone();
        g.on_remove_attachment(move |idx| {
            let i = idx.max(0) as usize;
            let removed = {
                let mut ups = l(&core_r.uploads);
                match ups.get(i) {
                    Some(e) => {
                        let id = e.local_id;
                        ups.retain(|e| e.local_id != id);
                        true
                    }
                    None => false,
                }
            };
            if removed {
                refresh_attachment_ui(&core_r, &weak_r);
            }
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
                let Some(app) = weak_c.upgrade() else { return };
                let g = app.global::<App>();
                g.set_model_index(idx);
                g.set_effort(2.min(labels.len().saturating_sub(1) as i32));
                apply_effort_labels(&g, &labels);
                g.set_model_popup_open(false);
            }
        });

        // effort
        let core_c = core.clone();
        let weak_e = weak.clone();
        g.on_set_effort(move |e| {
            let mut p = l(&core_c.persisted);
            p.effort_index = e.max(0) as usize;
            store::save(&p);
            let Some(app) = weak_e.upgrade() else { return };
            app.global::<App>().set_effort(e);
        });

        // clipboard
        g.on_copy_to_clipboard(|t| {
            crate::clipboard::copy_text(&t);
        });

        // lazy markdown parse: ListView sətri YARADILANDA çağırılır (yalnız
        // görünən sətirlər). Uzun söhbətin bütün tarixçəsi açılışda parse
        // olunmur — RAM və açılış vaxtı yalnız görünən hissəyə bağlı qalır.
        {
            let weak_b = weak.clone();
            g.on_ensure_blocks_parsed(move |idx| {
                let Some(app) = weak_b.upgrade() else { return };
                let g = app.global::<App>();
                let model = g.get_messages();
                let Some(vm) = model
                    .as_any()
                    .downcast_ref::<slint::VecModel<ChatMessage>>()
                else {
                    return;
                };
                let i = idx.max(0) as usize;
                if i >= vm.row_count() {
                    return;
                }
                let Some(row) = vm.row_data(i) else { return };
                // yalnız: assistant + xətasız + bitmiş + mətnli + HƏLƏ PARSE OLUNMAMIŞ
                // (parsed flag-i: "***" kimi 0 bloka parse olunan mətnlərdə də
                // təkrar-təkrar parse olunmanın qarşısını alır)
                if row.role != "assistant" || row.error || row.streaming || row.text.is_empty() || row.parsed {
                    return;
                }
                let mut upd = row.clone();
                upd.blocks = blocks_model(&row.text);
                upd.parsed = true;
                vm.set_row_data(i, upd);
            });
        }
    }

    // ---- dot animasiyası (axın zamanı) ----
    // Boş vaxtda event loop oyadılmır (batareya): streaming_flag ucuz
    // atomic oxunuşudur, yalnız axın aktiv ikən tick göndərilir.
    {
        let weak_dot = weak.clone();
        let flag = core.streaming_flag.clone();
        std::thread::spawn(move || loop {
            if flag.load(Ordering::Relaxed) {
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
            } else {
                std::thread::sleep(Duration::from_millis(1000));
            }
        });
    }

    // ---- clipboard avtomatik aşkarlama: app önə gələndə (yumuşaq poll) ----
    {
        let weak_cp = weak.clone();
        std::thread::spawn(move || {
            let mut last_clip = String::new();
            loop {
                std::thread::sleep(Duration::from_secs(3));
                // yalnız login gözləyəndə lazımdır
                let cell: Arc<Mutex<Option<(bool, i32, bool)>>> = Arc::new(Mutex::new(None));
                let cell2 = cell.clone();
                let weak_poll = weak_cp.clone();
                let ok = slint::invoke_from_event_loop(move || {
                    if let Some(app) = weak_poll.upgrade() {
                        let g = app.global::<App>();
                        *cell2.lock().unwrap() = Some((g.get_authed(), g.get_login_step(), g.get_login_busy()));
                    }
                })
                .is_ok();
                if !ok {
                    continue;
                }
                let state = match cell.lock() {
                    Ok(g) => g,
                    Err(e) => e.into_inner(),
                };
                let (authed, step, busy) = match *state {
                    Some(v) => v,
                    None => continue,
                };
                drop(state);
                if authed || busy || step != 2 {
                    continue;
                }
                if let Some(clip) = picker::read_clipboard_text() {
                    let clip = clip.trim().to_string();
                    if clip != last_clip && clip.starts_with("eyJ") && clip.len() > 60 {
                        last_clip = clip.clone();
                        // Tapıldı — GÖSTƏR, amma avtomatik daxil olma YOX:
                        // yalnız istifadəçi düyməyə toxunanda token istifadə olunur
                        // (mübadilə buferi razılığı). Android 10+ fon clipboard-u
                        // onsuz da yalnız ön plana verir.
                        let _ = slint::invoke_from_event_loop({
                            let weak = weak_cp.clone();
                            let clip = clip.clone();
                            move || {
                                if let Some(app) = weak.upgrade() {
                                    let g = app.global::<App>();
                                    g.set_token_auto_detected(true);
                                    g.set_token_input(clip.into());
                                    g.set_login_busy(false);
                                }
                            }
                        });
                    }
                }
            }
        });
    }

    // ---- boot ----
    spawn_boot(core.clone(), weak.clone());

    // ---- TEST hook (ZAI_TEST_SEND env) ----
    if let Ok(msg) = std::env::var("ZAI_TEST_SEND") {
        let core_c = core.clone();
        let weak_c = weak.clone();
        std::thread::spawn(move || {
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
                        g.set_authed(true);
                        g.invoke_send(msg.into());
                    }
                }
            });
        });
    }

    app.run().expect("Hadisə dövrəsi xətası");
}

/// Qonaq kimi davam: saxlanmış qonaq tokeni varsa UI-ı birbaşa aç
fn apply_auth_guest(core: &Core, weak: &Weak<AppWindow>) {
    let _ = slint::invoke_from_event_loop({
        let weak = weak.clone();
        move || {
            if let Some(app) = weak.upgrade() {
                let g = app.global::<App>();
                g.set_authed(true);
                g.set_login_busy(false);
                g.set_login_error("".into());
            }
        }
    });
    let _ = core;
}

#[cfg(test)]
mod tests {
    use super::*;

    fn msg(role: &str, text: &str, error: bool) -> Arc<RawMsg> {
        Arc::new(RawMsg {
            id: api::gen_uuid(),
            role: role.into(),
            text: text.into(),
            files: Vec::new(),
            streaming: false,
            error,
            retrying: false,
            stamp: String::new(),
        })
    }

    /// run_send-in tarixçə kəsmə məntiqi: son 2 sətir (yeni user + boş
    /// placeholder) çıxılır, əvvəlki TAM turlar (assistant cavabları daxil)
    /// kontekstdə QALIR.
    #[test]
    fn history_keeps_previous_assistant_answer() {
        let msgs = vec![
            msg("user", "1", false),
            msg("assistant", "A1", false),
            msg("user", "2", false),
            msg("assistant", "A2", false),
            msg("user", "3", false),
            msg("assistant", "", false), // boş placeholder (streaming)
        ];
        let upto = msgs.len().saturating_sub(2);
        let h = collect_history(&msgs[..upto]);
        // "3" (yeni prompt) run_completion-ə ayrıca ötürülür — tarixçədə OLMAMALI;
        // "A2" (son real assistant cavabı) isə QALMALI (köhnə kod onu itirirdi)
        assert_eq!(
            h,
            vec![
                ("user".to_string(), "1".to_string()),
                ("assistant".to_string(), "A1".to_string()),
                ("user".to_string(), "2".to_string()),
                ("assistant".to_string(), "A2".to_string()),
            ],
            "son real assistant cavabı (A2) tarixçədə qalmalıdır"
        );
    }

    /// Xətalı mesajlar (avtomatik retry göstəricisi) kontekstə düşməməlidir.
    #[test]
    fn history_skips_error_rows() {
        let msgs = vec![
            msg("user", "1", false),
            msg("assistant", "A1", false),
            msg("user", "2", false),
            msg("assistant", "Server busy", true), // error — süzülür
            msg("user", "3", false),                // yeni prompt
            msg("assistant", "", false),             // placeholder
        ];
        let upto = msgs.len().saturating_sub(2);
        let h = collect_history(&msgs[..upto]);
        assert_eq!(
            h,
            vec![
                ("user".to_string(), "1".to_string()),
                ("assistant".to_string(), "A1".to_string()),
                ("user".to_string(), "2".to_string()),
            ]
        );
    }
}
