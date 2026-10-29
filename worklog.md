# ZaiMobile v5.0 — İş jurnalı

## 2026-10-29 — v5.0 "Eyni sistem" (The Same System)

### Kök səbəb: 4 cəhd, 1 dərs
| Versiya | UI texnologiyası | Nəticə |
|---|---|---|
| v2.x | egui | "işləmir" — IME/şəbəkə/render qeyri-stabil |
| v3.0 | egui + piksel replika | "heç yaxın deyil" |
| v4.0 | Slint 1.18 + Skia | "olmur, heç yaxın belə ola bilmir" |

**Dərs:** Native UI toolkit-i (egui/Slint) hazır veb app-in (Next.js/Tailwind)
görünüşünü heç vaxt piksel-dəqiq təkrarlaya bilmir. İstifadəçinin tələbi:
"saytdakı halı kimi — eyni sistem, eyni məntiq, loqodan ən kiçik elementə qədər."

### v5.0 həlli — "eyni sistem" hərfən
App daxilində chat.z.ai-in ÖZ mühərriki işləyir (system WebView = Chrome mühərriki =
istifadəçinin mobil brauzerində gördüyünün eynisi). UI, məntiq, API protokolu,
WAF/captcha, SSE, fayl yükləmə — hamısı saytın ÖZ kodu. Yenidən yazmaq yoxdur →
davam gətirmək mümkün deyil.

Quruluş:
- `src-tauri/` — Rust örtüyü (tauri 2.12 + wry 0.53): pəncərə + hər səhifəyə
  yeridilən bridge JS.
- `ui/index.html` — brendli bağlanır səhifəsi (Z.ai loqosu, probe, redirect).
- `src-tauri/src/bridge_js.rs` — pull-to-refresh (Chrome həddə eyni), offline
  heartbeat + brendli overlay, a[download]/blob endirmə kəsici (fetch→base64→
  MediaStore), target=_blank/mailto/tel → sistem brauzeri, window.open kəsici.
- `gen/android .../MainActivity.kt` — __zaiNative JavascriptInterface
  (openExternal, saveDownload→MediaStore, toast), Back düyməsi
  (webview.goBack | moveTaskToBack), adjustResize (klaviatura).
- CI: `cargo tauri android build --apk --target aarch64` → zipalign →
  imza (köhnə keystore secrets — imza dəyişmir, upgrade yolu qalır) → beta prerelease.

### Canlı yoxlamalar (headless Chromium + real chat.z.ai)
- Connector: probe → `location.replace('https://chat.z.ai/')` ✓
- Bridge real saytda: _blank→external ✓, mailto→external ✓,
  a[download] → fetch→base64(21KB real payload)→saveDownload ✓,
  detached a.click() ✓, window.open ✓, offline event→brendli overlay ✓,
  pull-to-refresh scaleX(1) (hasTouch) ✓, toast "Saved: probe.html" ✓
- APK lokal build: uğurlu (6.6MB, universal/aarch64, imzalı sınaq) ✓

### Yadda saxla
- `prod-fe-1.1.123` (saytın hazırkı frontend versiyası) — artıq əhəmiyyət daşımır:
  sayt özünü yeniləyir, app avtomatik sinxron qalır.
- Rəsmi assetlər: `chat.z.ai/static/logo.svg` (CDN) — offline overlay + connector
  + app ikonu üçün istifadə olundu.
