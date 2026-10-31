# ZaiMobile — Z.ai Native (Kotlin)

[chat.z.ai](https://chat.z.ai) üçün **100% native** Android klienti.

- ❌ **WebView YOXDUR** — bütün UI native Android View-lar, bütün data native HTTP
- ❌ **API açarı YOXDUR** — adi hesabla giriş (email + şifrə), veb sessiyası təkrar istifadə olunur
- ✅ Rust/Slint kod bazası tamamilə tərk edildi — **v7.0-dan etibarən Kotlin**

## Buraxılışlar

Hər push-da GitHub Actions APK-nı qurur, imzalayır və **beta (pre-release)**
olaraq yayımlayır: [releases](https://github.com/Abdullayews/ZaiMobile/releases)

## Özəlliklər

- **Giriş**: Google / Email / Github düymələri (saytın görünüşü ilə) → email + şifrə
  ilə birbaşa native giriş; "Skip for now" = qonaq tokeni; captcha fallback
  (brauzerdə giriş + token köçürmə, clipboard-da `eyJ…` aşkarlanması)
- **Chat**: SSE streaming (delta mətn + fazalar: *Thinking… / Searching the web…*),
  markdown render (başlıqlar, **qalın**, *meyl*, `kod`, kod bloqları + copy,
  siyahılar, sitatlar, keçidlər), tap-to-retry, dayandır
- **Söhbətlər**: sidebar — siyahı, açma, silmə, başlıq sinxronu
- **Modellər**: siyahı + thinking effort (low/high/max) seçimi
- **Fayl əlavələri**: SAF seçici (60 MB limit) → multipart upload →
  image_url / video_url / file_url content massivi; böyük şəkillər avtomatik
  2048px JPEG-ə endirilir
- **Fullscreen**: immersive sticky + WindowInsetsController (API 30+) +
  systemUiVisibility fallback + IME insets — tam edge-to-edge

## Texniki

| | |
|---|---|
| Dil | Kotlin 2.0.21, korutinlər (yalnız asılılıq: `kotlinx-coroutines-android`) |
| UI | Proqramatik native View-lar (XML layout YOX — kompilyator hər xassəni yoxlayır) |
| Şəbəkə | `HttpURLConnection` + qlobal `CookieManager` (WAF `acw_tc` cookie-ləri) |
| Protokol | veb tətbiqinin dəqiq protokolu: HMAC-SHA256 imza, `/api/v2/chat/completions` SSE, WAF retry |
| Build | Gradle 8.10.2 + AGP 8.7.3, compileSdk 34, minSdk 24, JDK 21 |
| Ölçü | ~1.7 MB APK |

### Struktur

```
app/src/main/java/zai/mobile/
  LoginActivity.kt    giriş ekranı (email/şifrə, qonaq, captcha fallback)
  ChatActivity.kt     chat UI: nav, sidebar, mesajlar, kompozitor, fullscreen
  ZaiApi.kt           HTTP client: bütün endpointlər, WAF retry, multipart upload
  ChatEngine.kt       completion orkestrasiyası: chat yarat → SSE → bərpa → saxla
  Signature.kt        veb imza alqoritmi (HMAC-SHA256, URLSearchParams)
  SseParser.kt        SSE hadisə təhlili (4 format, çoxsətirli birləşmə)
  ChatParse.kt        chat detalı → mesaj zənciri
  MarkdownParse.kt    markdown parser (xalis Kotlin, JVM-də test olunur)
  Markdown.kt         markdown → Android spannable view-lar
  Session.kt          SharedPreferences state (token, model seçimi)
  Models.kt           data modelləri + sealed StreamEvent
  Ui.kt               chat.z.ai rəng tokenləri, UI köməkçiləri, fullscreen/insets

kotlin-dersleri/      20 dərslık Kotlin kursu (yazılmazdan əvvəl tamamlanıb)
```

### Lokal build

```bash
./gradlew assembleRelease
# APK: app/build/outputs/apk/release/app-release-unsigned.apk
```

## Lisenziya

[LICENSE](LICENSE)
