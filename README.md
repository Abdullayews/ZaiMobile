# ZaiMobile — Z.ai Native Android (100% Rust)

Z.ai (chat.z.ai) üçün tam **native** Android klienti. **WebView yoxdur** — bütün interfeys
Rust + egui ilə GPU-da render olunur, bütün şəbəkə əməliyyatları Rust-da birbaşa
chat.z.ai veb API-si üzərindən gedir.

## Arxitektura

```
┌─────────────────────────────────┐
│  zai.mobile (APK, arm64-v8a)    │
│                                 │
│  ┌───────────────────────────┐  │
│  │  libzai_mobile.so (Rust)  │  │
│  │                           │  │
│  │  egui (GPU render)        │  │
│  │  reqwest (HTTP/SSE)       │  │
│  │  chat.z.ai veb protokolu  │  │
│  └───────────────────────────┘  │
│  NativeActivity (sistem)        │
└─────────────────────────────────┘
```

- **UI**: eframe/egui 0.37, Carlito fontu (Azərbaycan əlifbası: ə, ö, ğ, ş, ı tam dəstək)
- **Şəbəkə**: reqwest + rustls, cookie store, brauzer warm-up
- **Protokol**: chat.z.ai daxili API (Open WebUI əsaslı)
  - `GET /api/v1/auths/` — avtomatik qonaq sessiyası
  - `POST /api/v1/chats/new` — söhbət yaratma
  - `POST /api/v2/chat/completions` — mesaj göndərmə (X-Signature + SSE)
  - `GET /api/v1/chats/` — söhbət tarixçəsi
- **Signature**: veb tətbiqinin HMAC-SHA256 imza alqoritmi (reverse-engineered)

## Xüsusiyyətlər

- Qonaq rejimi (avtomatik) və hesab tokeni ilə daxilolma
- Canlı cavab axını (SSE) — mətn yazıldıqca görünür
- Düşünmə / axtarış fazaları göstərilir
- Markdown render: başlıqlar, **bold**, `kod`, kod blokları, keçidlər
- Söhbət tarixçəsi, model seçimi (GLM-5.3 / Flash / 5.2)
- Stop düyməsi, mesaj kopyalama

## Build

GitHub Actions avtomatik APK yığır (`.github/workflows/build.yml`):
Rust (aarch64-linux-android) → aapt2 → zipalign → apksigner (imzalı).

**Hər push → yeni beta release:** `main`-ə hər push-da CI APK qurur, imzalayır və
`v2.0.0-beta.N` teqi ilə **pre-release (Beta)** kimi dərc edir. Stabil "Latest"
buraxılışı yalnız beta mərhələsi bitəndə olacaq.

Endirmək üçün: **Releases** sekmesində ən son beta → `ZaiMobile-v2.0.0-beta.N.apk`.

Lokal desktop testi:

```bash
cargo run --bin smoke --release   # şəbəkə protokol testi
cargo run                          # desktop GUI (Linux)
```

## Lizensiya

MIT
