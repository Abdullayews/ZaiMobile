# Z.ai Mobile — 100% Native Rust Android Client

chat.z.ai veb tətbiqinin tam native Android klienti. **WebView yoxdur** — UI [Slint](https://slint.dev) (Skia renderer) ilə, şəbəkə reqwest ilə, veb protokolu (imza HMAC-SHA256 + SSE) Rust-da implementasiya olunub.

## v4.0 — Tam sıfırdan yenidən yazılıb (Slint)

| Sahə | Həll |
|---|---|
| UI | Slint 1.18, `backend-android-activity-06` (NativeActivity + Skia) |
| Klaviatura/IME | Slint-in rəsmi Android IME dəstəyi (javahelper) |
| Clipboard | JNI vasitəsilə ClipboardManager.setPrimaryClip |
| Şəbəkə | reqwest (rustls) + cookie store + WAF 405 avtomatik retry |
| Axın | /api/v2/chat/completions SSE + imza + 3 retry + 150s watchdog |
| Giriş | Qonaq / Email+şifrə / Account token (localStorage) |
| Markdown | Başlıq, siyahı, sitat, kod blokları (dil + copy) |
| Saxlanma | /data/data/zai.mobile/files/state.json |

## Build

GitHub Actions hər push-da avtomatik imzalı APK buraxır (beta pre-release):
`Actions → Build APK (Rust) → Releases`

Lokal desktop preview: `cargo run` (X11/Wayland)

## Struktur

```
ui/*.slint     — UI (theme, icons-Path, chat, sidebar, modals, state)
src/api.rs     — HTTP client + imza + WAF retry
src/stream.rs  — SSE completion axını + bərpa
src/controller.rs — callback-lər, worker-lər, state sinxronu
src/markdown.rs   — markdown → bloklar
src/clipboard.rs  — JNI clipboard
src/lib.rs     — android_main + desktop main
```
