use crate::api::{ChatSummary, Client};
use crate::icons::{Cards, Icons};
use crate::stream::{spawn_completion, StreamEvent};
use crate::store::PersistedState;
use crate::theme::{BG, HOVER, SURFACE, TEXT, TEXT_2};
use egui::Color32;
use std::sync::atomic::AtomicBool;
use std::sync::mpsc::{Receiver, Sender};
use std::sync::Arc;

#[derive(Clone)]
pub struct Msg {
    pub id: String,
    pub role: String,
    pub content: String,
    pub phase: Option<String>,
    pub ts: u64,
}

pub enum BgEvent {
    Ready(Box<Client>),
    Models(Vec<(String, String)>),
    ChatList(Vec<ChatSummary>),
    ChatLoaded(String, String, Vec<Msg>),
    Title(String),
    Err(String),
}

pub struct MyApp {
    pub client: Option<Client>,
    pub persist: PersistedState,
    pub models: Vec<(String, String)>,
    pub model_idx: usize,

    pub chat_id: Option<String>,
    pub chat_title: String,
    pub messages: Vec<Msg>,
    pub input: String,
    pub chat_list: Vec<ChatSummary>,
    pub list_loading: bool,

    pub tx_stream: Option<Sender<StreamEvent>>,
    pub rx_stream: Option<Receiver<StreamEvent>>,
    pub stop_flag: Option<Arc<AtomicBool>>,
    pub streaming: bool,
    pub connecting: bool,
    /// İstifadəçi daxilolma düyməsini basıb (startup auto-connect deyil)
    pub login_pending: bool,

    pub tx_bg: Sender<BgEvent>,
    pub rx_bg: Receiver<BgEvent>,

    pub token_input: String,
    pub email_input: String,
    pub pass_input: String,
    pub search_input: String,
    pub error: Option<String>,
    pub info: Option<String>,
    pub session_id: String,

    // Overlay state (veb kimi: tək ekran + overlay-lər)
    pub drawer_open: bool,
    pub signin_open: bool,
    pub signin_email: bool,
    pub model_popup: bool,
    pub api_menu: bool,
    pub effort: f32,

    // İkonlar + kart şəkilləri (vebdən rasterləşdirilmiş)
    pub icons: Icons,
    pub cards: Cards,

    // Pulldown-refresh (aşağı çəkib buraxaraq yenilə)
    pub pull_dist: f32,
    pub pull_armed: bool,

    // Avtomatik bərpa: kəsilmədə retry, donma nəzarəti, 401-də sessiya yeniləmə
    pub retry_count: u32,
    pub last_prompt: Option<String>,
    pub pending_retry: Option<String>,
    pub regen_prompt: Option<String>,
    pub last_activity: Option<std::time::Instant>,
    pub egui_ctx: egui::Context,
}

impl MyApp {
    pub fn new(cc: &eframe::CreationContext) -> Self {
        // ---------- Tema: chat.z.ai veb (işıq) ----------
        let mut visuals = egui::Visuals::light();
        visuals.panel_fill = BG;
        visuals.window_fill = SURFACE;
        visuals.extreme_bg_color = SURFACE;
        visuals.faint_bg_color = BG;
        visuals.widgets.noninteractive.bg_fill = SURFACE;
        visuals.widgets.noninteractive.fg_stroke.color = TEXT_2;
        visuals.widgets.inactive.bg_fill = SURFACE;
        visuals.widgets.inactive.fg_stroke.color = TEXT;
        visuals.widgets.hovered.bg_fill = HOVER;
        visuals.widgets.hovered.fg_stroke.color = TEXT;
        visuals.widgets.active.bg_fill = HOVER;
        visuals.widgets.active.fg_stroke.color = TEXT;
        visuals.selection.bg_fill = Color32::from_rgb(220, 230, 250);
        visuals.selection.stroke.color = TEXT;
        visuals.widgets.noninteractive.bg_stroke.color = crate::theme::border_alpha();
        cc.egui_ctx.set_visuals(visuals);

        // ---------- Style ----------
        use egui::{FontId, TextStyle};
        cc.egui_ctx.all_styles_mut(|style| {
        style.text_styles = [
            (
                TextStyle::Small,
                FontId::new(13.0, egui::FontFamily::Proportional),
            ),
            (
                TextStyle::Body,
                FontId::new(16.0, egui::FontFamily::Proportional),
            ),
            (
                TextStyle::Button,
                FontId::new(16.0, egui::FontFamily::Proportional),
            ),
            (
                TextStyle::Heading,
                FontId::new(20.0, egui::FontFamily::Proportional),
            ),
            (
                TextStyle::Monospace,
                FontId::new(14.0, egui::FontFamily::Monospace),
            ),
        ]
        .into();
        style.spacing.button_padding = egui::vec2(14.0, 8.0);
        });

        // ---------- Fontlar (ə, ö, ğ, ş üçün Carlito; serif başlıq üçün Tinos) ----------
        let mut fonts = egui::FontDefinitions::default();
        fonts.font_data.insert(
            "carlito".into(),
            Arc::new(egui::FontData::from_static(include_bytes!(
                "../assets/fonts/Carlito-Regular.ttf"
            ))),
        );
        fonts.font_data.insert(
            "carlito-bold".into(),
            Arc::new(egui::FontData::from_static(include_bytes!(
                "../assets/fonts/Carlito-Bold.ttf"
            ))),
        );
        fonts.font_data.insert(
            "crimson-bold".into(),
            Arc::new(egui::FontData::from_static(include_bytes!(
                "../assets/fonts/CrimsonText-Bold.ttf"
            ))),
        );
        fonts.font_data.insert(
            "mono".into(),
            Arc::new(egui::FontData::from_static(include_bytes!(
                "../assets/fonts/DejaVuSansMono.ttf"
            ))),
        );
        fonts
            .families
            .entry(egui::FontFamily::Proportional)
            .or_default()
            .insert(0, "carlito".into());
        fonts
            .families
            .entry(egui::FontFamily::Monospace)
            .or_default()
            .insert(0, "mono".into());
        fonts.families.insert(
            egui::FontFamily::Name(std::sync::Arc::from("bold")),
            vec!["carlito-bold".into()],
        );
        fonts.families.insert(
            egui::FontFamily::Name(std::sync::Arc::from("serif")),
            vec!["crimson-bold".into()],
        );
        cc.egui_ctx.set_fonts(fonts);

        // ---------- İkonlar + kart şəkilləri ----------
        let icons = Icons::load(&cc.egui_ctx);
        let cards = Cards::load(&cc.egui_ctx);

        let (tx_bg, rx_bg) = std::sync::mpsc::channel::<BgEvent>();
        let persist = crate::store::load();

        MyApp {
            client: None,
            persist: persist.clone(),
            models: vec![
                ("x-preview-l".into(), "GLM-5.3-Flash".into()),
                ("glm-5.3".into(), "GLM-5.3".into()),
                ("glm-5.2".into(), "GLM-5.2".into()),
            ],
            model_idx: match persist.model.as_deref() {
                Some("glm-5.3") => 1,
                Some("glm-5.2") => 2,
                _ => 0,
            },
            chat_id: None,
            chat_title: "New Chat".into(),
            messages: Vec::new(),
            input: String::new(),
            chat_list: Vec::new(),
            list_loading: false,
            tx_stream: None,
            rx_stream: None,
            stop_flag: None,
            streaming: false,
            connecting: false,
            login_pending: false,
            tx_bg,
            rx_bg,
            token_input: String::new(),
            email_input: String::new(),
            pass_input: String::new(),
            search_input: String::new(),
            error: None,
            info: None,
            session_id: persist
                .session_id
                .clone()
                .unwrap_or_else(|| format!("web-{}", crate::api::gen_uuid())),
            drawer_open: false,
            signin_open: false,
            signin_email: false,
            model_popup: false,
            api_menu: false,
            effort: 1.0,
            icons,
            cards,
            pull_dist: 0.0,
            pull_armed: false,
            retry_count: 0,
            last_prompt: None,
            pending_retry: None,
            regen_prompt: None,
            last_activity: None,
            egui_ctx: cc.egui_ctx.clone(),
        }
    }

    /// Başlanğıcda avtomatik qoşul (veb kimi: saxlanmış token → qonaq)
    pub fn auto_connect(&mut self) {
        if self.client.is_none() {
            self.start_guest();
        }
    }

    /// Hesabdan çıx: tokeni sil, təmiz vəziyyətə qayıt, arxa planda qonaq al
    pub fn sign_out(&mut self) {
        self.persist.token = None;
        crate::store::save(&self.persist.clone());
        self.client = None;
        self.chat_id = None;
        self.chat_title = "New Chat".into();
        self.messages.clear();
        self.chat_list.clear();
        self.streaming = false;
        self.stop_flag = None;
        self.error = None;
        self.info = None;
        self.regen_prompt = None;
        self.drawer_open = false;
        self.signin_open = false;
        self.signin_email = false;
        self.token_input.clear();
        self.start_guest();
    }

    pub fn model(&self) -> String {
        self.models
            .get(self.model_idx)
            .map(|(id, _)| id.clone())
            .unwrap_or_else(|| "x-preview-l".into())
    }

    pub fn save_state(&mut self) {
        self.persist.model = Some(self.model());
        self.persist.session_id = Some(self.session_id.clone());
        if let Some(c) = &self.client {
            self.persist.token = Some(c.token.clone());
        }
        crate::store::save(&self.persist);
    }

    // ---------- Arxa plan əməliyyatları ----------

    pub fn start_guest(&mut self) {
        self.connecting = true;
        self.error = None;
        let tx = self.tx_bg.clone();
        let saved_token = self.persist.token.clone();
        std::thread::spawn(move || {
            let mut c = match Client::new() {
                Ok(c) => c,
                Err(e) => {
                    let _ = tx.send(BgEvent::Err(e));
                    return;
                }
            };
            let mut used_saved = false;
            if let Some(tok) = saved_token {
                if c.verify_token(&tok).is_ok() {
                    used_saved = true;
                }
            }
            if !used_saved {
                if let Err(e) = c.guest_auth() {
                    let _ = tx.send(BgEvent::Err(e));
                    return;
                }
            }
            let models = c.models().unwrap_or_default();
            let _ = tx.send(BgEvent::Ready(Box::new(c)));
            let _ = tx.send(BgEvent::Models(models));
        });
    }

    pub fn start_token_login(&mut self) {
        let tok = self.token_input.trim().to_string();
        if tok.len() < 40 {
            self.error = Some("Token qısa görünür — tam tokeni yapışdırın.".into());
            return;
        }
        self.connecting = true;
        self.login_pending = true;
        self.error = None;
        let tx = self.tx_bg.clone();
        std::thread::spawn(move || {
            let mut c = match Client::new() {
                Ok(c) => c,
                Err(e) => {
                    let _ = tx.send(BgEvent::Err(e));
                    return;
                }
            };
            match c.verify_token(&tok) {
                Ok(()) => {}
                Err(e) => {
                    let _ = tx.send(BgEvent::Err(e));
                    return;
                }
            }
            let models = c.models().unwrap_or_default();
            let _ = tx.send(BgEvent::Ready(Box::new(c)));
            let _ = tx.send(BgEvent::Models(models));
        });
    }

    pub fn refresh_chats(&mut self) {
        if self.client.is_none() {
            return;
        }
        self.list_loading = true;
        let tx = self.tx_bg.clone();
        let c = self.client.as_ref().unwrap().shallow_clone();
        std::thread::spawn(move || {
            match c.chat_list() {
                Ok(list) => {
                    let _ = tx.send(BgEvent::ChatList(list));
                }
                Err(e) => {
                    let _ = tx.send(BgEvent::Err(e));
                }
            }
        });
    }

    pub fn open_chat(&mut self, id: String) {
        let c = self.client.as_ref().unwrap().shallow_clone();
        let tx = self.tx_bg.clone();
        self.list_loading = true;
        std::thread::spawn(move || {
            match c.chat_detail(&id) {
                Ok(v) => {
                    let title = v["title"].as_str().unwrap_or("Söhbət").to_string();
                    let mut msgs = Vec::new();
                    // history.messages obyektindən zənciri yenidən qur
                    if let Some(history) = v.pointer("/chat/history") {
                        let empty = serde_json::Map::new();
                        let map = history["messages"].as_object().unwrap_or(&empty);
                        let current = history["currentId"].as_str();
                        if let Some(cur) = current {
                            // currentId-dən parentId ilə kökə qalx
                            let mut chain = Vec::new();
                            let mut cursor = Some(cur.to_string());
                            while let Some(cid) = cursor {
                                if let Some(m) = map.get(&cid) {
                                    chain.push((
                                        cid.clone(),
                                        m["parentId"].as_str().map(|s| s.to_string()),
                                        m.clone(),
                                    ));
                                    cursor = m["parentId"].as_str().map(|s| s.to_string());
                                } else {
                                    break;
                                }
                            }
                            chain.reverse();
                            for (id, _, m) in chain {
                                let role = m["role"].as_str().unwrap_or("assistant").to_string();
                                let content = extract_content(&m);
                                msgs.push(Msg {
                                    id,
                                    role,
                                    content,
                                    phase: None,
                                    ts: m["timestamp"].as_u64().unwrap_or(0),
                                });
                            }
                        } else {
                            // fallback: xəritə ardıcıllığı
                            for (mid, m) in map {
                                msgs.push(Msg {
                                    id: mid.clone(),
                                    role: m["role"].as_str().unwrap_or("assistant").to_string(),
                                    content: extract_content(m),
                                    phase: None,
                                    ts: m["timestamp"].as_u64().unwrap_or(0),
                                });
                            }
                        }
                    }
                    let _ = tx.send(BgEvent::ChatLoaded(id, title, msgs));
                }
                Err(e) => {
                    let _ = tx.send(BgEvent::Err(e));
                }
            }
        });
    }

    pub fn send_message(&mut self, _ctx: egui::Context) {
        let prompt = self.input.trim().to_string();
        if prompt.is_empty() || self.streaming {
            return;
        }
        // Klient hələ hazır deyilsə (başlanğıc qoşulma) — girişi itirmə, xəbər ver
        if self.client.is_none() {
            self.info = Some("Hələ qoşulur — bir an sonra yenidən göndərin".into());
            return;
        }
        self.input.clear();
        self.dispatch(prompt, false);
    }

    /// Mesajı göndər. `is_retry` — avtomatik bərpa zamanı təkrar göndəriş
    /// (retry sayğacı sıfırlanmır; köhnə user+assistant cütü artıq çıxarılıb olmalıdır).
    pub fn dispatch(&mut self, prompt: String, is_retry: bool) {
        if prompt.is_empty() || self.streaming {
            return;
        }
        self.error = None;
        if !is_retry {
            self.retry_count = 0;
            self.info = None;
        }
        self.last_prompt = Some(prompt.clone());
        self.last_activity = Some(std::time::Instant::now());

        // Hər göndərişdə YENİ kanal — köhnə (kəsilmiş/donmuş) axının hadisələri
        // yeni cəhdlə qarışmır: köhnə rx düşür, köhnə thread-in göndərişləri
        // sükutla nəzərə alınmır.
        let (tx, rx) = std::sync::mpsc::channel();
        self.tx_stream = Some(tx);
        self.rx_stream = Some(rx);

        // İstifadəçi mesajı + boş köməkçi
        let user_id = crate::api::gen_uuid();
        self.messages.push(Msg {
            id: user_id.clone(),
            role: "user".into(),
            content: prompt.clone(),
            phase: None,
            ts: crate::api::now_ms() / 1000,
        });
        let assistant_id = crate::api::gen_uuid();
        self.messages.push(Msg {
            id: assistant_id.clone(),
            role: "assistant".into(),
            content: String::new(),
            phase: None,
            ts: crate::api::now_ms() / 1000,
        });
        self.streaming = true;

        let client = self.client.as_ref().unwrap().shallow_clone();
        let history: Vec<(String, String)> = self.messages[..self.messages.len() - 2]
            .iter()
            .map(|m| (m.role.clone(), m.content.clone()))
            .collect();
        let history_ids: Vec<(String, String, String)> = self.messages[..self.messages.len() - 2]
            .iter()
            .map(|m| (m.id.clone(), m.role.clone(), m.content.clone()))
            .collect();
        let title = self.chat_title.clone();
        let is_first = self.chat_id.is_none();

        let job = spawn_completion(
            client,
            self.chat_id.clone(),
            prompt,
            history,
            history_ids,
            user_id,
            assistant_id,
            title,
            is_first,
            self.model(),
            self.session_id.clone(),
            self.egui_ctx.clone(),
            self.tx_stream.as_ref().unwrap().clone(),
        );
        self.stop_flag = Some(job.stop);
    }

    pub fn stop_generation(&mut self) {
        if let Some(f) = &self.stop_flag {
            f.store(true, std::sync::atomic::Ordering::Relaxed);
        }
        self.streaming = false;
        if let Some(last) = self.messages.last_mut() {
            if last.role == "assistant" {
                last.phase = None;
            }
        }
    }

    // ---------- Axın hadisələrini emal et ----------

    pub fn drain_stream(&mut self) {
        // Əvvəlcə hadisələri topla (rx immutable borroudur), sonra emal et —
        // emal zamanı dispatch/retry self-i mutasiya edir.
        let events: Vec<StreamEvent> = {
            let rx = match &self.rx_stream {
                Some(r) => r,
                None => return,
            };
            let mut events = Vec::new();
            while let Ok(ev) = rx.try_recv() {
                events.push(ev);
            }
            events
        };
        for ev in events {
            // axın aktivdir — donma sayğacını sıfırla
            self.last_activity = Some(std::time::Instant::now());
            match ev {
                StreamEvent::ChatCreated(id) => {
                    self.chat_id = Some(id);
                }
                StreamEvent::Delta(d) => {
                    if let Some(last) = self.messages.last_mut() {
                        if last.role == "assistant" {
                            last.content.push_str(&d);
                        }
                    }
                }
                StreamEvent::Phase(p) => {
                    if let Some(last) = self.messages.last_mut() {
                        if last.role == "assistant" {
                            last.phase = Some(p);
                        }
                    }
                }
                StreamEvent::Title(t) => {
                    if t != "New Chat" && !t.is_empty() {
                        self.chat_title = t.clone();
                        for c in &mut self.chat_list {
                            if Some(&c.id) == self.chat_id.as_ref() {
                                c.title = t.clone();
                            }
                        }
                    }
                }
                StreamEvent::Done => {
                    self.streaming = false;
                    self.last_activity = None;
                    self.last_prompt = None;
                    self.retry_count = 0;
                    self.regen_prompt = None;
                    self.info = None;
                    // Yeni chat yaradılıbsa tarixçəni arxa planda yeniləşdir
                    self.refresh_chats();
                    if let Some(last) = self.messages.last_mut() {
                        if last.role == "assistant" {
                            last.phase = None;
                            if last.content.trim().is_empty() {
                                last.content = "…".into();
                            }
                        }
                    }
                }
                StreamEvent::Error(e) => {
                    self.streaming = false;
                    self.last_activity = None;
                    self.handle_stream_error(e);
                }
            }
        }
    }

    // ---------- Avtomatik bərpa ----------

    /// Axın xətasını emal et: 401 → sessiya yenilə; kəsilmə → təkrar cəhd;
    /// digər hallarda istifadəçiyə xəta göstər (Regenerate üçün promptu saxla).
    fn handle_stream_error(&mut self, e: String) {
        // Regenerate düyməsi üçün promptu qoru
        if self.regen_prompt.is_none() {
            self.regen_prompt = self.last_prompt.clone();
        }
        let low = e.to_lowercase();
        // 401 — token vaxtı bitib
        if low.contains("401") || low.contains("unauthorized") {
            self.remove_last_pair();
            if self.client.as_ref().map(|c| c.is_guest).unwrap_or(false) {
                // qonaq: avtomatik yeni qonaq sessiyası al və mesajı təkrar göndər
                self.pending_retry = self.last_prompt.take();
                self.regen_prompt = None;
                self.info = Some("Qonaq sessiyası vaxtı bitdi — yenilənir…".into());
                self.reauth_guest();
            } else {
                // hesab tokeni bitib — qonaq sessiyasına qayıt + daxilolma təklif et
                self.persist.token = None;
                crate::store::save(&self.persist.clone());
                self.client = None;
                self.signin_open = true;
                self.signin_email = false;
                self.start_guest();
                self.error =
                    Some("Sessiya vaxtı bitdi — tokeni yenidən daxil edin".into());
            }
            return;
        }
        // kəsilmə/donma — avtomatik təkrar cəhd (maks. 2)
        if Self::is_retryable(&e) && self.retry_count < 2 {
            self.retry_count += 1;
            if let Some(p) = self.last_prompt.take() {
                self.remove_last_pair();
                self.regen_prompt = None;
                self.info = Some(format!(
                    "Bağlantı kəsildi — avtomatik yenidən cəhd ({}/2)…",
                    self.retry_count
                ));
                self.dispatch(p, true);
                return;
            }
        }
        self.last_prompt = None;
        self.retry_count = 0;
        self.error = Some(e);
    }

    /// Şəbəkə xətaları (kəsilmə, timeout, 5xx, donma) təkrar cəhdə layiqdirmi?
    fn is_retryable(e: &str) -> bool {
        let e = e.to_lowercase();
        if e.contains("captcha")
            || e.contains("limit")
            || e.contains("versiya")
            || e.contains("401")
            || e.contains("426")
            || e.contains("token")
        {
            return false;
        }
        e.contains("şəbəkə")
            || e.contains("bağlantı")
            || e.contains("kəsildi")
            || e.contains("dondu")
            || e.contains("timeout")
            || e.contains("http 5")
            || e.contains("connection")
            || e.contains("reset")
            || e.contains("eof")
    }

    /// Son göndərilən user+assistant cütünü ekrandan çıxar (retry üçün)
    pub fn remove_last_pair(&mut self) {
        if self
            .messages
            .last()
            .map(|m| m.role == "assistant")
            .unwrap_or(false)
        {
            self.messages.pop();
        }
        if self
            .messages
            .last()
            .map(|m| m.role == "user")
            .unwrap_or(false)
        {
            self.messages.pop();
        }
    }

    /// Arxa fonda yeni qonaq sessiyası al (ekran dəyişmır)
    fn reauth_guest(&mut self) {
        let tx = self.tx_bg.clone();
        std::thread::spawn(move || {
            let mut c = match Client::new() {
                Ok(c) => c,
                Err(e) => {
                    let _ = tx.send(BgEvent::Err(e));
                    return;
                }
            };
            if let Err(e) = c.guest_auth() {
                let _ = tx.send(BgEvent::Err(e));
                return;
            }
            let _ = tx.send(BgEvent::Ready(Box::new(c)));
        });
    }

    /// Axın donmasına nəzarət — 180 saniyə heç bir hadisə gəlməsə kəsib bərpa et.
    /// (GLM-in uzun "düşünmə" fazası 60-90 saniyə səssiz ola bilər — ona görə 180s)
    pub fn stream_watchdog(&mut self) {
        if self.streaming {
            if let Some(t) = self.last_activity {
                if t.elapsed() > std::time::Duration::from_secs(180) {
                    self.stop_generation();
                    self.handle_stream_error("Axın dondu — avtomatik bərpa".into());
                }
            }
        }
    }

    // ---------- Pulldown-refresh ----------

    /// Scroll yuxarıda ikən barmağın aşağı çəkilməsini izləyir.
    /// Buraxılışda hədd keçilibsə `true` qaytarır (yeniləməni tetiklə).
    pub fn pull_refresh_step(&mut self, ui: &egui::Ui, at_top: bool) -> bool {
        let (dragging, dy) =
            ui.input(|i| (i.pointer.is_decidedly_dragging(), i.pointer.delta().y));
        if dragging && at_top && dy > 0.0 {
            self.pull_dist = (self.pull_dist + dy).min(140.0);
        } else if !dragging {
            let fire = self.pull_armed;
            self.pull_dist = 0.0;
            self.pull_armed = false;
            return fire;
        }
        self.pull_armed = self.pull_dist >= 80.0;
        false
    }

    pub fn drain_bg(&mut self) {
        while let Ok(ev) = self.rx_bg.try_recv() {
            match ev {
                BgEvent::Ready(c) => {
                    // brauzer warm-up-u fon rejimində
                    c.warm_up();
                    self.client = Some(*c);
                    // 401-dən sonra gözləyən mesaj varsa avtomatik göndər
                    if let Some(p) = self.pending_retry.take() {
                        self.info = Some("Sessiya yeniləndi — mesaj yenidən göndərilir…".into());
                        self.dispatch(p, true);
                    }
                }
                BgEvent::Models(models) => {
                    if !models.is_empty() {
                        // saxlanmış modeli saxla, yeni siyahını qəbul et
                        let saved = self.model();
                        self.models = models;
                        self.model_idx = self
                            .models
                            .iter()
                            .position(|(id, _)| *id == saved)
                            .unwrap_or(0);
                    }
                    if self.connecting {
                        self.connecting = false;
                        // daxilolma uğurludursa modalı bağla (startup auto-connect yox)
                        if self.login_pending {
                            self.login_pending = false;
                            self.signin_open = false;
                            self.signin_email = false;
                        }
                        self.save_state();
                        self.refresh_chats();
                    }
                }
                BgEvent::ChatList(list) => {
                    self.chat_list = list;
                    self.list_loading = false;
                }
                BgEvent::ChatLoaded(id, title, msgs) => {
                    self.chat_id = Some(id);
                    self.chat_title = title;
                    self.messages = msgs;
                    self.list_loading = false;
                    self.drawer_open = false;
                    self.error = None;
                    self.info = None;
                    self.regen_prompt = None;
                }
                BgEvent::Title(t) => {
                    if t != "New Chat" && !t.is_empty() && (self.chat_title == "New Chat" || self.chat_title == "Yeni söhbət") {
                        self.chat_title = t.clone();
                        for c in &mut self.chat_list {
                            if Some(&c.id) == self.chat_id.as_ref() {
                                c.title = t;
                                break;
                            }
                        }
                    }
                }
                BgEvent::Err(e) => {
                    if e.contains("__CLIENT__") {
                        continue;
                    }
                    self.connecting = false;
                    self.login_pending = false;
                    self.list_loading = false;
                    self.error = Some(e);
                }
            }
        }
    }
}

/// Server mesajından mətn çıxar (content: string | array)
fn extract_content(m: &serde_json::Value) -> String {
    match &m["content"] {
        serde_json::Value::String(s) => s.clone(),
        serde_json::Value::Array(arr) => {
            let mut out = String::new();
            for part in arr {
                if let Some(t) = part["text"].as_str() {
                    out.push_str(t);
                }
            }
            out
        }
        _ => String::new(),
    }
}
