//! Markdown → mesaj blokları (Slint renderi üçün).
//! Dəstək: başlıq, paraqraf, siyahı, sitat, kod bloku (dil etiketi ilə).
//! Inline **bold**/*italic*/`code` işarələri təmizlənir (mətn saxlanılır).

/// Blok növləri (chat.slint-dəki kind ilə uyğun)
pub const KIND_TEXT: i32 = 0;
pub const KIND_CODE: i32 = 1;
pub const KIND_LIST: i32 = 2;
pub const KIND_HEADING: i32 = 3;
pub const KIND_QUOTE: i32 = 4;

#[derive(Clone, Debug)]
pub struct Block {
    pub kind: i32,
    pub text: String,
    pub lang: String,
}

/// Inline markdown işarələrini təmizlə (**bold**, *italic*, `code`, [link](url) → text)
pub fn strip_inline(s: &str) -> String {
    let b: Vec<char> = s.chars().collect();
    let mut out = String::with_capacity(b.len());
    let mut i = 0usize;
    while i < b.len() {
        match b[i] {
            '`' => {
                // `code` → code
                let mut j = i + 1;
                while j < b.len() && b[j] != '`' {
                    j += 1;
                }
                for &c in &b[i + 1..j] {
                    out.push(c);
                }
                i = (j + 1).min(b.len());
            }
            '*' => {
                // ** və ya * — qapatma işarəsinə qədər keç
                if i + 1 < b.len() && b[i + 1] == '*' {
                    let mut j = i + 2;
                    while j + 1 < b.len() && !(b[j] == '*' && b[j + 1] == '*') {
                        j += 1;
                    }
                    for &c in &b[i + 2..j.min(b.len())] {
                        out.push(c);
                    }
                    i = (j + 2).min(b.len());
                } else {
                    let mut j = i + 1;
                    while j < b.len() && b[j] != '*' {
                        j += 1;
                    }
                    for &c in &b[i + 1..j.min(b.len())] {
                        out.push(c);
                    }
                    i = (j + 1).min(b.len());
                }
            }
            '[' => {
                // [text](url) → text
                let mut close = i + 1;
                while close < b.len() && b[close] != ']' {
                    close += 1;
                }
                if close + 1 < b.len() && b[close + 1] == '(' {
                    let mut endp = close + 2;
                    while endp < b.len() && b[endp] != ')' {
                        endp += 1;
                    }
                    for &c in &b[i + 1..close] {
                        out.push(c);
                    }
                    i = (endp + 1).min(b.len());
                } else {
                    out.push('[');
                    i += 1;
                }
            }
            '\\' if i + 1 < b.len() => {
                out.push(b[i + 1]);
                i += 2;
            }
            c => {
                out.push(c);
                i += 1;
            }
        }
    }
    out
}

/// Tam markdown → blok siyahısı
pub fn parse(md: &str) -> Vec<Block> {
    let mut blocks = Vec::new();
    // 1) kod hasarlarına görə böl
    let mut rest = md;
    let mut para_buf: Vec<String> = Vec::new();

    macro_rules! flush_para {
        () => {
            if !para_buf.is_empty() {
                let joined = para_buf.join("\n");
                let joined = joined.trim();
                if !joined.is_empty() {
                    blocks.push(Block { kind: KIND_TEXT, text: strip_inline(joined), lang: String::new() });
                }
                para_buf.clear();
            }
        };
    }

    loop {
        match rest.find("```") {
            Some(pos) => {
                let (before, after) = rest.split_at(pos);
                // hasardan əvvəlki mətn — sətir-sətir
                for line in before.lines() {
                    handle_line(line, &mut blocks, &mut para_buf);
                }
                flush_para!();
                // hasarın içinə bax
                let inner = &after[3..];
                let end = inner.find("```");
                let (code, remaining) = match end {
                    Some(e) => (&inner[..e], &inner[e + 3..]),
                    None => (inner, ""),
                };
                // ilk sətir = dil (qısa, boşluqsuzdursa)
                let mut lines = code.lines();
                let first = lines.next().unwrap_or("").trim().to_string();
                let is_lang = !first.is_empty() && first.len() <= 24 && !first.contains(' ') && !first.contains('\t');
                let (lang, body) = if is_lang {
                    (first, lines.collect::<Vec<_>>().join("\n"))
                } else {
                    (String::new(), code.to_string())
                };
                let body = body.strip_suffix('\n').unwrap_or(&body).to_string();
                if !body.trim().is_empty() {
                    blocks.push(Block { kind: KIND_CODE, text: body, lang });
                }
                rest = remaining;
                if end.is_none() {
                    break;
                }
            }
            None => {
                for line in rest.lines() {
                    handle_line(line, &mut blocks, &mut para_buf);
                }
                flush_para!();
                break;
            }
        }
    }

    // Təhlükəsizlik limitləri
    if blocks.len() > 400 {
        blocks.truncate(400);
    }
    for b in blocks.iter_mut() {
        if b.text.len() > 24000 {
            b.text.truncate(24000);
            b.text.push_str("…");
        }
    }
    blocks
}

fn handle_line(line: &str, blocks: &mut Vec<Block>, para_buf: &mut Vec<String>) {
    let t = line.trim();
    if t.is_empty() {
        if !para_buf.is_empty() {
            let joined = para_buf.join("\n").trim().to_string();
            if !joined.is_empty() {
                blocks.push(Block { kind: KIND_TEXT, text: strip_inline(&joined), lang: String::new() });
            }
            para_buf.clear();
        }
        return;
    }
    // başlıq
    let hcount = t.chars().take_while(|&c| c == '#').count();
    if hcount >= 1 && hcount <= 6 && t.chars().nth(hcount) == Some(' ') {
        let text = strip_inline(t[hcount + 1..].trim());
        if !text.is_empty() {
            blocks.push(Block { kind: KIND_HEADING, text, lang: String::new() });
        }
        return;
    }
    // siyahı
    if (t.starts_with("- ") || t.starts_with("* ") || t.starts_with("+ "))
        || (t.len() >= 2 && t.chars().next().unwrap().is_ascii_digit() && t[1..].starts_with(". "))
    {
        let item = if t.starts_with("- ") || t.starts_with("* ") || t.starts_with("+ ") {
            &t[2..]
        } else {
            &t[2..]
        };
        let text = strip_inline(item.trim());
        if !text.is_empty() {
            blocks.push(Block { kind: KIND_LIST, text, lang: String::new() });
        }
        return;
    }
    // sitat
    if t.starts_with('>') {
        let text = strip_inline(t[1..].trim());
        if !text.is_empty() {
            blocks.push(Block { kind: KIND_QUOTE, text, lang: String::new() });
        }
        return;
    }
    // adi sətir — paraqrafa yığ
    para_buf.push(line.to_string());
    if para_buf.len() > 200 {
        let joined = para_buf.join("\n");
        para_buf.clear();
        blocks.push(Block { kind: KIND_TEXT, text: strip_inline(&joined), lang: String::new() });
    }
}
