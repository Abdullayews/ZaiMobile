use crate::md::{self, ACCENT, DIM, TXT};
use crate::ui::*;
use egui::{Align, Color32, Layout, RichText};

impl eframe::App for MyApp {
    fn ui(&mut self, ui: &mut egui::Ui, _frame: &mut eframe::Frame) {
        // Kanalları boşalt
        self.drain_bg();
        self.drain_stream();

        match self.screen {
            Screen::Welcome => self.welcome_ui(ui),
            Screen::Chats => self.chats_ui(ui),
            Screen::Chat => self.chat_ui(ui),
        }
    }
}

impl MyApp {
    // ================== WELCOME ==================
    fn welcome_ui(&mut self, ui: &mut egui::Ui) {
        ui.vertical_centered(|ui| {
            ui.add_space(ui.available_height() * 0.18);
            ui.label(
                RichText::new("Z.ai")
                    .family(egui::FontFamily::Name(std::sync::Arc::from("bold")))
                    .size(44.0)
                    .color(Color32::WHITE),
            );
            ui.add_space(6.0);
            ui.label(RichText::new("GLM süni intellekt — native Android").size(15.0).color(DIM));
            ui.add_space(36.0);

            let w = (ui.available_width() * 0.86).min(340.0);
            ui.set_max_width(w);

            let btn = |ui: &mut egui::Ui, txt: &str| {
                let r = ui.add_sized(
                    [w, 48.0],
                    egui::Button::new(RichText::new(txt).size(16.0).color(Color32::WHITE))
                        .fill(ACCENT)
                        .corner_radius(12.0),
                );
                r
            };

            if self.connecting {
                ui.add(egui::Spinner::new().size(30.0));
                ui.add_space(8.0);
                ui.label(RichText::new("Qoşulur…").size(14.0).color(DIM));
            } else {
                if btn(ui, "Qonaq kimi davam et").clicked() {
                    self.start_guest();
                }
                ui.add_space(14.0);
                if btn(ui, "Token ilə daxil ol").clicked() {
                    self.show_token_help = !self.show_token_help;
                }
                ui.add_space(6.0);

                if self.show_token_help {
                    egui::Frame::new()
                        .fill(INPUT_BG)
                        .corner_radius(10.0)
                        .inner_margin(egui::Margin::same(12))
                        .show(ui, |ui| {
                            ui.set_width(w - 8.0);
                            ui.label(
                                RichText::new("Tokeni haradan tapmaq olar:")
                                    .family(egui::FontFamily::Name(std::sync::Arc::from("bold")))
                                    .color(TXT),
                            );
                            ui.add_space(4.0);
                            ui.label(
                                RichText::new(
                                    "1. Telefonda brauzerdə chat.z.ai açın və hesabınıza daxil olun\n\
                                     2. Brauzer menyusundan 'Saytı araşdır' (Inspect) açın\n\
                                     3. Application → Local Storage → https://chat.z.ai\n\
                                     4. 'token' açarının dəyərini tam kopyalayın və buraya yapışdırın",
                                )
                                .size(13.5)
                                .color(DIM),
                            );
                            ui.add_space(8.0);
                            let mut tok = std::mem::take(&mut self.token_input);
                            ui.add(
                                egui::TextEdit::multiline(&mut tok)
                                    .hint_text("eyJhbGciOiJFUzI1NiIs…")
                                    .desired_rows(2)
                                    .desired_width(w - 16.0)
                                    .font(egui::TextStyle::Monospace)
                                    .text_color(TXT),
                            );
                            self.token_input = tok;
                            ui.add_space(6.0);
                            if ui
                                .add_sized(
                                    [w - 16.0, 38.0],
                                    egui::Button::new(RichText::new("Daxil ol").size(15.0))
                                        .fill(ACCENT)
                                        .corner_radius(10.0),
                                )
                                .clicked()
                            {
                                self.start_token_login();
                            }
                        });
                }
            }

            if let Some(e) = &self.error {
                ui.add_space(14.0);
                error_banner(ui, e);
            }
        });
    }

    // ================== CHATS ==================
    fn chats_ui(&mut self, ui: &mut egui::Ui) {
        egui::Panel::top("chats_top").frame(top_panel_frame()).show(ui, |ui| {
            ui.horizontal_centered(|ui| {
                ui.label(
                    RichText::new("Söhbətlər")
                        .family(egui::FontFamily::Name(std::sync::Arc::from("bold")))
                        .size(20.0)
                        .color(Color32::WHITE),
                );
                if self.client.as_ref().map(|c| c.is_guest).unwrap_or(false) {
                    ui.label(RichText::new("(qonaq)").size(13.0).color(DIM));
                }
                ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                    if icon_button(ui, "⟳", "Yenilə").clicked() {
                        self.refresh_chats();
                    }
                    if icon_button(ui, "＋", "Yeni söhbət").clicked() {
                        self.start_new_chat();
                    }
                });
            });
        });

        egui::CentralPanel::default()
            .frame(egui::Frame::new().fill(BG))
            .show(ui, |ui| {
                if self.list_loading && self.chat_list.is_empty() {
                    ui.centered_and_justified(|ui| {
                        ui.add(egui::Spinner::new().size(28.0));
                    });
                    return;
                }
                if self.chat_list.is_empty() {
                    ui.vertical_centered(|ui| {
                        ui.add_space(60.0);
                        ui.label(RichText::new("Hələ söhbət yoxdur").size(15.0).color(DIM));
                        ui.add_space(12.0);
                        if ui
                            .add_sized(
                                [200.0, 44.0],
                                egui::Button::new(RichText::new("Yeni söhbət başlat").size(15.0))
                                    .fill(ACCENT)
                                    .corner_radius(10.0),
                            )
                            .clicked()
                        {
                            self.start_new_chat();
                        }
                    });
                    return;
                }
                egui::ScrollArea::vertical().auto_shrink([false, false]).show(ui, |ui| {
                    let ids: Vec<String> = self.chat_list.iter().map(|c| c.id.clone()).collect();
                    for id in ids {
                        let c = self.chat_list.iter().find(|c| c.id == id).unwrap().clone();
                        let resp = egui::Frame::new()
                            .fill(PANEL)
                            .corner_radius(10.0)
                            .inner_margin(egui::Margin::same(12))
                            .outer_margin(egui::Margin {
                                left: 8,
                                right: 8,
                                top: 3,
                                bottom: 3,
                            })
                            .stroke(egui::Stroke::new(1.0, BORDER))
                            .show(ui, |ui| {
                                ui.set_width(ui.available_width());
                                ui.horizontal(|ui| {
                                    ui.label(
                                        RichText::new("💬").size(18.0),
                                    );
                                    ui.vertical(|ui| {
                                        ui.label(
                                            RichText::new(&c.title)
                                                .size(15.5)
                                                .color(TXT)
                                                .family(egui::FontFamily::Name(
                                                    std::sync::Arc::from("bold"),
                                                )),
                                        );
                                        ui.label(
                                            RichText::new(time_ago(c.updated_at))
                                                .size(12.5)
                                                .color(DIM),
                                        );
                                    });
                                })
                                .response
                                .interact(egui::Sense::click())
                            });
                        if resp.response.clicked() {
                            self.open_chat(c.id.clone());
                        }
                    }
                });
            });

        if let Some(e) = self.error.clone() {
            egui::Panel::bottom("err").frame(top_panel_frame()).show(ui, |ui| {
                error_banner(ui, &e);
            });
        }
    }

    // ================== CHAT ==================
    fn chat_ui(&mut self, ui: &mut egui::Ui) {
        let title = self.chat_title.clone();
        let model_name = self
            .models
            .get(self.model_idx)
            .map(|(_, n)| n.clone())
            .unwrap_or_else(|| "GLM".into());
        let streaming = self.streaming;

        // ---- Üst panel ----
        egui::Panel::top("chat_top").frame(top_panel_frame()).show(ui, |ui| {
            ui.horizontal_centered(|ui| {
                if icon_button(ui, "←", "Geri").clicked() {
                    self.screen = Screen::Chats;
                    self.refresh_chats();
                }
                ui.label(
                    RichText::new(truncate(&title, 24))
                        .size(16.0)
                        .color(Color32::WHITE)
                        .family(egui::FontFamily::Name(std::sync::Arc::from("bold"))),
                );
                ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                    if icon_button(ui, "＋", "Yeni söhbət").clicked() {
                        self.start_new_chat();
                    }
                    // model seçici
                    let mresp = ui.add(
                        egui::Button::new(
                            RichText::new(format!("⌄ {}", model_name))
                                .size(13.5)
                                .color(DIM),
                        )
                        .fill(Color32::TRANSPARENT),
                    );
                    let mresp = mresp.on_hover_text("Modeli dəyiş");
                    if mresp.clicked() {
                        self.model_idx = (self.model_idx + 1) % self.models.len().max(1);
                        self.save_state();
                    }
                });
            });
        });

        // ---- Alt panel (giriş) ----
        egui::Panel::bottom("chat_bottom")
            .frame(top_panel_frame())
            .show(ui, |ui| {
                ui.horizontal_centered(|ui| {
                    if let Some(e) = &self.error {
                        error_banner(ui, e);
                    }
                });
                ui.add_space(4.0);
                ui.horizontal(|ui| {
                    let mut input = std::mem::take(&mut self.input);
                    let rows = input.lines().count().clamp(1, 5);
                    let edit = egui::TextEdit::multiline(&mut input)
                        .hint_text("Mesaj yazın…")
                        .desired_rows(rows)
                        .desired_width(ui.available_width() - 92.0)
                        .text_color(TXT);
                    let resp = ui.add_sized([ui.available_width() - 92.0, (rows as f32) * 22.0 + 14.0], edit);
                    // Enter = göndər (desktop), Shift+Enter = yeni sətir
                    let send_by_enter = resp.lost_focus()
                        && ui.input(|i| i.key_pressed(egui::Key::Enter))
                        && !ui.input(|i| i.modifiers.shift);
                    self.input = input;

                    if streaming {
                        if ui
                            .add_sized(
                                [76.0, 42.0],
                                egui::Button::new(
                                    RichText::new("■ Stop").size(14.0).color(Color32::WHITE),
                                )
                                .fill(Color32::from_rgb(190, 60, 60))
                                .corner_radius(10.0),
                            )
                            .clicked()
                        {
                            self.stop_generation();
                        }
                    } else {
                        let enabled = !self.input.trim().is_empty();
                        let btn = egui::Button::new(
                            RichText::new("➤").size(18.0).color(Color32::WHITE),
                        )
                        .corner_radius(10.0);
                        let btn = if enabled { btn.fill(ACCENT) } else { btn.fill(Color32::from_rgb(40, 48, 64)) };
                        if ui
                            .add_sized([76.0, 42.0], btn)
                            .clicked() && enabled
                        {
                            let ctx = ui.ctx().clone();
                            self.send_message(ctx);
                        }
                    }
                    if send_by_enter && !streaming && !self.input.trim().is_empty() {
                        let ctx = ui.ctx().clone();
                        self.send_message(ctx);
                    }
                });
            });

        // ---- Mərkəz (mesajlar) ----
        egui::CentralPanel::default()
            .frame(egui::Frame::new().fill(BG))
            .show(ui, |ui| {
                if self.messages.is_empty() {
                    ui.vertical_centered(|ui| {
                        ui.add_space(ui.available_height() * 0.3);
                        ui.label(
                            RichText::new("Z")
                                .size(52.0)
                                .color(Color32::from_rgb(36, 46, 66))
                                .family(egui::FontFamily::Name(std::sync::Arc::from("bold"))),
                        );
                        ui.label(RichText::new("Sualınızı yazın…").size(14.0).color(DIM));
                    });
                    return;
                }
                egui::ScrollArea::vertical()
                    .auto_shrink([false, false])
                    .stick_to_bottom(true)
                    .show(ui, |ui| {
                        ui.add_space(6.0);
                        let n = self.messages.len();
                        for i in 0..n {
                            let m = self.messages[i].clone();
                            message_bubble(ui, &m, i + 1 == n && self.streaming);
                        }
                        ui.add_space(6.0);
                    });
            });
    }

    fn start_new_chat(&mut self) {
        self.chat_id = None;
        self.chat_title = "Yeni söhbət".into();
        self.messages.clear();
        self.streaming = false;
        self.stop_flag = None;
        self.error = None;
        self.screen = Screen::Chat;
    }
}

// ================== Köməkçilər ==================

fn top_panel_frame() -> egui::Frame {
    egui::Frame::new()
        .fill(PANEL)
        .inner_margin(egui::Margin {
            left: 10,
            right: 10,
            top: 8,
            bottom: 8,
        })
        .stroke(egui::Stroke::new(1.0, BORDER))
}

fn message_bubble(ui: &mut egui::Ui, m: &Msg, is_last_streaming: bool) {
    let is_user = m.role == "user";
    let max_w = (ui.available_width() - 24.0).min(560.0);
    ui.add_space(3.0);
    if is_user {
        ui.with_layout(Layout::right_to_left(Align::TOP), |ui| {
            egui::Frame::new()
                .fill(BUBBLE_USER)
                .corner_radius(14.0)
                .inner_margin(egui::Margin::same(12))
                .show(ui, |ui| {
                    ui.set_max_width(max_w);
                    ui.label(RichText::new(&m.content).size(15.5).color(Color32::WHITE));
                });
        });
    } else {
        ui.with_layout(Layout::left_to_right(Align::TOP), |ui| {
            // avatar
            ui.label(
                RichText::new("Z")
                    .size(13.0)
                    .color(Color32::WHITE)
                    .family(egui::FontFamily::Name(std::sync::Arc::from("bold"))),
            )
            .on_hover_text("GLM");
            ui.add_space(2.0);
            egui::Frame::new()
                .fill(BUBBLE_AI)
                .corner_radius(14.0)
                .inner_margin(egui::Margin::same(12))
                .stroke(egui::Stroke::new(1.0, BORDER))
                .show(ui, |ui| {
                    ui.set_max_width(max_w);
                    if m.content.is_empty() {
                        if let Some(p) = &m.phase {
                            ui.horizontal(|ui| {
                                ui.add(egui::Spinner::new().size(14.0));
                                ui.label(RichText::new(p).size(13.5).color(DIM));
                            });
                        } else if is_last_streaming {
                            ui.horizontal(|ui| {
                                ui.add(egui::Spinner::new().size(14.0));
                                ui.label(RichText::new("Gözləyin…").size(13.5).color(DIM));
                            });
                        }
                    } else {
                        md::render(ui, &m.content);
                        if let Some(p) = &m.phase {
                            ui.add_space(2.0);
                            ui.horizontal(|ui| {
                                ui.add(egui::Spinner::new().size(12.0));
                                ui.label(RichText::new(p).size(12.5).color(DIM));
                            });
                        }
                    }
                });
        });
        // kopyalama
        if !m.content.is_empty() {
            ui.with_layout(Layout::right_to_left(Align::TOP), |ui| {
                ui.add_space(8.0);
                if md::copy_button(ui, &m.content) {
                    // kopyalandı
                }
            });
        }
    }
}

fn icon_button(ui: &mut egui::Ui, icon: &str, tooltip: &str) -> egui::Response {
    let r = ui.add(
        egui::Button::new(RichText::new(icon).size(18.0).color(TXT))
            .fill(Color32::TRANSPARENT),
    );
    r.on_hover_text(tooltip)
}

fn error_banner(ui: &mut egui::Ui, msg: &str) {
    egui::Frame::new()
        .fill(Color32::from_rgb(64, 25, 28))
        .corner_radius(8.0)
        .inner_margin(egui::Margin::same(10))
        .show(ui, |ui| {
            ui.set_width(ui.available_width());
            ui.label(RichText::new(format!("⚠ {}", msg)).size(13.5).color(Color32::from_rgb(255, 180, 180)));
        });
}

fn truncate(s: &str, max: usize) -> String {
    if s.chars().count() <= max {
        s.to_string()
    } else {
        let t: String = s.chars().take(max).collect();
        format!("{}…", t)
    }
}

fn time_ago(ts: i64) -> String {
    let now = crate::api::now_ms() as i64 / 1000;
    let d = now - ts;
    if d < 60 {
        "indi".into()
    } else if d < 3600 {
        format!("{} dəq əvvəl", d / 60)
    } else if d < 86400 {
        format!("{} saat əvvəl", d / 3600)
    } else {
        format!("{} gün əvvəl", d / 86400)
    }
}
