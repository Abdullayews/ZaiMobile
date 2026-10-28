use egui::{Color32, FontFamily, RichText, Ui};

pub const TXT: Color32 = Color32::from_rgb(230, 234, 242);
pub const DIM: Color32 = Color32::from_rgb(138, 147, 166);
pub const ACCENT: Color32 = Color32::from_rgb(76, 111, 255);
pub const CODE_BG: Color32 = Color32::from_rgb(13, 17, 23);
pub const INLINE_CODE: Color32 = Color32::from_rgb(139, 195, 255);
pub const LINK: Color32 = Color32::from_rgb(96, 165, 250);

fn bold_family() -> FontFamily {
    FontFamily::Name(std::sync::Arc::from("bold"))
}

fn mono_family() -> FontFamily {
    FontFamily::Monospace
}

/// Yüngül markdown renderer: kod blokları, başlıqlar, bold, inline kod, siyahılar, keçidlər
pub fn render(ui: &mut Ui, text: &str) {
    // 1) ```kod``` bloklarını ayır
    let mut segments: Vec<Seg> = Vec::new();
    let mut rest = text;
    let mut in_code = false;
    let mut buf = String::new();
    loop {
        if let Some(pos) = rest.find("```") {
            let (before, after) = rest.split_at(pos);
            let after = &after[3..];
            if in_code {
                // kod bitdi — dili ötür
                let body = before;
                segments.push(Seg::Code(body.to_string()));
            } else {
                segments.push(Seg::Text(before.to_string()));
            }
            in_code = !in_code;
            // dili ötür (birinci sətir)
            let after = if in_code {
                match after.find('\n') {
                    Some(nl) => &after[nl + 1..],
                    None => after,
                }
            } else {
                after
            };
            rest = after;
            buf.clear();
        } else {
            if in_code {
                buf.push_str(rest);
                segments.push(Seg::Code(buf.clone()));
            } else if !rest.is_empty() {
                segments.push(Seg::Text(rest.to_string()));
            }
            break;
        }
    }

    for seg in segments {
        match seg {
            Seg::Code(body) => render_code_block(ui, &body),
            Seg::Text(t) => render_text(ui, &t),
        }
    }
}

enum Seg {
    Text(String),
    Code(String),
}

fn render_code_block(ui: &mut Ui, body: &str) {
    let body = body.trim_end_matches('\n');
    egui::Frame::new()
        .fill(CODE_BG)
        .corner_radius(8.0)
        .inner_margin(egui::Margin::same(10))
        .outer_margin(egui::Margin { left: 0, right: 8, top: 4, bottom: 4 })
        .stroke(egui::Stroke::new(1.0, Color32::from_rgb(32, 38, 48)))
        .show(ui, |ui| {
            ui.set_min_width((ui.available_width() - 16.0).max(60.0));
            ui.style_mut().override_text_style = Some(egui::TextStyle::Monospace);
            ui.add(
                egui::TextEdit::multiline(&mut body.to_owned())
                    .font(egui::TextStyle::Monospace)
                    .text_color(Color32::from_rgb(201, 209, 217))
                    .desired_width(f32::INFINITY)
                    .interactive(false),
            );
        });
}

fn render_text(ui: &mut Ui, text: &str) {
    for line in text.split('\n') {
        let trimmed = line.trim_start();
        if trimmed.is_empty() {
            ui.add_space(4.0);
            continue;
        }
        // Başlıqlar
        let heading = if trimmed.starts_with("### ") {
            Some(15.0)
        } else if trimmed.starts_with("## ") {
            Some(16.5)
        } else if trimmed.starts_with("# ") {
            Some(18.0)
        } else {
            None
        };
        if let Some(sz) = heading {
            let h = trimmed.trim_start_matches('#').trim();
            ui.label(
                RichText::new(h)
                    .family(bold_family())
                    .size(sz)
                    .color(Color32::WHITE),
            );
            ui.add_space(2.0);
            continue;
        }
        // Siyahı elementləri
        if trimmed.starts_with("- ") || trimmed.starts_with("* ") {
            ui.horizontal_wrapped(|ui| {
                ui.add_space(10.0);
                ui.label(RichText::new("•").color(ACCENT));
                inline(ui, trimmed[2..].trim());
            });
            continue;
        }
        if let Some(num) = numbered(trimmed) {
            ui.horizontal_wrapped(|ui| {
                ui.add_space(10.0);
                ui.label(RichText::new(format!("{}.", num)).color(ACCENT));
                inline(ui, trimmed[(num.to_string().len() + 2).min(trimmed.len())..].trim());
            });
            continue;
        }
        // İtirilmiş düşüncə bloklarını gizlət
        if trimmed.starts_with("<details") || trimmed.starts_with("</details") {
            continue;
        }
        if trimmed.starts_with("<glm_block") || trimmed.starts_with("</glm_block") {
            continue;
        }
        ui.horizontal_wrapped(|ui| inline(ui, trimmed));
    }
}

fn numbered(s: &str) -> Option<u32> {
    let mut n = String::new();
    for c in s.chars() {
        if c.is_ascii_digit() {
            n.push(c);
        } else if c == '.' && n.len() <= 2 {
            return n.parse().ok();
        } else {
            return None;
        }
    }
    None
}

/// Sətir daxili formatlaşdırma: **bold**, `kod`, [keçid](url)
fn inline(ui: &mut Ui, s: &str) {
    let bytes = s.as_bytes();
    let mut i = 0;
    let mut plain = String::new();

    let flush = |ui: &mut Ui, plain: &mut String| {
        if !plain.is_empty() {
            ui.label(RichText::new(plain.clone()).color(TXT));
            plain.clear();
        }
    };

    while i < bytes.len() {
        // **bold**
        if s[i..].starts_with("**") {
            if let Some(end_rel) = s[i + 2..].find("**") {
                flush(ui, &mut plain);
                let content = &s[i + 2..i + 2 + end_rel];
                ui.label(RichText::new(content).family(bold_family()).color(Color32::WHITE));
                i += 2 + end_rel + 2;
                continue;
            }
        }
        // `kod`
        if bytes[i] == b'`' {
            if let Some(end_rel) = s[i + 1..].find('`') {
                flush(ui, &mut plain);
                let content = &s[i + 1..i + 1 + end_rel];
                ui.label(
                    RichText::new(content)
                        .family(mono_family())
                        .color(INLINE_CODE)
                        .background_color(Color32::from_rgb(28, 33, 43)),
                );
                i += 1 + end_rel + 1;
                continue;
            }
        }
        // [mətn](url)
        if bytes[i] == b'[' {
            if let Some(close) = s[i..].find(']') {
                if s[i + close + 1..].starts_with('(') {
                    if let Some(paren) = s[i + close + 2..].find(')') {
                        flush(ui, &mut plain);
                        let label = &s[i + 1..i + close];
                        let url = &s[i + close + 2..i + close + 2 + paren];
                        let r = ui.link(RichText::new(label).color(LINK));
                        if r.clicked() {
                            let _ = ui.ctx().open_url(egui::OpenUrl::new_tab(url));
                        }
                        i = i + close + 2 + paren + 1;
                        continue;
                    }
                }
            }
        }
        // adi simvol
        let ch = s[i..].chars().next().unwrap();
        plain.push(ch);
        i += ch.len_utf8();
    }
    flush(ui, &mut plain);
}

/// Kopyalama düyməsi — true qaytarırsa klikləndi
/// (Android-də JNI sistem buferi, desktop-da egui/arboard buferi)
pub fn copy_button(ui: &mut Ui, text: &str) -> bool {
    let r = ui.small(RichText::new("📋 Copy").color(DIM));
    if r.clicked() {
        crate::platform::copy_to_clipboard(ui.ctx(), text);
        true
    } else {
        false
    }
}
