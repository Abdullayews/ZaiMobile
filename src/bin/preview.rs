//! Desktop preview — UI-i telefonda yoxlamadan əvvəl desktopda görmək üçün.
//! (CI bu target-i yığmır — yalnız `--lib` qurulur.)
//!
//! İstifadə:
//!   cargo run --bin preview --release                    # landing
//!   ZAI_OPEN=signin  cargo run --bin preview --release   # sign-in modal
//!   ZAI_OPEN=email   ...                                 # email/token ekranı
//!   ZAI_OPEN=model   ...                                 # model popup
//!   ZAI_OPEN=api     ...                                 # API menyu
//!   ZAI_OPEN=drawer  ...                                 # tarixçə drawer
//!   ZAI_OPEN=chat    ...                                 # fake mesajlı söhbət
//!   ZAI_OPEN=thinking ...                                # Thinking... vəziyyəti
//!
//! Headless screenshot: scripts/capture.sh (Xvfb + ffmpeg x11grab)

fn main() -> eframe::Result {
    env_logger::init();
    let options = eframe::NativeOptions {
        renderer: eframe::Renderer::Glow,
        viewport: egui::ViewportBuilder::default()
            .with_inner_size([412.0, 915.0])
            .with_title("Z.ai"),
        ..Default::default()
    };
    eframe::run_native(
        "Z.ai",
        options,
        Box::new(|cc| {
            let mut app = zai_mobile::ui::MyApp::new(cc);
            app.auto_connect();
            match std::env::var("ZAI_OPEN").as_deref() {
                Ok("signin") => {
                    app.signin_open = true;
                }
                Ok("email") => {
                    app.signin_open = true;
                    app.signin_email = true;
                }
                Ok("drawer") => {
                    app.drawer_open = true;
                }
                Ok("model") => {
                    app.model_popup = true;
                }
                Ok("api") => {
                    app.api_menu = true;
                }
                Ok("chat") => {
                    use zai_mobile::ui::Msg;
                    app.chat_id = Some("test-chat".into());
                    app.chat_title = "Söhbət başlığı".into();
                    app.messages = vec![
                        Msg {
                            id: "m1".into(),
                            role: "user".into(),
                            content: "Salam! Bu bir test mesajıdır — azərbaycanca hərflərlə: ə ö ğ ş ç ü".into(),
                            phase: None,
                            ts: 0,
                        },
                        Msg {
                            id: "m2".into(),
                            role: "assistant".into(),
                            content: "Salam! **Xoş gəlmisiniz.**\n\nBu cavab mesajıdır. Kod nümunəsi:\n```rust\nfn main() {\n    println!(\"Salam, dünya!\");\n}\n```\n\n- Birinci bənd\n- İkinci bənd\n\nDaha çox mətn üçün uzun sətir yazılır ki, wrap davranışını yoxlamaq mümkün olsun. [Keçid](https://z.ai)".into(),
                            phase: None,
                            ts: 0,
                        },
                        Msg {
                            id: "m3".into(),
                            role: "user".into(),
                            content: "Qısa sual".into(),
                            phase: None,
                            ts: 0,
                        },
                        Msg {
                            id: "m4".into(),
                            role: "assistant".into(),
                            content: "Qısa cavab.".into(),
                            phase: None,
                            ts: 0,
                        },
                    ];
                }
                Ok("thinking") => {
                    use zai_mobile::ui::Msg;
                    app.messages = vec![
                        Msg {
                            id: "m1".into(),
                            role: "user".into(),
                            content: "Çətin sual verdim".into(),
                            phase: None,
                            ts: 0,
                        },
                        Msg {
                            id: "m2".into(),
                            role: "assistant".into(),
                            content: String::new(),
                            phase: Some("Thinking".into()),
                            ts: 0,
                        },
                    ];
                    app.streaming = true;
                }
                _ => {}
            }
            Ok(Box::new(app))
        }),
    )
}
