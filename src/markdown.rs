//! Markdown → mesaj blokları (Slint renderi üçün).
//! Dəstək: başlıq, paraqraf, siyahı, sitat, kod bloku (dil etiketi ilə).
//! Inline **bold**/*italic*/`code` işarələri təmizlənir (mətn saxlanılır).
//!
//! Artımlı axın üçün: `parse_spanned` hər blokun bayt ofsetini qaytarır və
//! `open_from` ilə bildirir ki, mətnin sonunda hələ böyüyə bilən bloq
//! haradan başlayır. Controller bu ofsetdən yalnız «quyruğu» yenidən
//! parse edir — tam mətni yox (axın zamanı O(tail)yerinə O(full)).

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

/// Başlanğıc ofseti ilə birlikdə blok (artımlı render üçün)
#[derive(Clone, Debug)]
pub struct SpannedBlock {
    /// Blokun `md` daxilindəki başlanğıc bayt ofseti
    pub start: usize,
    pub block: Block,
}

/// `parse_spanned`-in nəticəsi
#[derive(Clone, Debug)]
pub struct Parsed {
    pub blocks: Vec<SpannedBlock>,
    /// `Some(off)`: `off`-dan etibarən sonuncu bloq hələ «açıqdır» — axın
    /// davam etsə dəyişə/böyüyə/bölünə bilər. `None`: bütün bloklar finaldır.
    pub open_from: Option<usize>,
}

/// Təhlükəsizlik limitləri (veb klientdəki kimi sərhədlənmiş render)
const MAX_BLOCKS: usize = 400;
const MAX_BLOCK_TEXT: usize = 24000;

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

/// Sətir emalı (ofset izləmə ilə). `off` = sətirin başlanğıc ofseti.
/// `line` `md`-nin ömrü ilə bağlıdır (para_buf sətirləri ona istinad edir).
fn handle_line<'md>(
    line: &'md str,
    off: usize,
    blocks: &mut Vec<SpannedBlock>,
    para_buf: &mut Vec<(usize, &'md str)>,
) {
    let t = line.trim();
    if t.is_empty() {
        flush_para(blocks, para_buf);
        return;
    }
    // başlıq
    let hcount = t.chars().take_while(|&c| c == '#').count();
    if (1..=6).contains(&hcount) && t.chars().nth(hcount) == Some(' ') {
        let text = strip_inline(t[hcount + 1..].trim());
        if !text.is_empty() {
            blocks.push(SpannedBlock {
                start: off,
                block: Block { kind: KIND_HEADING, text, lang: String::new() },
            });
        }
        return;
    }
    // siyahı
    if (t.starts_with("- ") || t.starts_with("* ") || t.starts_with("+ "))
        || (t.len() >= 2 && t.chars().next().map(|c| c.is_ascii_digit()).unwrap_or(false) && t[1..].starts_with(". "))
    {
        let item = &t[2..];
        let text = strip_inline(item.trim());
        if !text.is_empty() {
            blocks.push(SpannedBlock {
                start: off,
                block: Block { kind: KIND_LIST, text, lang: String::new() },
            });
        }
        return;
    }
    // sitat
    if let Some(rest) = t.strip_prefix('>') {
        let text = strip_inline(rest.trim());
        if !text.is_empty() {
            blocks.push(SpannedBlock {
                start: off,
                block: Block { kind: KIND_QUOTE, text, lang: String::new() },
            });
        }
        return;
    }
    // adi sətir — paraqrafa yığ
    para_buf.push((off, line));
    if para_buf.len() > 200 {
        // çox uzun paraqraf — məcburi flush (paraqrafın öz ofsetindən)
        flush_para(blocks, para_buf);
    }
}

/// Yığılmış paraqraf sətirlərini TEXT blokuna çevir
fn flush_para(blocks: &mut Vec<SpannedBlock>, para_buf: &mut Vec<(usize, &str)>) {
    if let Some((first_off, _)) = para_buf.first().copied() {
        let joined = para_buf.iter().map(|(_, l)| *l).collect::<Vec<_>>().join("\n");
        let joined = joined.trim();
        if !joined.is_empty() {
            blocks.push(SpannedBlock {
                start: first_off,
                block: Block { kind: KIND_TEXT, text: strip_inline(joined), lang: String::new() },
            });
        }
        para_buf.clear();
    }
}

/// Kod hasarını bloka çevir (dil etiketi + gövdə)
fn push_code(fence_off: usize, code: &str, blocks: &mut Vec<SpannedBlock>) {
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
        blocks.push(SpannedBlock {
            start: fence_off,
            block: Block { kind: KIND_CODE, text: body, lang },
        });
    }
}

/// Tam markdown → ofsetli blok siyahısı + açıq quyruq mövqeyi.
///
/// `open_from` semantikası (səsliyin əsası):
/// - qapanmamış ``` hasarı → hasarın ofseti;
/// - son paraqraf hələ boş sətirlə qapanmayıb → paraqrafın ofseti;
/// - mətn `\n` ilə bitmir → son sətir (və ona məxsus blok) hələ böyüyə bilər.
pub fn parse_spanned(md: &str) -> Parsed {
    let mut blocks: Vec<SpannedBlock> = Vec::new();
    let mut para_buf: Vec<(usize, &str)> = Vec::new();
    let mut pos = 0usize;
    let mut open_from: Option<usize> = None;

    loop {
        let fence_rel = md[pos..].find("```");
        match fence_rel {
            Some(rel) => {
                let fence_at = pos + rel;
                let before = &md[pos..fence_at];
                let mut loff = pos;
                for line in before.split_inclusive('\n') {
                    let l = line.strip_suffix('\n').unwrap_or(line);
                    handle_line(l, loff, &mut blocks, &mut para_buf);
                    loff += line.len();
                }
                flush_para(&mut blocks, &mut para_buf);
                // hasarın içinə bax
                let after = &md[fence_at + 3..];
                match after.find("```") {
                    Some(e) => {
                        let code = &after[..e];
                        push_code(fence_at, code, &mut blocks);
                        pos = fence_at + 3 + e + 3;
                    }
                    None => {
                        // AÇIQ hasar — kod bloqu axın ilə böyüyəcək
                        push_code(fence_at, after, &mut blocks);
                        open_from = Some(fence_at);
                        break;
                    }
                }
            }
            None => {
                let rest = &md[pos..];
                let mut loff = pos;
                for line in rest.split_inclusive('\n') {
                    let l = line.strip_suffix('\n').unwrap_or(line);
                    handle_line(l, loff, &mut blocks, &mut para_buf);
                    loff += line.len();
                }
                break;
            }
        }
    }

    // son paraqraf (varsa) — flush-dan əvvəl onun ofsetini yadda saxla
    let para_open = para_buf.first().map(|(o, _)| *o);
    flush_para(&mut blocks, &mut para_buf);

    if open_from.is_none() {
        if let Some(po) = para_open {
            // paraqraf sonradan gələn sətirlərlə birləşə bilər → açıqdır
            open_from = Some(po);
        } else if !md.is_empty() && !md.ends_with('\n') {
            // son sətir tamamlanmayıb: son bloq (başlıq/siyahı/sitat/mətn) böyüyə bilər
            if let Some(last) = blocks.last() {
                open_from = Some(last.start);
            }
        }
    }

    // Təhlükəsizlik limitləri (əvvəlki davranışla eyni)
    if blocks.len() > MAX_BLOCKS {
        blocks.truncate(MAX_BLOCKS);
    }
    for sb in blocks.iter_mut() {
        if sb.block.text.len() > MAX_BLOCK_TEXT {
            sb.block.text.truncate(MAX_BLOCK_TEXT);
            sb.block.text.push('…');
        }
    }

    Parsed { blocks, open_from }
}

/// Tam markdown → blok siyahısı (final render üçün — köhnə API, davranış saxlanılıb)
pub fn parse(md: &str) -> Vec<Block> {
    parse_spanned(md).blocks.into_iter().map(|sb| sb.block).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn kinds(blocks: &[Block]) -> Vec<i32> {
        blocks.iter().map(|b| b.kind).collect()
    }

    #[test]
    fn parse_basics() {
        let b = parse("# Title\n\npara **bold** text\n\n- item1\n- item2\n\n> quote\n\n```rust\nfn x() {}\n```");
        assert_eq!(kinds(&b), vec![KIND_HEADING, KIND_TEXT, KIND_LIST, KIND_LIST, KIND_QUOTE, KIND_CODE]);
        assert_eq!(b[1].text, "para bold text");
        assert_eq!(b[5].lang, "rust");
        assert_eq!(b[5].text, "fn x() {}");
    }

    #[test]
    fn open_code_fence() {
        let p = parse_spanned("para\n\n```rust\nfn a");
        assert_eq!(p.open_from, Some(6), "açık hasarın ofseti");
        assert_eq!(p.blocks.len(), 2);
        assert_eq!(p.blocks[1].block.kind, KIND_CODE);
    }

    #[test]
    fn closed_paragraph_is_not_open() {
        let p = parse_spanned("para\n\n");
        assert_eq!(p.open_from, None);
        let p2 = parse_spanned("para\n\nnext\n\n");
        assert_eq!(p2.open_from, None);
    }

    #[test]
    fn growing_paragraph_is_open() {
        let p = parse_spanned("abc def");
        assert_eq!(p.open_from, Some(0));
        // axın davam edir: "abc def ghi" → yenə TEK paraqraf, ofset dəyişmir
        let p2 = parse_spanned("abc def ghi\n");
        assert_eq!(p2.open_from, Some(0));
    }

    #[test]
    fn unterminated_line_is_open() {
        // başlıq sətri hələ bitməyib
        let p = parse_spanned("para\n\n## Hea");
        assert_eq!(p.open_from, Some(6));
        // tamamlandıqdan sonra (\n ilə bitir) qapanır
        let p2 = parse_spanned("para\n\n## Heading\n");
        assert_eq!(p2.open_from, None);
    }

    /// ARTIMLILIQ ƏSAS TESTİ: istənilən prefiks üçün artımlı nəticə,
    /// tam parse ilə eyni blok ardıcıllığını verməlidir.
    #[test]
    fn incremental_matches_full_parse() {
        let doc = "Intro paragraph with some text.\n\n\
                   ## Heading two\n\n\
                   - list one\n- list two\n- list three\n\n\
                   ```python\nprint('hello')\nprint('world')\n```\n\n\
                   Closing paragraph after code.\n\n\
                   > a quote line\n\n\
                   Final paragraph tail that keeps growing";
        // hər bayt prefiksində yoxla (multi-byte yoxdur — ASCII sənəd)
        for cut in 0..=doc.len() {
            let prefix = &doc[..cut];
            let full = parse_spanned(prefix);
            // artımlı simulyasiya: stable-dan quyruğu parse et
            let mut stable = 0usize;
            let mut acc: Vec<Block> = Vec::new();
            let mut open_block: Option<Block> = None;
            // addım-addım: əvvəlki prefikslərdən keç (stable irəliləyir)
            // — sadələşdirmə: birbaşa cari prefiksdən başlayaraq tək addım yox,
            // bütün ara addımları oynat (real axın kimi)
            let mut prev = String::new();
            for end in 0..=cut {
                // yalnız «sətir sərhədlərində + hər 7 baytda» addım at (sürət üçün)
                if end < cut && end % 7 != 0 && doc.as_bytes()[end] != b'\n' {
                    continue;
                }
                let cur = &doc[..end];
                if cur.len() == prev.len() {
                    continue;
                }
                let tail = &cur[stable.min(cur.len())..];
                let p = parse_spanned(tail);
                let n = p.blocks.len();
                let closed = if p.open_from.is_some() { n.saturating_sub(1) } else { n };
                // bağlı blokları ötür
                for sb in &p.blocks[..closed] {
                    acc.push(sb.block.clone());
                }
                // açıq bloq (yalnız sonuncu)
                open_block = p.blocks.get(closed).map(|sb| sb.block.clone());
                match p.open_from {
                    Some(o) => stable += o,
                    None => stable = cur.len(),
                }
                prev = cur.to_string();
            }
            let mut mine = acc;
            if let Some(ob) = open_block {
                mine.push(ob);
            }
            // müqayisə: tam parse blokları
            let want: Vec<Block> = full.blocks.iter().map(|sb| sb.block.clone()).collect();
            assert_eq!(
                (mine.iter().map(|b| (b.kind, b.text.clone())).collect::<Vec<_>>(), mine.len()),
                (want.iter().map(|b| (b.kind, b.text.clone())).collect::<Vec<_>>(), want.len()),
                "prefiks {} baytda uyğunsuzluq",
                cut
            );
        }
    }

    #[test]
    fn limits() {
        let mut big = String::new();
        for i in 0..500 {
            big.push_str(&format!("para {}\n\n", i));
        }
        let b = parse(&big);
        assert_eq!(b.len(), MAX_BLOCKS);
    }
}
