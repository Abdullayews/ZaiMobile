# ZaiMobile — Z.ai Native Android (100% Rust)

Z.ai (chat.z.ai) üçün tam **native** Android klienti. **WebView yoxdur** — bütün interfeys
Rust + egui ilə GPU-da render olunur, bütün şəbəkə əməliyyatları Rust-da birbaşa
chat.z.ai veb API-si üzərindən gedir.

**v3.0 — Veb UI-nun piksel-dəqiq native replikası:** brauzerdə chat.z.ai necə görünürsə,
tətbiq də tam eyni görünür (işıq tema, eyni başlıq, kartlar, kapsul, modallar).

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

- **UI**: eframe/egui 0.37 — veb dizayn tokenləri (`src/theme.rs`): #F0F0F0 fon,
  #0D0D0D mətn, #EAEAEA balonlar, r24 giriş kapsulu; Carlito (Azərbaycan əlifbası
  tam dəstək) + Crimson Text (serif başlıq) + DejaVu Mono
- **İkonlar**: veb-dən çıxarılmış gerçek SVG-lər, ağ rəngdə rasterləşdirilmiş
  (`assets/icons/`), runtime tint sistemi (`src/icons.rs`)
- **Kart şəkilləri**: veb CDN-dən endirilmiş orijinallar (`assets/cards/`)
- **Şəbəkə**: reqwest + rustls, cookie store, brauzer warm-up
- **Protokol**: chat.z.ai daxili API (Open WebUI əsaslı)
  - `GET /api/v1/auths/` — avtomatik qonaq sessiyası
  - `POST /api/v1/chats/new` — söhbət yaratma
  - `POST /api/v2/chat/completions` — mesaj göndərmə (X-Signature + SSE)
  - `GET /api/v1/chats/` — söhbət tarixçəsi
- **Signature**: veb tətbiqinin HMAC-SHA256 imza alqoritmi (reverse-engineered)

## Ekranlar (veb strukturu: tək ekran + overlay-lər)

- **Landing**: nav (tarixçə / yeni söhbət / API / Sign in), "More than coding..."
  serif başlıq, 5 təklif kartı, "Send a Message" kapsulu, footer linkləri
- **Söhbət**: istifadəçi balonları (#EAEAEA, r12, sağda), cavablar markdown + Copy,
  xətada "No response, Please try again later." + Regenerate
- **Tarixçə drawer**: New Chat, axtarış, tarix qrupları (Today / Yesterday / ...),
  profil + Sign in / Sign out, pulldown-refresh
- **Sign-in modal**: Welcome to Z.ai — Continue with Google / Email / Github /
  Skip for now (veb görünüşü) + işləyən token daxilolma (Email ekranında)
- **Model popup**: GLM modelləri + Effort slider + "Try advanced features"
- **API menyu**: Product / Documents bölmələri

## Xüsusiyyətlər

- Qonaq rejimi (avtomatik) və hesab tokeni ilə daxilolma
- Canlı cavab axını (SSE) — mətn yazıldıqca görünür, Thinking... animasiyası
- Markdown render: başlıqlar, **bold**, `kod`, kod blokları, keçidlər
- Token yapışdırma / mesaj kopyalama (JNI sistem buferi)
- Android back düyməsi: overlay-ləri bağlayır, heç biri açıq deyilsə arxa plana
- Pulldown-refresh (tarixçə + söhbət), avtomatik bərpa (retry + 180s watchdog +
  401-də sessiya yeniləmə)

## Build

GitHub Actions avtomatik APK yığır (`.github/workflows/build.yml`):
Rust (aarch64-linux-android) → aapt2 → zipalign → apksigner (imzalı).

**Hər push → yeni beta release:** `main`-ə hər push-da CI APK qurur, imzalayır və
`v3.0.0-beta.N` teqi ilə **pre-release (Beta)** kimi dərc edir. Stabil "Latest"
buraxılışı yalnız beta mərhələsi bitəndə olacaq.

Endirmək üçün: **Releases** sekmesində ən son beta → `ZaiMobile-v3.0.0-beta.N.apk`.

Lokal desktop testi:

```bash
cargo run --bin smoke --release   # şəbəkə protokol testi
cargo run                          # desktop GUI (Linux)
cargo run --bin preview --release # desktop preview (ZAI_OPEN=signin|model|drawer|api|chat)
```

UI yoxlaması (headless screenshot): `ZAI_OPEN=<rejim>` ilə preview işə salın,
Xvfb + ffmpeg x11grab ilə screenshot al (`scripts/capture.sh` nümunəsi).

## Lizensiya

MIT
