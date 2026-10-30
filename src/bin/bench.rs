//! Performans benchmark-ləri (host, release):
//!   cargo run --release --bin bench
//! Ölçür:
//!   1. markdown::parse — tam mətn üzrə (hazırkı "hər flush-da tam parse" modeli)
//!   2. streaming simulyasiyası: N flush-də cəmi CPU
//!   3. push_all_messages pattern-inin clone xərci (uzun söhbət)
//!   4. state.json yazma ölçüsü
//! Nəticələr cədvəl şəklində stdout-a çıxır.

use std::time::Instant;
use zai_mobile::markdown;

/// Realistik cavab mətni yarat (paraqraf + başlıq + siyahı + kod hasarı qarışığı)
fn synth_response(target_bytes: usize) -> String {
    let mut s = String::with_capacity(target_bytes + 4096);
    let para = "This is a realistic paragraph of assistant text with **some bold** and `inline code` markers, describing how the GLM model streams tokens to the client and how the renderer handles markdown blocks incrementally.\n\n";
    let code = "```rust\nfn main() {\n    let blocks = parse(text);\n    for b in blocks {\n        println!(\"{:?}\", b.kind);\n    }\n}\n```\n\n";
    let list = "- first item with some explanation text\n- second item with more detail\n- third item\n\n";
    let head = "## Section heading\n\n";
    let mut i = 0;
    while s.len() < target_bytes {
        match i % 4 {
            0 => s.push_str(para),
            1 => s.push_str(code),
            2 => s.push_str(list),
            _ => s.push_str(head),
        }
        i += 1;
    }
    s
}

fn ns_to_us(ns: u128) -> f64 {
    ns as f64 / 1000.0
}

fn main() {
    println!("=== ZaiMobile bench (host) ===\n");

    // ---- 1) Tam parse xərci ölçüyə görə ----
    println!("[markdown::parse — tam mətn]");
    println!("{:>10} {:>12} {:>14} {:>12}", "size", "parse µs", "blocks", "MB/s");
    for &kb in &[4usize, 12, 24, 48, 96] {
        let text = synth_response(kb * 1024);
        // 3 illik ölçüm
        let mut best = u128::MAX;
        for _ in 0..3 {
            let t = Instant::now();
            let blocks = markdown::parse(&text);
            let el = t.elapsed().as_nanos();
            if el < best {
                best = el;
            }
            if kb == 4 {
                // block sayını bir dəfə göstər
                println!("{:>7}KB {:>10.1} {:>14} {:>10.1}", kb, ns_to_us(best), blocks.len(), (kb as f64) / (ns_to_us(best) / 1e6) / 1.048576);
                continue;
            }
        }
        if kb != 4 {
            let blocks = markdown::parse(&text);
            println!("{:>7}KB {:>10.1} {:>14} {:>10.1}", kb, ns_to_us(best), blocks.len(), (kb as f64) / (ns_to_us(best) / 1e6) / 1.048576);
        }
    }

    // ---- 2) Streaming simulyasiyası: hazırkı model = hər flush-da TAM parse ----
    // 90ms throttle, tokenlar ~20 bayt, cavab 24KB → ~1200 token, ~120 flush
    println!("\n[streaming simulyasiya — 24KB cavab, 120 flush]");
    let text = synth_response(24 * 1024);
    let total = text.len();
    let flushes = 120;
    let step = total / flushes;
    let mut sum_full = 0u128;
    let mut worst_full = 0u128;
    let mut sum_model_build = 0u128;
    let mut worst_model_build = 0u128;
    let mut sum_text_clone = 0u128;
    for i in 1..=flushes {
        let upto = &text[..(i * step).min(total)];
        // (a) tam mətn clone (update_last_message pattern: RawMsg clone)
        let t0 = Instant::now();
        let _owned = upto.to_string();
        sum_text_clone += t0.elapsed().as_nanos();
        // (b) tam parse
        let t = Instant::now();
        let blocks = markdown::parse(upto);
        let el = t.elapsed().as_nanos();
        sum_full += el;
        if el > worst_full {
            worst_full = el;
        }
        // (c) blocks → SharedString/VecModel (raw_to_ui pattern)
        let t2 = Instant::now();
        let vm: std::rc::Rc<slint::VecModel<zai_mobile::MessageBlock>> =
            std::rc::Rc::new(slint::VecModel::default());
        for b in &blocks {
            vm.push(zai_mobile::MessageBlock {
                kind: b.kind,
                text: b.text.as_str().into(),
                lang: b.lang.as_str().into(),
            });
        }
        let el2 = t2.elapsed().as_nanos();
        sum_model_build += el2;
        if el2 > worst_model_build {
            worst_model_build = el2;
        }
        drop(vm);
    }
    println!("  hazırkı (tam parse/flush):        cəmi {:>8.1} ms | orta {:>7.1} µs/flush | ən pis {:>7.1} µs",
        ns_to_us(sum_full) / 1000.0, ns_to_us(sum_full) as f64 / flushes as f64, ns_to_us(worst_full));
    println!("  hazırkı (model qurumu/flush):     cəmi {:>8.1} ms | orta {:>7.1} µs/flush | ən pis {:>7.1} µs",
        ns_to_us(sum_model_build) / 1000.0, ns_to_us(sum_model_build) as f64 / flushes as f64, ns_to_us(worst_model_build));
    println!("  hazırkı (tam mətn clone/flush):   cəmi {:>8.1} ms | orta {:>7.1} µs/flush",
        ns_to_us(sum_text_clone) / 1000.0, ns_to_us(sum_text_clone) as f64 / flushes as f64);

    // ---- 3) push_all_messages clone pattern (uzun söhbət) ----
    // RawMsg-lərın tam clone-ı (controller.rs:124 `l(&core.messages).clone()`)
    println!("\n[messages.clone() pattern — push_all_messages]");
    struct FakeRaw {
        text: String,
    }
    println!("{:>10} {:>16} {:>16}", "mesaj", "clone µs", " RAM (MB)");
    for &n in &[50usize, 100, 200, 400] {
        let msgs: Vec<FakeRaw> = (0..n).map(|_| FakeRaw { text: synth_response(3 * 1024) }).collect();
        let ram: usize = msgs.iter().map(|m| m.text.capacity()).sum();
        // clone xərci
        let t = Instant::now();
        let cloned: Vec<FakeRaw> = msgs.iter().map(|m| FakeRaw { text: m.text.clone() }).collect();
        let el = t.elapsed().as_nanos();
        drop(cloned);
        println!("{:>10} {:>12.1} {:>14.1}", n, ns_to_us(el), ram as f64 / 1048576.0);
    }

    // ---- 4) state.json ölçüsü ----
    let p = zai_mobile::store::load();
    let s = serde_json::to_string_pretty(&p).unwrap_or_default();
    println!("\n[state.json] hazırkı yazma ölçüsü: {} bayt (hər auth/model/effort dəyişikliyində)", s.len());

    println!("\n=== BENCH DONE ===");
}
