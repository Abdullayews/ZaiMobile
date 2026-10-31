# ZaiMobile — Z.ai Native (Kotlin)

[chat.z.ai](https://chat.z.ai) üçün **100% native** Android klienti.

- ❌ **WebView YOXDUR** — bütün UI native Android View-lar, bütün data native HTTP
- ❌ **API açarı YOXDUR** — adi hesabla giriş (email + şifrə), veb sessiyası təkrar istifadə olunur
- ❌ **Statik data YOXDUR** — modellər, söhbətlər, tarixçə, başlıqlar, adlar: hamısı
  canlı `chat.z.ai` API-dən gəlir. Yüklənmədikdə düz xəta + Retry göstərilir,
  heç vaxt saxta fallback yoxdur
- ✅ Rust dövrü (v6) tam tərk edildi; v7 Kotlin — sıfırdan, spec-əsaslı yenidənqurma

## Buraxılışlar

Hər push-da GitHub Actions APK-nı qurur, imzalayır və **beta (pre-release)**
olaraq yayımlayır: [releases](https://github.com/Abdullayews/ZaiMobile/releases)

## Özəlliklər

- **Boot**: WAF warm-up → token yoxlama / qonaq tokeni → chat; şəbəkə xətasında
  düz xəta + Retry (saxta data yoxdur). Gözlənilməz çıxış → növbəti açılışda
  **crash hesabatı** (stack kopyalanabilir)
- **Giriş** (toolbar "Sign in"): email + şifrə native POST; server captcha tələb
  etsə → sistem brauzerində bir dəfə giriş + token köçürmə (clipboard-da `eyJ…`
  aşkarlanır, istifadəçi razılığı ilə) və ya tokeni əllə yapışdır
- **Chat**: SSE streaming (delta mətn + fazalar: *Thinking… / Searching the web…*),
  markdown render (başlıqlar, **qalın**, *meyl*, `kod`, keçidlər, siyahılar,
  sitatlar, kod blokları + copy), tap-to-retry, stop (qismən mətn saxlanılır)
- **Söhbətlər**: sidebar — canlı siyahı (səhifələnmiş), açma (server tarixçəsi:
  currentId → parentId zənciri), silmə; başlıqlar server tərəfindən generasiya
  olunur və axından sonra canlı sinxronlaşır
- **Modellər**: canlı `/api/models` (qonaq filtri: min_available_level ≤ 1) +
  thinking effort seçimi — modelin öz səviyyələrindən
- **Fayl əlavələri**: SAF seçici (heç bir storage icazəsi lazım deyil) → 60 MB
  limit → multipart upload → image_url / video_url / file_url; böyük şəkillər
  2048px JPEG-ə endirilir
- **Protokol dəqiqliyi**: HMAC-SHA256 imza (RFC 4231 vektoru ilə test olunub),
  WAF bloklarında backoff ilə təkrar (hər cəhd üçün YENİ imza), çoxsətirli SSE
  birləşdirmə, 150s sükut watchdog-u, 401-də qonaq self-heal, rate-limit/busy
  friendly mesajlar

## Arxitektura

```
zai.mobile
├── protocol/   # Transport (HttpURLConnection) · ZaiHttp (WAF+cookie) ·
│               # Signature (HMAC) · Auth · Models · Chats · Completion (SSE) ·
│               # Upload · Errors — hamısı spec-ə dəqiq uyğun
├── state/      # Session: state.json (atomik yazma), deviceId (uid_+13)
├── engine/     # ChatEngine: yaratma → axın (4 cəhd + 3 təkrar) → saxlama
└── ui/         # Boot/Login/Chat Activity-lər · Markdown · Theme/Widgets
```

Yeganə runtime asılılıq: `kotlinx-coroutines-android`. HTTP — HttpURLConnection,
JSON — org.json, crypto — javax.crypto. Şəbəkə kəsiyi (`Transport`) testlərdə
`ScriptedTransport` ilə əvəz olunur.

## Testlər

- **Unit (Robolectric)**: imza vektoru, SSE multi-line/[DONE]/EOF, faza xəritməsi,
  WAF təkrarı, pagination, zəncir gəzintisi, upload parse, markdown, session
- **Startup smoke (Robolectric)**: hər ekran REAL lifecycle ilə açılır; tam
  göndərmə axını (yaz → göndər → stream → render); login captcha→token; models
  xətasında fallback YOXDUR; crash hesabatı
- **Canlı (LIVE_TEST=1)**: real chat.z.ai — qonaq token, modellər, signin-captcha;
  datacenter IP-dən tam dövr WAF/captcha ilə məhdudlaşır (telefonda işləyir)

```bash
gradle test                      # unit + smoke
LIVE_TEST=1 gradle test          # + canlı protokol
```
