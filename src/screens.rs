//! chat.z.ai veb UI-nun piksel-dəqiq native replikası (işıq tema).
//! Tək ekran arxitektura: nav + məzmun + giriş kapsulu; bütün digər
//! elementlər (drawer, sign-in, model popup, API menyu) overlay-dir.

use crate::api::ChatSummary;
use crate::icons;
use crate::md;
use crate::theme::*;
use crate::ui::*;
use egui::{Align, Align2, Color32, CornerRadius, Id, Layout, Margin, RichText, Sense, TextureHandle};

impl eframe::App for MyApp {
    fn ui(&mut self, ui: &mut egui::Ui, _frame: &mut eframe::Frame) {
        // Kanalları boşalt
        self.drain_bg();
        self.drain_stream();

        // Axın aktiv ikən davamlı repaint — animasiya + watchdog
        if self.streaming {
            ui.ctx()
                .request_repaint_after(std::time::Duration::from_millis(300));
            self.stream_watchdog();
        }

        // Android back: overlay-ləri bağla, heç biri açıq deyilsə arxa plana
        if ui.input(|i| i.key_pressed(egui::Key::BrowserBack)) {
            if self.model_popup {
                self.model_popup = false;
            } else if self.api_menu {
                self.api_menu = false;
            } else if self.signin_open {
                self.signin_open = false;
                self.signin_email = false;
            } else if self.drawer_open {
                self.drawer_open = false;
            } else {
                crate::platform::move_task_to_back();
            }
        }

        // ---- Əsas layout: nav (üst) → giriş (alt) → mərkəz ----
        self.nav_bar(ui);
        self.input_area(ui);

        egui::CentralPanel::default()
            .frame(egui::Frame::new().fill(BG))
            .show(ui, |ui| {
                if self.messages.is_empty() {
                    self.empty_state(ui);
                } else {
                    self.messages_area(ui);
                }
            });

        // ---- Overlay-lər (üstə çəkilir) ----
        if self.drawer_open {
            self.drawer_ui(ui);
        }
        if self.signin_open {
            self.signin_ui(ui);
        }
        if self.model_popup {
            self.model_popup_ui(ui);
        }
        if self.api_menu {
            self.api_menu_ui(ui);
        }
    }
}

impl MyApp {
    // ================== NAV BAR (veb: 48px, #F8F8F8) ==================
    fn nav_bar(&mut self, ui: &mut egui::Ui) {
        let is_guest = self.client.as_ref().map(|c| c.is_guest).unwrap_or(true);
        let user_name = self
            .client
            .as_ref()
            .map(|c| c.user_name.clone())
            .unwrap_or_default();
        let connecting = self.connecting && self.client.is_none();

        egui::Panel::top("nav")
            .resizable(false)
            .show_separator_line(false)
            .exact_size(48.0)
            .frame(
                egui::Frame::new()
                    .fill(NAV_BG)
                    .inner_margin(Margin { left: 6, right: 10, top: 0, bottom: 0 }),
            )
            .show(ui, |ui| {
                let bar_h = 48.0;
                let (rect, _) = ui.allocate_exact_size(
                    egui::vec2(ui.available_width(), bar_h),
                    Sense::hover(),
                );
                // alt sərhəd (veb: 1px rgba(13,13,13,0.1))
                ui.painter().line_segment(
                    [rect.left_bottom(), rect.right_bottom()],
                    egui::Stroke::new(1.0, border_alpha()),
                );
                let mut row = ui.new_child(
                    egui::UiBuilder::new()
                        .max_rect(rect.shrink2(egui::vec2(4.0, 0.0)))
                        .layout(Layout::left_to_right(Align::Center)),
                );
                let rw = row.available_width();
                region(&mut row, egui::vec2(rw, 48.0), Layout::left_to_right(Align::Center), |ui| {
                    // Sol: tarixçə + yeni söhbət
                    if icons::draw_clickable(ui, &self.icons.panel_left, 18.0, 40.0, TEXT).clicked() {
                        self.drawer_open = true;
                        if self.chat_list.is_empty() && !self.list_loading {
                            self.refresh_chats();
                        }
                    }
                    if icons::draw_clickable(ui, &self.icons.square_pen, 18.0, 40.0, TEXT).clicked() {
                        self.start_new_chat();
                    }
                    if connecting {
                        ui.add_space(6.0);
                        ui.add(egui::Spinner::new().size(14.0).color(TEXT_3));
                    }
                    // Sağ: API + Sign in / avatar
                    let rw2 = ui.available_width();
                    region(ui, egui::vec2(rw2, 48.0), Layout::right_to_left(Align::Center), |ui| {
                        if is_guest {
                            if dark_pill(ui, "Sign in", 32.0, 14.0).clicked() {
                                self.signin_open = true;
                                self.signin_email = false;
                            }
                        } else {
                            // istifadəçi avatarı (tünd dairə + baş hərf)
                            let initial = user_name
                                .chars()
                                .next()
                                .map(|c| c.to_uppercase().to_string())
                                .unwrap_or_else(|| "U".into());
                            let (r, resp) =
                                ui.allocate_exact_size(egui::vec2(34.0, 34.0), Sense::click());
                            ui.painter().rect_filled(r, CornerRadius::same(16), BTN_DARK);
                            avatar_text(ui, r, &initial, 14.0);
                            if resp.clicked() {
                                self.drawer_open = true;
                                if self.chat_list.is_empty() && !self.list_loading {
                                    self.refresh_chats();
                                }
                            }
                        }
                        // API açılan menyu düyməsi
                        let aw = ui.available_width();
                        let api_clicked = region(ui, egui::vec2(aw, 48.0), Layout::left_to_right(Align::Center), |ui| {
                            text_btn(ui, "API", 14.0, TEXT, true).clicked()
                                || icons::draw_clickable(ui, &self.icons.chevron_down, 12.0, 26.0, TEXT).clicked()
                        });
                        if api_clicked {
                            self.api_menu = !self.api_menu;
                        }
                    });
                });
            });
    }

    // ================== BOŞ VƏZİYYƏT (landing) ==================
    fn empty_state(&mut self, ui: &mut egui::Ui) {
        // Pulldown göstəricisi (yalnız gesture zamanı; fon yükləməsində gizlət — veb-də yoxdur)
        pull_indicator(ui, self.pull_dist, self.pull_armed, false);
        let out = egui::ScrollArea::vertical()
            .auto_shrink([false, false])
            .show(ui, |ui| {
                egui::Frame::new()
                    .inner_margin(Margin { left: 24, right: 24, top: 76, bottom: 16 })
                    .show(ui, |ui| {
                        let w = content_width(ui);
                        ui.set_max_width(w);
                        // Başlıq — veb: Crimson Text serif 24px bold
                        ui.label(
                            RichText::new("More than coding...")
                                .family(serif_family())
                                .size(24.0)
                                .color(TEXT),
                        );
                        ui.add_space(20.0);
                        self.cards_grid(ui);
                    });
            });
        let at_top = out.state.clamped_offset().y <= 0.5;
        if self.pull_refresh_step(ui, at_top) {
            self.refresh_chats();
        }
    }

    /// Veb-dəki 5 təklif kartı: 2 sütunlu şəbəkə, CDN şəkilləri
    fn cards_grid(&mut self, ui: &mut egui::Ui) {
        let cards: [(&str, &TextureHandle, &str); 5] = [
            ("Full-Stack", &self.cards.fullstack, "Help me build a full-stack web application:\n"),
            ("Writing", &self.cards.writing, "Write a polished, engaging article about:\n"),
            ("Data Insight", &self.cards.data, "Analyze this data and highlight key insights:\n"),
            ("PPT", &self.cards.slides, "Create a professional presentation about:\n"),
            ("Web Site", &self.cards.web, "Build a modern, responsive website for:\n"),
        ];
        let gap = 12.0;
        let col_w = (ui.available_width() - gap) / 2.0;
        // veb: konteyner 170, şəkil 144 → şəkil = konteynerin 84.7%-i
        let inner_w = col_w * (144.0 / 170.0);
        let img_h = inner_w * (119.0 / 144.0);
        let mut clicked_prompt: Option<String> = None;
        for chunk in cards.chunks(2) {
            ui.horizontal(|ui| {
                for (label, tex, prompt) in chunk {
                    let resp = card(ui, label, tex, col_w, img_h);
                    if resp.clicked() {
                        clicked_prompt = Some(prompt.to_string());
                    }
                    if !std::ptr::eq(*label, chunk[chunk.len() - 1].0) {
                        ui.add_space(gap);
                    }
                }
            });
            ui.add_space(gap);
        }
        if let Some(p) = clicked_prompt {
            self.input = p;
        }
    }

    // ================== MESAJLAR ==================
    fn messages_area(&mut self, ui: &mut egui::Ui) {
        let streaming = self.streaming;
        let has_error = self.error.is_some();
        let regen_prompt = self.regen_prompt.clone();
        let mut regen_request: Option<String> = None;

        pull_indicator(ui, self.pull_dist, self.pull_armed, self.list_loading && !streaming);
        let out = egui::ScrollArea::vertical()
            .auto_shrink([false, false])
            .stick_to_bottom(true)
            .show(ui, |ui| {
                egui::Frame::new()
                    .inner_margin(Margin { left: 24, right: 24, top: 16, bottom: 8 })
                    .show(ui, |ui| {
                        let w = content_width(ui);
                        ui.set_max_width(w);
                        let n = self.messages.len();
                        for i in 0..n {
                            let m = self.messages[i].clone();
                            let is_last = i + 1 == n;
                            if m.role == "user" {
                                user_bubble(ui, &m, &self.icons.copy);
                            } else {
                                let r = assistant_msg(
                                    ui,
                                    &m,
                                    is_last && streaming,
                                    is_last && has_error && !streaming,
                                    regen_prompt.clone(),
                                    &self.icons.copy,
                                );
                                if r.is_some() {
                                    regen_request = r;
                                }
                            }
                        }
                    });
            });
        let at_top = out.state.clamped_offset().y <= 0.5;
        if self.pull_refresh_step(ui, at_top) && !streaming {
            if let Some(id) = self.chat_id.clone() {
                self.open_chat(id);
            }
        }
        // Regenerate: son cütü çıxar və yenidən göndər
        if let Some(p) = regen_request {
            self.regen_prompt = None;
            self.error = None;
            self.remove_last_pair();
            self.dispatch(p, true);
        }
    }

    // ================== GİRİŞ KAPSULU (veb: ağ, r24, kölgəli) ==================
    // ================== GİRİŞ KAPSULU (veb: ağ, r24, kölgəli) ==================
    fn input_area(&mut self, ui: &mut egui::Ui) {
        let streaming = self.streaming;
        let model_name = self
            .models
            .get(self.model_idx)
            .map(|(_, n)| n.clone())
            .unwrap_or_else(|| "GLM-5.3-Flash".into());

        // default_size: ilk kadrda düzgün hündürlük (inversiya rect-in qarşısını alır);
        // size_range: hər hansı glitç zamanı panelin partlamasının qarşını alır
        egui::Panel::bottom("input")
            .resizable(false)
            .show_separator_line(false)
            .default_size(176.0)
            .size_range(96.0..=400.0)
            .frame(
                egui::Frame::new()
                    .fill(BG)
                    .inner_margin(Margin { left: 16, right: 16, top: 4, bottom: 6 }),
            )
            .show(ui, |ui| {
                // Xəta/info banneri kapsulun üstündə — amma xəta mesajın içində
                // "No response" kimi göstərilirsə dublikat olmasın deyə gizlət (veb davranışı)
                let err_in_msg = !self.messages.is_empty()
                    && self
                        .messages
                        .last()
                        .map(|m| m.role == "assistant" && m.content.is_empty())
                        .unwrap_or(false);
                if let Some(e) = self.error.clone() {
                    if !err_in_msg {
                        error_banner(ui, &e);
                        ui.add_space(6.0);
                    }
                } else if let Some(i) = self.info.clone() {
                    info_banner(ui, &i);
                    ui.add_space(6.0);
                }

                // ---- Kapsul ----
                egui::Frame::new()
                    .fill(SURFACE)
                    .corner_radius(CAPSULE_RADIUS)
                    .stroke(border_stroke())
                    .shadow(capsule_shadow())
                    .inner_margin(Margin { left: 16, right: 12, top: 12, bottom: 10 })
                    .show(ui, |ui| {
                        ui.set_width(ui.available_width());
                        // Mətn sahəsi (egui multiline hündürlükdə greedy-dir —
                        // add_sized ilə sətir sayına görə məhdudlaşdır)
                        let mut input = std::mem::take(&mut self.input);
                        let rows = input.lines().count().clamp(1, 8);
                        let edit_h = (rows as f32) * 22.0 + 14.0;
                        let edit = egui::TextEdit::multiline(&mut input)
                            .hint_text("Send a Message")
                            .desired_rows(rows)
                            .desired_width(f32::INFINITY)
                            .frame(egui::Frame::NONE)
                            .text_color(TEXT)
                            .font(egui::TextStyle::Body);
                        let resp = ui.add_sized([ui.available_width(), edit_h], edit);
                        // Enter = göndər (desktop), Shift+Enter = yeni sətir
                        let send_by_enter = resp.lost_focus()
                            && ui.input(|i| i.key_pressed(egui::Key::Enter))
                            && !ui.input(|i| i.modifiers.shift);
                        self.input = input;
                        let can_send = !self.input.trim().is_empty() && self.client.is_some();
                        let mut send_now = send_by_enter && can_send && !streaming;

                        ui.add_space(6.0);
                        // ---- Nəzarət sətri (dəqiq 30px — region genişlənməsin) ----
                        let row_w = ui.available_width();
                        let (row_rect, _) =
                            ui.allocate_exact_size(egui::vec2(row_w, 30.0), Sense::hover());
                        let mut row = ui.new_child(
                            egui::UiBuilder::new()
                                .max_rect(row_rect)
                                .layout(Layout::left_to_right(Align::Center)),
                        );
                        // + dairəsi (əlavə)
                        if circle_icon_btn(&mut row, &self.icons.plus, 30.0, TEXT_3).clicked() {
                            self.info = Some("Fayl əlavə etmək növbəti versiyada olacaq".into());
                        }
                        // sağ tərəf: model + göndər
                        let right_w = row.available_width();
                        let (right_rect, _) =
                            row.allocate_exact_size(egui::vec2(right_w, 30.0), Sense::hover());
                        let mut right = row.new_child(
                            egui::UiBuilder::new()
                                .max_rect(right_rect)
                                .layout(Layout::right_to_left(Align::Center)),
                        );
                        if streaming {
                            if circle_icon_btn_solid(
                                &mut right,
                                &self.icons.stop,
                                30.0,
                                BTN_DARK,
                                Color32::WHITE,
                            )
                            .clicked()
                            {
                                self.stop_generation();
                            }
                        } else if can_send {
                            if circle_icon_btn_solid(
                                &mut right,
                                &self.icons.arrow_up,
                                30.0,
                                BTN_DARK,
                                Color32::WHITE,
                            )
                            .clicked()
                            {
                                send_now = true;
                            }
                        } else {
                            circle_icon_btn_solid(
                                &mut right,
                                &self.icons.arrow_up,
                                30.0,
                                BTN_DISABLED,
                                TEXT_2,
                            );
                        }
                        right.add_space(6.0);
                        let model_clicked = text_btn(&mut right, &model_name, 14.0, TEXT, false)
                            .clicked()
                            || icons::draw_clickable(
                                &mut right,
                                &self.icons.chevron_down,
                                12.0,
                                26.0,
                                TEXT_3,
                            )
                            .clicked();
                        if model_clicked {
                            self.model_popup = true;
                        }
                        if send_now {
                            let ctx = ui.ctx().clone();
                            self.send_message(ctx);
                        }
                    });

                ui.add_space(6.0);
                // ---- Footer (dəqiq 28px region) ----
                let fw = ui.available_width();
                let (f_rect, _) = ui.allocate_exact_size(egui::vec2(fw, 28.0), Sense::hover());
                let mut f = ui.new_child(
                    egui::UiBuilder::new()
                        .max_rect(f_rect)
                        .layout(Layout::top_down(Align::Center)),
                );
                f.label(
                    RichText::new("Generated by AI. For reference only.")
                        .size(10.0)
                        .color(TEXT_3),
                );
                f.label(
                    RichText::new("Terms of Service          Privacy Policy")
                        .size(10.0)
                        .color(TEXT_3),
                );
            });
    }

    // ================== TARIXÇƏ DRAWER ==================
    fn drawer_ui(&mut self, ui: &mut egui::Ui) {
        let screen = ui.ctx().input(|i| i.viewport_rect());
        let w = (screen.width() * 0.86).min(336.0);
        let is_guest = self.client.as_ref().map(|c| c.is_guest).unwrap_or(true);
        let user_name = self
            .client
            .as_ref()
            .map(|c| c.user_name.clone())
            .unwrap_or_default();
        let icons = IconsRef {
            x: self.icons.x.clone(),
            square_pen: self.icons.square_pen.clone(),
        };
        let mut open_chat_id: Option<String> = None;
        let mut new_chat = false;
        let mut sign_out = false;
        let mut go_signin = false;
        let mut pull_state = (self.pull_dist, self.pull_armed);
        let mut pull_trigger = false;
        let search_snapshot = self.search_input.clone();

        let id = Id::unique("drawer_modal");
        let modal = egui::Modal::new(id)
            .area(egui::Modal::default_area(id).anchor(Align2::LEFT_TOP, egui::vec2(0.0, 0.0)))
            .backdrop_color(Color32::from_black_alpha(90))
            .frame(egui::Frame::new().fill(SURFACE).inner_margin(Margin::same(0)));
        let resp = modal.show(ui.ctx(), |ui| {
            ui.set_width(w);
            ui.set_min_height(screen.height());
            drawer_content(
                ui,
                DrawerCtx {
                    icons: &icons,
                    chats: &self.chat_list,
                    active_id: self.chat_id.clone(),
                    loading: self.list_loading,
                    search: &search_snapshot,
                    is_guest,
                    user_name: &user_name,
                },
                &mut self.search_input,
                &mut self.drawer_open,
                &mut open_chat_id,
                &mut new_chat,
                &mut sign_out,
                &mut go_signin,
                &mut pull_state,
                &mut pull_trigger,
            );
        });
        self.pull_dist = pull_state.0;
        self.pull_armed = pull_state.1;
        if resp.should_close() {
            self.drawer_open = false;
        }
        if new_chat {
            self.start_new_chat();
            self.drawer_open = false;
        }
        if let Some(id) = open_chat_id {
            self.open_chat(id);
            self.drawer_open = false;
        }
        if go_signin {
            self.drawer_open = false;
            self.signin_open = true;
            self.signin_email = false;
        }
        if sign_out {
            self.sign_out();
        }
        if pull_trigger {
            self.refresh_chats();
        }
    }

    // ================== SIGN-IN MODAL ==================
    fn signin_ui(&mut self, ui: &mut egui::Ui) {
        let screen = ui.ctx().input(|i| i.viewport_rect());
        let w = (screen.width() - 48.0).min(400.0);
        let email_mode = self.signin_email;
        let google = self.icons.google.clone();
        let mail = self.icons.mail.clone();
        let github = self.icons.github.clone();
        let arrow_left = self.icons.arrow_left.clone();

        let id = Id::unique("signin_modal");
        let modal = egui::Modal::new(id)
            .backdrop_color(Color32::from_black_alpha(100))
            .frame(
                egui::Frame::new()
                    .fill(SURFACE)
                    .corner_radius(20.0)
                    .shadow(popup_shadow()),
            );
        let resp = modal.show(ui.ctx(), |ui| {
            ui.set_width(w);
            egui::Frame::new()
                .inner_margin(Margin { left: 24, right: 24, top: 28, bottom: 20 })
                .show(ui, |ui| {
                    if email_mode {
                        self.signin_email_ui(ui, &arrow_left);
                    } else {
                        self.signin_main_ui(ui, &google, &mail, &github);
                    }
                });
        });
        if resp.should_close() {
            self.signin_open = false;
            self.signin_email = false;
        }
    }

    fn signin_main_ui(&mut self, ui: &mut egui::Ui, google: &TextureHandle, mail: &TextureHandle, github: &TextureHandle) {
        // Logo: tünd kvadrat + ağ Z (veb: 64px, r14)
        full_row(ui, 130.0, Layout::top_down(Align::Center), |ui| {
            let (r, _) = ui.allocate_exact_size(egui::vec2(64.0, 64.0), Sense::hover());
            ui.painter().rect_filled(r, CornerRadius::same(14), BTN_DARK);
            avatar_text(ui, r, "Z", 38.0);
            ui.add_space(16.0);
            ui.label(RichText::new("Welcome to Z.ai").size(22.0).color(TEXT).family(bold_family()));
            ui.add_space(6.0);
            ui.label(RichText::new("Unlock all features by logging in").size(14.0).color(TEXT_2));
        });
        ui.add_space(24.0);

        // Google (tünd)
        if full_btn(ui, "Continue with Google", Some(google), Color32::WHITE, true, 48.0, 24.0)
            .clicked()
        {
            self.error = Some("Google ilə daxilolma hələ mövcud deyil — Email seçin".into());
        }
        ui.add_space(14.0);
        divider_with_text(ui, "or");
        ui.add_space(14.0);
        // Email
        if full_btn(ui, "Continue with Email", Some(mail), TEXT, false, 48.0, 24.0).clicked() {
            self.signin_email = true;
            self.error = None;
        }
        ui.add_space(10.0);
        // Github
        if full_btn(ui, "Continue with Github", Some(github), TEXT, false, 48.0, 24.0).clicked() {
            self.error = Some("Github ilə daxilolma hələ mövcud deyil — Email seçin".into());
        }
        ui.add_space(14.0);
        // Skip
        if full_btn(ui, "Skip for now", None, TEXT, false, 48.0, 24.0).clicked() {
            self.signin_open = false;
            self.signin_email = false;
            if self.client.is_none() {
                self.start_guest();
            }
        }

        if let Some(e) = self.error.clone() {
            ui.add_space(14.0);
            error_banner(ui, &e);
        }
        ui.add_space(16.0);
        full_row(ui, 44.0, Layout::top_down(Align::Center), |ui| {
            ui.label(RichText::new("Switch to China site login").size(12.0).color(TEXT_2));
            ui.add_space(10.0);
            ui.label(
                RichText::new("Terms of Service          Privacy Policy")
                    .size(10.0)
                    .color(TEXT_2),
            );
        });
    }

    /// Email ekranı: veb görünümlü form + işləyən token daxilolma
    fn signin_email_ui(&mut self, ui: &mut egui::Ui, arrow_left: &TextureHandle) {
        full_row(ui, 36.0, Layout::left_to_right(Align::Center), |ui| {
            if icons::draw_clickable(ui, arrow_left, 18.0, 36.0, TEXT).clicked() {
                self.signin_email = false;
            }
            ui.label(RichText::new("Sign in").size(17.0).color(TEXT).family(bold_family()));
        });
        ui.add_space(20.0);

        // Email
        field(ui, "Enter Your Email", false, &mut self.email_input);
        ui.add_space(12.0);
        // Parol
        field(ui, "Enter Your Password", true, &mut self.pass_input);
        ui.add_space(8.0);
        full_row(ui, 26.0, Layout::right_to_left(Align::Center), |ui| {
            if text_btn(ui, "Forgot Password?", 13.0, TEXT_2, false).clicked() {
                self.error = Some("Parol bərpası chat.z.ai brauzerdən aparılır".into());
            }
        });
        ui.add_space(14.0);
        if full_btn(ui, "Sign in", None, Color32::WHITE, true, 46.0, 8.0).clicked() {
            self.error = Some(
                "Email+parol daxilolma hələ mövcud deyil — aşağıdakı token üsulunu istifadə edin"
                    .into(),
            );
        }
        ui.add_space(16.0);
        divider_with_text(ui, "or");
        ui.add_space(16.0);

        // ---- Token bölməsi (işləyən metod) ----
        ui.label(
            RichText::new("Brauzer tokeni ilə daxil ol")
                .size(14.0)
                .color(TEXT)
                .family(bold_family()),
        );
        ui.add_space(4.0);
        ui.label(
            RichText::new(
                "1. Brauzerdə chat.z.ai açın və daxil olun\n\
                 2. Saytı araşdır → Application → Local Storage\n\
                 3. 'token' açarının dəyərini kopyalayın",
            )
            .size(12.5)
            .color(TEXT_2),
        );
        ui.add_space(8.0);
        let mut tok = std::mem::take(&mut self.token_input);
        ui.add_sized(
            [ui.available_width(), 78.0],
            egui::TextEdit::multiline(&mut tok)
                .hint_text("eyJhbGciOiJFUzI1NiIs…")
                .desired_rows(3)
                .desired_width(f32::INFINITY)
                .font(egui::TextStyle::Monospace)
                .text_color(TEXT),
        );
        self.token_input = tok;
        ui.add_space(8.0);
        let pw = ui.available_width();
        region(ui, egui::vec2(pw, 38.0), Layout::left_to_right(Align::Center), |ui| {
            if outline_pill(ui, "📋 Paste", 38.0, 14.0).clicked() {
                match crate::platform::clipboard_get() {
                    Ok(t) => {
                        let t = t.trim().trim_matches('"').to_string();
                        if t.len() >= 40 {
                            self.token_input = t;
                            self.error = None;
                        } else {
                            self.error = Some(
                                "Buferdə token görünmür — əvvəlcə chat.z.ai-dən kopyalayın".into(),
                            );
                        }
                    }
                    Err(e) => self.error = Some(e),
                }
            }
            ui.add_space(8.0);
            if outline_pill(ui, "Clear", 38.0, 14.0).clicked() {
                self.token_input.clear();
            }
        });
        ui.add_space(10.0);
        let connecting = self.connecting;
        if full_btn(
            ui,
            if connecting { "Connecting…" } else { "Sign in with Token" },
            None,
            Color32::WHITE,
            true,
            46.0,
            8.0,
        )
        .clicked()
        {
            self.start_token_login();
        }
        ui.add_space(14.0);
        full_row(ui, 30.0, Layout::top_down(Align::Center), |ui| {
            if text_btn(ui, "Skip for now", 14.0, TEXT_2, false).clicked() {
                self.signin_open = false;
                self.signin_email = false;
            }
        });
    }

    // ================== MODEL POPUP (bottom sheet) ==================
    fn model_popup_ui(&mut self, ui: &mut egui::Ui) {
        let screen = ui.ctx().input(|i| i.viewport_rect());
        let w = (screen.width() - 24.0).min(420.0);
        let is_guest = self.client.as_ref().map(|c| c.is_guest).unwrap_or(true);
        let model_idx = self.model_idx;
        let models = self.models.clone();
        let effort = self.effort;
        let check = self.icons.check.clone();

        let id = Id::unique("model_modal");
        let modal = egui::Modal::new(id)
            .area(
                egui::Modal::default_area(id)
                    .anchor(Align2::CENTER_BOTTOM, egui::vec2(0.0, -16.0)),
            )
            .backdrop_color(Color32::from_black_alpha(90))
            .frame(
                egui::Frame::new()
                    .fill(SURFACE)
                    .corner_radius(16.0)
                    .shadow(popup_shadow()),
            );
        let resp = modal.show(ui.ctx(), |ui| {
            ui.set_width(w);
            egui::Frame::new()
                .inner_margin(Margin { left: 18, right: 18, top: 16, bottom: 16 })
                .show(ui, |ui| {
                    // Model sətirləri
                    for (i, (_mid, name)) in models.iter().enumerate() {
                        let selected = i == model_idx;
                        let (rect, r) = ui.allocate_exact_size(
                            egui::vec2(ui.available_width(), 44.0),
                            Sense::click(),
                        );
                        let fill = if selected {
                            HOVER
                        } else if r.hovered() {
                            Color32::from_rgb(246, 246, 247)
                        } else {
                            SURFACE
                        };
                        ui.painter().rect_filled(rect, CornerRadius::same(10), fill);
                        let galley = ui.ctx().fonts_mut(|f| {
                            f.layout_no_wrap(
                                name.clone(),
                                if selected {
                                    egui::FontId::new(15.0, bold_family())
                                } else {
                                    egui::FontId::proportional(15.0)
                                },
                                if selected { TEXT } else { TEXT_2 },
                            )
                        });
                        ui.painter().galley(
                            egui::pos2(rect.left() + 12.0, rect.center().y - galley.size().y / 2.0),
                            galley,
                            if selected { TEXT } else { TEXT_2 },
                        );
                        if selected {
                            let ir = egui::Rect::from_center_size(
                                egui::pos2(rect.right() - 20.0, rect.center().y),
                                egui::vec2(16.0, 16.0),
                            );
                            egui::Image::from_texture(&check)
                                .tint(TEXT)
                                .fit_to_exact_size(egui::vec2(16.0, 16.0))
                                .paint_at(ui, ir);
                        }
                        if r.clicked() {
                            self.model_idx = i;
                            self.save_state();
                            self.model_popup = false;
                        }
                    }
                    // Effort slider
                    ui.add_space(10.0);
                    ui.separator();
                    ui.add_space(8.0);
                    full_row(ui, 22.0, Layout::left_to_right(Align::Center), |ui| {
                        ui.label(RichText::new("Effort").size(13.0).color(TEXT_2));
                        let label = if effort >= 0.75 {
                            "Max"
                        } else if effort >= 0.4 {
                            "High"
                        } else {
                            "Low"
                        };
                        let rw = ui.available_width();
                        region(ui, egui::vec2(rw, 22.0), Layout::right_to_left(Align::Center), |ui| {
                            ui.label(
                                RichText::new(label).size(13.0).color(TEXT).family(bold_family()),
                            );
                        });
                    });
                    let mut new_effort = self.effort;
                    effort_slider(ui, effort, &mut new_effort);
                    self.effort = new_effort;
                    // Qonaq üçün premium blok
                    if is_guest {
                        ui.add_space(12.0);
                        ui.separator();
                        ui.add_space(12.0);
                        ui.label(
                            RichText::new("Try advanced features")
                                .size(15.0)
                                .color(TEXT)
                                .family(bold_family()),
                        );
                        ui.add_space(4.0);
                        ui.label(
                            RichText::new(
                                "Sign in to get smarter responses, access advanced models, and more.",
                            )
                            .size(12.0)
                            .color(TEXT_2),
                        );
                        ui.add_space(10.0);
                        full_row(ui, 36.0, Layout::left_to_right(Align::Center), |ui| {
                            if dark_pill(ui, "Sign up for free", 36.0, 13.5).clicked() {
                                self.model_popup = false;
                                self.signin_open = true;
                                self.signin_email = false;
                            }
                            ui.add_space(8.0);
                            if outline_pill(ui, "Sign in", 36.0, 13.5).clicked() {
                                self.model_popup = false;
                                self.signin_open = true;
                                self.signin_email = false;
                            }
                        });
                    }
                });
        });
        if resp.should_close() {
            self.model_popup = false;
        }
    }

    // ================== API MENYU (dropdown) ==================
    fn api_menu_ui(&mut self, ui: &mut egui::Ui) {
        let ext = self.icons.external.clone();
        let mut info_msg: Option<String> = None;

        let id = Id::unique("api_modal");
        let modal = egui::Modal::new(id)
            .area(
                egui::Modal::default_area(id)
                    .anchor(Align2::RIGHT_TOP, egui::vec2(-12.0, 56.0)),
            )
            .backdrop_color(Color32::from_black_alpha(60))
            .frame(
                egui::Frame::new()
                    .fill(SURFACE)
                    .corner_radius(12.0)
                    .shadow(popup_shadow())
                    .inner_margin(Margin { left: 6, right: 6, top: 10, bottom: 10 }),
            );
        let resp = modal.show(ui.ctx(), |ui| {
            ui.set_width(264.0);
            let r1 = api_section(
                ui,
                "Product",
                &["Language Models", "Vision Models", "Video Gen Models", "Agent"],
                &ext,
            );
            ui.add_space(6.0);
            let r2 = api_section(
                ui,
                "Documents",
                &["Quick Start", "Overview", "API Reference", "Coding Plan", "API Keys"],
                &ext,
            );
            if r1.is_none() {
                info_msg = r2;
            } else {
                info_msg = r1;
            }
        });
        if resp.should_close() {
            self.api_menu = false;
        }
        if let Some(item) = info_msg {
            self.api_menu = false;
            self.info = Some(format!("‘{}’ — z.ai veb saytında açılır", item));
        }
    }

    fn start_new_chat(&mut self) {
        if self.streaming {
            self.stop_generation();
        }
        self.chat_id = None;
        self.chat_title = "New Chat".into();
        self.messages.clear();
        self.streaming = false;
        self.stop_flag = None;
        self.error = None;
        self.info = None;
        self.regen_prompt = None;
        self.drawer_open = false;
        self.model_popup = false;
    }
}

// ================== Drawer ==================

struct IconsRef {
    x: TextureHandle,
    square_pen: TextureHandle,
}

struct DrawerCtx<'a> {
    icons: &'a IconsRef,
    chats: &'a [ChatSummary],
    active_id: Option<String>,
    loading: bool,
    search: &'a str,
    is_guest: bool,
    user_name: &'a str,
}

#[allow(clippy::too_many_arguments)]
fn drawer_content(
    ui: &mut egui::Ui,
    ctx: DrawerCtx<'_>,
    search_input: &mut String,
    drawer_open: &mut bool,
    open_chat_id: &mut Option<String>,
    new_chat: &mut bool,
    sign_out: &mut bool,
    go_signin: &mut bool,
    pull: &mut (f32, bool),
    pull_trigger: &mut bool,
) {
    // Başlıq sətri (48px + alt sərhəd)
    let (hdr_rect, _) =
        ui.allocate_exact_size(egui::vec2(ui.available_width(), 48.0), Sense::hover());
    ui.painter().line_segment(
        [hdr_rect.left_bottom(), hdr_rect.right_bottom()],
        egui::Stroke::new(1.0, border_alpha()),
    );
    let mut hdr = ui.new_child(
        egui::UiBuilder::new()
            .max_rect(hdr_rect.shrink2(egui::vec2(10.0, 0.0)))
            .layout(Layout::left_to_right(Align::Center)),
    );
    let hw = hdr.available_width();
    region(&mut hdr, egui::vec2(hw, 48.0), Layout::left_to_right(Align::Center), |ui| {
        ui.label(RichText::new("Z.ai").size(17.0).color(TEXT).family(bold_family()));
        let rw = ui.available_width();
        region(ui, egui::vec2(rw, 48.0), Layout::right_to_left(Align::Center), |ui| {
            if icons::draw_clickable(ui, &ctx.icons.x, 16.0, 36.0, TEXT_2).clicked() {
                *drawer_open = false;
            }
        });
    });

    egui::Frame::new()
        .fill(SURFACE)
        .inner_margin(Margin { left: 14, right: 14, top: 10, bottom: 0 })
        .show(ui, |ui| {
            // New Chat düyməsi (tünd)
            if full_btn(
                ui,
                "New Chat",
                Some(&ctx.icons.square_pen),
                Color32::WHITE,
                true,
                38.0,
                10.0,
            )
            .clicked()
            {
                *drawer_open = false;
                *new_chat = true;
            }
            ui.add_space(10.0);
            // Axtarış
            ui.add(
                egui::TextEdit::singleline(search_input)
                    .hint_text("Search")
                    .desired_width(f32::INFINITY)
                    .text_color(TEXT),
            );
            ui.add_space(6.0);
        });

    // Siyahı
    let q = ctx.search.to_lowercase();
    let list: Vec<&ChatSummary> = ctx
        .chats
        .iter()
        .filter(|c| q.is_empty() || c.title.to_lowercase().contains(&q))
        .collect();

    pull_indicator(ui, pull.0, pull.1, ctx.loading);
    let out = egui::ScrollArea::vertical()
        .auto_shrink([false, false])
        .show(ui, |ui| {
            ui.add_space(4.0);
            if list.is_empty() && !ctx.loading {
                full_row(ui, 80.0, Layout::top_down(Align::Center), |ui| {
                    ui.add_space(40.0);
                    ui.label(RichText::new("No conversations yet").size(14.0).color(TEXT_3));
                });
            }
            let mut last_group = String::new();
            for c in &list {
                let group = group_label(c.updated_at);
                if group != last_group {
                    last_group = group.clone();
                    ui.add_space(8.0);
                    ui.label(RichText::new(&group).size(12.0).color(TEXT_3));
                }
                let active = Some(&c.id) == ctx.active_id.as_ref();
                let (rect, r) =
                    ui.allocate_exact_size(egui::vec2(ui.available_width(), 40.0), Sense::click());
                let fill = if active {
                    HOVER
                } else if r.hovered() {
                    Color32::from_rgb(245, 245, 246)
                } else {
                    SURFACE
                };
                ui.painter().rect_filled(rect, CornerRadius::same(8), fill);
                let galley = ui.ctx().fonts_mut(|f| {
                    f.layout_no_wrap(truncate(&c.title, 30), egui::FontId::proportional(14.0), TEXT)
                });
                ui.painter().galley(
                    egui::pos2(rect.left() + 10.0, rect.center().y - galley.size().y / 2.0),
                    galley,
                    TEXT,
                );
                if r.clicked() {
                    *open_chat_id = Some(c.id.clone());
                }
            }
            ui.add_space(8.0);
        });
    let at_top = out.state.clamped_offset().y <= 0.5;
    // Drawer-da pulldown-refresh: yuxarıda ikən aşağı çək → burax → yenilə
    let (dragging, dy) =
        ui.input(|i| (i.pointer.is_decidedly_dragging(), i.pointer.delta().y));
    if dragging && at_top && dy > 0.0 {
        pull.0 = (pull.0 + dy).min(140.0);
    } else if !dragging {
        if pull.1 {
            *pull_trigger = true;
        }
        pull.0 = 0.0;
        pull.1 = false;
    }
    pull.1 = pull.0 >= 80.0;

    // Alt: profil (ekranın dibinə yapışdır)
    let screen_h = ui.ctx().input(|i| i.viewport_rect()).height();
    let used = ui.min_rect().bottom();
    if screen_h - used > 60.0 {
        ui.add_space((screen_h - used - 54.0).max(0.0));
    }
    ui.separator();
    egui::Frame::new()
        .fill(SURFACE)
        .inner_margin(Margin { left: 14, right: 14, top: 8, bottom: 8 })
        .show(ui, |ui| {
            let fw = ui.available_width();
            region(ui, egui::vec2(fw, 44.0), Layout::left_to_right(Align::Center), |ui| {
                let initial = if ctx.is_guest {
                    "G".to_string()
                } else {
                    ctx.user_name
                        .chars()
                        .next()
                        .map(|c| c.to_uppercase().to_string())
                        .unwrap_or_else(|| "U".into())
                };
                let (r, _) = ui.allocate_exact_size(egui::vec2(28.0, 28.0), Sense::hover());
                ui.painter().rect_filled(r, CornerRadius::same(14), BTN_DARK);
                avatar_text(ui, r, &initial, 13.0);
                ui.add_space(8.0);
                ui.label(
                    RichText::new(if ctx.is_guest { "Guest" } else { ctx.user_name })
                        .size(13.5)
                        .color(TEXT),
                );
                let rw = ui.available_width();
                region(ui, egui::vec2(rw, 44.0), Layout::right_to_left(Align::Center), |ui| {
                    if ctx.is_guest {
                        if text_btn(ui, "Sign in", 13.5, TEXT, true).clicked() {
                            *go_signin = true;
                        }
                    } else if text_btn(ui, "Sign out", 13.5, TEXT_2, false).clicked() {
                        *sign_out = true;
                    }
                });
            });
        });
}

// ================== Mesaj vidcetləri ==================

/// İstifadəçi baloncuğu: sağda, #EAEAEA, r12, pad12 (veb dəyərləri)
fn user_bubble(ui: &mut egui::Ui, m: &Msg, copy_tex: &TextureHandle) {
    // balon hündürlüyü mətn ölçüsü ilə hesablanır
    let max_w = (ui.available_width() * 0.82).min(560.0);
    let galley = ui.ctx().fonts_mut(|f| {
        f.layout(
            m.content.clone(),
            egui::FontId::proportional(16.0),
            TEXT,
            max_w - 24.0,
        )
    });
    let bubble_h = galley.size().y + 24.0;
    let bubble_w = galley.size().x + 24.0;
    ui.add_space(6.0);
    let row_w = ui.available_width();
    region(
        ui,
        egui::vec2(row_w, bubble_h),
        Layout::right_to_left(Align::TOP),
        |ui| {
            egui::Frame::new()
                .fill(BUBBLE_USER)
                .corner_radius(12.0)
                .inner_margin(Margin::same(12))
                .show(ui, |ui| {
                    ui.set_min_width(bubble_w - 24.0);
                    ui.label(RichText::new(&m.content).size(16.0).color(TEXT));
                });
        },
    );
    // kopyalama (veb-də user mesajın altında da var)
    region(ui, egui::vec2(row_w, 26.0), Layout::right_to_left(Align::TOP), |ui| {
        copy_action(ui, &m.content, copy_tex);
    });
    ui.add_space(8.0);
}

/// Köməkçi mesaj: fondasız tam en + Copy / Regenerate.
/// Regenerate kliklənibsə təkrar göndəriləcək prompt-u qaytarır.
fn assistant_msg(
    ui: &mut egui::Ui,
    m: &Msg,
    is_streaming: bool,
    has_error: bool,
    regen_prompt: Option<String>,
    copy_tex: &TextureHandle,
) -> Option<String> {
    ui.add_space(6.0);
    let mut regen: Option<String> = None;
    if m.content.is_empty() {
        if is_streaming {
            // "Thinking..." animasiyalı nöqtələr (veb davranışı)
            let t = ui.ctx().time() as i32;
            let dots = ".".repeat(((t as i64 / 600) % 3 + 1) as usize);
            let phase = m.phase.clone().unwrap_or_else(|| "Thinking".into());
            ui.label(
                RichText::new(format!("{}{}", phase, dots))
                    .size(14.0)
                    .color(TEXT_2),
            );
        } else if has_error {
            // veb xəta mətni + Regenerate
            ui.label(
                RichText::new("No response, Please try again later.")
                    .size(16.0)
                    .color(TEXT_2),
            );
            ui.add_space(8.0);
            if outline_pill(ui, "Regenerate", 34.0, 13.0).clicked() {
                regen = regen_prompt;
            }
        }
    } else {
        md::render(ui, &m.content);
        if let Some(p) = &m.phase {
            ui.add_space(2.0);
            ui.label(RichText::new(format!("{}…", p)).size(12.5).color(TEXT_3));
        }
        if !is_streaming {
            ui.add_space(6.0);
            let cw = ui.available_width();
            region(ui, egui::vec2(cw, 26.0), Layout::left_to_right(Align::TOP), |ui| {
                copy_action(ui, &m.content, copy_tex);
            });
        }
    }
    ui.add_space(10.0);
    regen
}

/// Copy əməliyyatı (veb: copy ikonu + "Copy" mətni, #676767)
fn copy_action(ui: &mut egui::Ui, content: &str, copy_tex: &TextureHandle) {
    if icons::draw_clickable(ui, copy_tex, 14.0, 30.0, TEXT_MUTED).clicked() {
        crate::platform::copy_to_clipboard(ui.ctx(), content);
    }
    ui.label(RichText::new("Copy").size(14.0).color(TEXT_MUTED));
}

// ================== Kiçik vidcetlər ==================

/// Mərkəzləşdirilmiş qısa mətn (avatar hərfi / Z loqosu)
fn avatar_text(ui: &mut egui::Ui, rect: egui::Rect, text: &str, size: f32) {
    let galley = ui.ctx().fonts_mut(|f| {
        f.layout_no_wrap(text.to_string(), egui::FontId::new(size, bold_family()), Color32::WHITE)
    });
    ui.painter().galley(
        egui::pos2(
            rect.center().x - galley.size().x / 2.0,
            rect.center().y - galley.size().y / 2.0,
        ),
        galley,
        Color32::WHITE,
    );
}

/// Təklif kartı: #E6E6E6 konteyner + mərkəzdən kəsilmiş şəkil + label (veb dəyərləri).
/// Veb: button 170x164, img 144x119 (object-fit: cover), label 13px/500.
fn card(ui: &mut egui::Ui, label: &str, tex: &TextureHandle, w: f32, img_h: f32) -> egui::Response {
    let pad = w * (13.0 / 170.0);
    let inner_img_w = w - pad * 2.0;
    let total_h = pad + img_h + 10.0 + 18.0 + pad * 0.7;
    let (rect, resp) = ui.allocate_exact_size(egui::vec2(w, total_h), Sense::click());
    // konteyner
    ui.painter()
        .rect_filled(rect, CornerRadius::same(12), Color32::from_rgb(230, 230, 230));
    // şəkil — mərkəzdən kəs (object-fit: cover)
    let img_rect = egui::Rect::from_min_size(
        egui::pos2(rect.left() + pad, rect.top() + pad),
        egui::vec2(inner_img_w, img_h),
    );
    let tex_size = tex.size_vec2();
    let target_aspect = inner_img_w / img_h;
    let src_aspect = tex_size.x / tex_size.y;
    let uv = if src_aspect > target_aspect {
        // mənbə daha enli — sol/sağdan kəs
        let frac = target_aspect / src_aspect;
        let cut = (1.0 - frac) / 2.0;
        egui::Rect::from_min_max(egui::pos2(cut, 0.0), egui::pos2(1.0 - cut, 1.0))
    } else {
        // mənbə daha hündür — yuxarı/aşağıdan kəs
        let frac = src_aspect / target_aspect;
        let cut = (1.0 - frac) / 2.0;
        egui::Rect::from_min_max(egui::pos2(0.0, cut), egui::pos2(1.0, 1.0 - cut))
    };
    egui::Image::from_texture(tex)
        .corner_radius(6)
        .uv(uv)
        .fit_to_exact_size(egui::vec2(inner_img_w, img_h))
        .paint_at(ui, img_rect);
    // label — konteyner içində, şəklin altında
    let galley = ui
        .ctx()
        .fonts_mut(|f| f.layout_no_wrap(label.to_string(), egui::FontId::new(13.0, bold_family()), TEXT_2));
    ui.painter().galley(
        egui::pos2(
            rect.center().x - galley.size().x / 2.0,
            img_rect.bottom() + 9.0,
        ),
        galley,
        TEXT_2,
    );
    resp
}

/// Konturlu dairə ikon düyməsi (+, veb: 30px dairə, 1px sərhəd)
fn circle_icon_btn(ui: &mut egui::Ui, tex: &TextureHandle, d: f32, tint: Color32) -> egui::Response {
    let (rect, resp) = ui.allocate_exact_size(egui::vec2(d, d), Sense::click());
    let fill = if resp.hovered() { HOVER } else { SURFACE };
    ui.painter().rect_filled(rect, CornerRadius::same((d / 2.0) as u8), fill);
    ui.painter()
        .rect_stroke(rect, CornerRadius::same((d / 2.0) as u8), border_stroke(), egui::StrokeKind::Inside);
    let s = d * 0.47;
    let img_rect = egui::Rect::from_center_size(rect.center(), egui::vec2(s, s));
    egui::Image::from_texture(tex)
        .tint(tint)
        .fit_to_exact_size(egui::vec2(s, s))
        .paint_at(ui, img_rect);
    resp
}

/// Dolu dairə ikon düyməsi (göndər: qara dairə + ağ ox; deaktiv: #E6E6E6)
fn circle_icon_btn_solid(
    ui: &mut egui::Ui,
    tex: &TextureHandle,
    d: f32,
    bg: Color32,
    tint: Color32,
) -> egui::Response {
    let interactive = bg == BTN_DARK;
    let (rect, resp) = ui.allocate_exact_size(
        egui::vec2(d, d),
        if interactive { Sense::click() } else { Sense::hover() },
    );
    let fill = if interactive && resp.hovered() {
        Color32::from_rgb(45, 45, 45)
    } else {
        bg
    };
    ui.painter().rect_filled(rect, CornerRadius::same((d / 2.0) as u8), fill);
    if !interactive {
        ui.painter()
            .rect_stroke(rect, CornerRadius::same((d / 2.0) as u8), border_stroke(), egui::StrokeKind::Inside);
    }
    let s = d * 0.5;
    let img_rect = egui::Rect::from_center_size(rect.center(), egui::vec2(s, s));
    egui::Image::from_texture(tex)
        .tint(tint)
        .fit_to_exact_size(egui::vec2(s, s))
        .paint_at(ui, img_rect);
    resp
}

/// "or" mətnli ayırıcı
fn divider_with_text(ui: &mut egui::Ui, text: &str) {
    full_row(ui, 20.0, Layout::left_to_right(Align::Center), |ui| {
        let w = (ui.available_width() - 34.0) / 2.0;
        let (l, _) = ui.allocate_exact_size(egui::vec2(w, 0.5), Sense::hover());
        ui.painter().line_segment(
            [l.left_center(), l.right_center()],
            egui::Stroke::new(1.0, border_alpha()),
        );
        ui.label(RichText::new(text).size(12.0).color(TEXT_3));
        let (r2, _) = ui.allocate_exact_size(egui::vec2(w, 0.5), Sense::hover());
        ui.painter().line_segment(
            [r2.left_center(), r2.right_center()],
            egui::Stroke::new(1.0, border_alpha()),
        );
    });
}

/// Mətn sahəsi (veb form stili: ağ, sərhəd, h48, r8)
fn field(ui: &mut egui::Ui, hint: &str, password: bool, buf: &mut String) {
    egui::Frame::new()
        .fill(SURFACE)
        .corner_radius(8.0)
        .stroke(border_stroke())
        .inner_margin(Margin { left: 12, right: 12, top: 12, bottom: 10 })
        .show(ui, |ui| {
            ui.set_width(ui.available_width());
            let mut edit = egui::TextEdit::singleline(buf)
                .hint_text(hint)
                .text_color(TEXT)
                .desired_width(f32::INFINITY)
                .frame(egui::Frame::NONE);
            if password {
                edit = edit.password(true);
            }
            ui.add(edit);
        });
}

/// Effort slider (veb: nazik trek, dolu hissə qara, ağ thumb)
fn effort_slider(ui: &mut egui::Ui, value: f32, out: &mut f32) {
    let h = 28.0;
    let (rect, resp) =
        ui.allocate_exact_size(egui::vec2(ui.available_width(), h), Sense::click_and_drag());
    let track = egui::Rect::from_center_size(rect.center(), egui::vec2(rect.width(), 6.0));
    ui.painter()
        .rect_filled(track, CornerRadius::same(3), Color32::from_rgb(229, 231, 235));
    let fill_w = track.width() * value;
    let fill = egui::Rect::from_min_size(track.left_top(), egui::vec2(fill_w, 6.0));
    ui.painter().rect_filled(fill, CornerRadius::same(3), BTN_DARK);
    let thumb_x = track.left() + fill_w;
    let thumb =
        egui::Rect::from_center_size(egui::pos2(thumb_x, track.center().y), egui::vec2(20.0, 20.0));
    ui.painter().rect_filled(thumb, CornerRadius::same(10), Color32::WHITE);
    ui.painter().rect_stroke(
        thumb,
        CornerRadius::same(10),
        egui::Stroke::new(1.0, border_alpha()),
        egui::StrokeKind::Inside,
    );
    if resp.dragged() || resp.clicked() {
        if let Some(p) = resp.interact_pointer_pos() {
            let frac = ((p.x - track.left()) / track.width()).clamp(0.0, 1.0);
            *out = frac;
        }
    }
}

/// API menyusu bölməsi — kliklənən elementin adını qaytarır
fn api_section(ui: &mut egui::Ui, title: &str, items: &[&str], ext: &TextureHandle) -> Option<String> {
    ui.label(RichText::new(title).size(11.0).color(TEXT_3));
    let mut clicked: Option<String> = None;
    for item in items {
        let (rect, r) =
            ui.allocate_exact_size(egui::vec2(ui.available_width(), 36.0), Sense::click());
        let fill = if r.hovered() { HOVER } else { SURFACE };
        ui.painter().rect_filled(rect, CornerRadius::same(8), fill);
        let galley = ui
            .ctx()
            .fonts_mut(|f| f.layout_no_wrap(item.to_string(), egui::FontId::proportional(14.0), TEXT));
        ui.painter().galley(
            egui::pos2(rect.left() + 10.0, rect.center().y - galley.size().y / 2.0),
            galley,
            TEXT,
        );
        let ir = egui::Rect::from_center_size(
            egui::pos2(rect.right() - 16.0, rect.center().y),
            egui::vec2(13.0, 13.0),
        );
        egui::Image::from_texture(ext)
            .tint(TEXT_3)
            .fit_to_exact_size(egui::vec2(13.0, 13.0))
            .paint_at(ui, ir);
        if r.clicked() {
            clicked = Some((*item).to_string());
        }
    }
    clicked
}

/// Pulldown-refresh göstəricisi
fn pull_indicator(ui: &mut egui::Ui, dist: f32, armed: bool, loading: bool) {
    if loading {
        full_row(ui, 24.0, Layout::top_down(Align::Center), |ui| {
            ui.horizontal(|ui| {
                ui.add(egui::Spinner::new().size(14.0).color(TEXT_2));
                ui.label(RichText::new("Yenilənir…").size(13.0).color(TEXT_2));
            });
        });
    } else if dist > 0.0 {
        full_row(ui, 22.0, Layout::top_down(Align::Center), |ui| {
            let pct = (((dist / 80.0) * 100.0) as i32).clamp(0, 100);
            let txt = if armed {
                format!("↻ {}% — yeniləmək üçün buraxın", pct)
            } else {
                format!("↓ yeniləmək üçün çəkin · {}%", pct)
            };
            ui.label(RichText::new(txt).size(13.0).color(TEXT_3));
        });
    }
}

/// Tarix qrupu etiketi (veb: Today / Yesterday / Last 7 Days…)
fn group_label(ts: i64) -> String {
    let now = crate::api::now_ms() as i64 / 1000;
    let d = now - ts;
    if d < 86400 {
        "Today".into()
    } else if d < 172800 {
        "Yesterday".into()
    } else if d < 604800 {
        "Last 7 Days".into()
    } else if d < 2592000 {
        "Last 30 Days".into()
    } else {
        "Earlier".into()
    }
}

fn truncate(s: &str, max: usize) -> String {
    if s.chars().count() <= max {
        s.to_string()
    } else {
        let t: String = s.chars().take(max).collect();
        format!("{}…", t)
    }
}
