<div align="center">

# ⚡ ZaiMobile

**A 100% native Rust Android client for Z.ai — no WebView, no Java UI, no shortcuts.**

*The Z.ai web protocol — HMAC-SHA256 request signing, SSE streaming, sessions — reimplemented from scratch in Rust. The whole UI is Slint on Skia.*

[![Rust](https://img.shields.io/badge/Rust-2021-DEA584?style=for-the-badge&logo=rust&logoColor=black)](https://rust-lang.org)
[![Slint](https://img.shields.io/badge/UI-Slint_1.18-0CC6D6?style=for-the-badge&logo=data:image/svg+xml;base64,&logoColor=white)](https://slint.dev)
[![Android](https://img.shields.io/badge/Android-API%2024%2B-3DDC84?style=for-the-badge&logo=android&logoColor=black)](https://developer.android.com)
[![License: MIT](https://img.shields.io/badge/License-MIT-3DA639?style=for-the-badge)](LICENSE)
[![CI](https://img.shields.io/badge/GitHub_Actions-signed%20APK-2088FF?style=for-the-badge&logo=githubactions&logoColor=white)](https://github.com/Abdullayews/ZaiMobile/actions)

</div>

---

## 💡 What is this?

ZaiMobile is a native Android chat client for **chat.z.ai** (the GLM chat
platform). The stubborn idea behind it: build the *entire* app in
**Rust** — UI, networking, streaming, persistence — and let Java do nothing
but host the NativeActivity.

That means:

- **No WebView.** The interface is rendered with **Slint 1.18** on the
  Android backend (`backend-android-activity-06`, Skia renderer).
- **No reverse-engineered hybrid tricks.** The web protocol is implemented
  properly in Rust: request signing with **HMAC-SHA256**, cookie sessions,
  and **SSE streaming** of `/api/v2/chat/completions` with 3 retries and a
  150-second watchdog.
- **Everything Rust.** `reqwest` (rustls) for networking, serde for state,
  a hand-rolled Markdown renderer, and JNI only where the platform forces
  it (clipboard).

The result is a small, fast, native chat app with real streaming — and a
[`worklog.md`](worklog.md) that documents every battle along the way.

## ✨ Features

| | |
|---|---|
| 💬 **Full chat flow** | Guest / email + password / account-token login — sessions survive restarts |
| ⚡ **SSE streaming** | Token-by-token completions, automatic recovery, 3 retries, 150s watchdog |
| 🔐 **Protocol in Rust** | HMAC-SHA256 request signing + WAF 405 auto-retry implemented from scratch |
| 📝 **Markdown rendering** | Headings, lists, quotes, code blocks with language tags + copy button |
| 📋 **JNI clipboard** | `ClipboardManager.setPrimaryClip` bridged through JNI |
| ⌨️ **Proper Android IME** | Slint's official input-method support (javahelper) |
| 💾 **State persistence** | `/data/data/zai.mobile/files/state.json` |
| 🖥️ **Desktop preview** | Same codebase runs on Linux (`cargo run`, winit + femtovg) |

## 🛠️ Tech stack

| Layer | Technology |
|---|---|
| Language | **Rust 2021 edition** |
| UI | **Slint 1.18.1** — Android: `backend-android-activity-06` (NativeActivity + Skia) · Desktop: `backend-winit` + femtovg |
| Networking | **reqwest 0.12** (rustls-tls, cookies, gzip, multipart) |
| Crypto | `sha2` (HMAC-SHA256 signatures) |
| Data | `serde` / `serde_json`, `chrono`, `uuid`, `image` |
| Platform | `jni` 0.21 + `ndk-context` |
| Build | `slint-build`, `cargo-apk`-style packaging, **GitHub Actions** CI |
| Target | `aarch64-linux-android` · minSdk 24 · targetSdk 34 |

## 📁 Repository structure

```
ZaiMobile/
├── src/
│   ├── api.rs         # HTTP client + request signing + WAF retry
│   ├── stream.rs      # SSE completion stream + recovery
│   ├── controller.rs  # Callbacks, workers, state sync
│   ├── markdown.rs    # Markdown → render blocks
│   ├── clipboard.rs   # JNI clipboard bridge
│   └── lib.rs         # android_main + desktop main
├── ui/                # *.slint — theme, icons, chat, sidebar, modals, state
├── assets/ · res/     # Android resources
├── AndroidManifest.xml · build.rs · Cargo.toml
├── scripts/           # Build helper scripts
├── .github/           # CI — builds a signed APK on every push
└── worklog.md         # The development diary
```

## 🚀 Getting the APK

The easiest way: **GitHub Actions builds a signed APK on every push**
(beta pre-release).

> `Actions` → `Build APK (Rust)` → grab the artifact from the run, or look
> under `Releases`.

## 🧪 Run locally (desktop preview)

The same crate runs as a desktop app on Linux (X11 / Wayland):

```bash
git clone https://github.com/Abdullayews/ZaiMobile.git
cd ZaiMobile
cargo run
```

## 📄 License

MIT — see [LICENSE](LICENSE).
