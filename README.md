# Z.ai Mobile 📱

**chat.z.ai** üçün **tam nativ** Android müştərisi — WebView yox, öz interfeysi olan əsl proqram. Mesanjer kimi işləyir, chat.z.ai-nin rəsmi backend API-si ilə birbaşa danışır.

## 📥 APK endir

1. **[Releases](https://github.com/Abdullayews/ZaiMobile/releases)** səhifəsinə keçin
2. Ən son buraxılışdan **`ZaiMobile-v2.0-native.apk`** faylını endirin
3. Telefonda faylı açın → "Naməlum mənbələrdən quraşdırma" icazəsi verin → quraşdırın

Hər `main` branch-ına push edildikdə APK **avtomatik yenidən qurulur** (GitHub Actions).

## ✨ Xüsusiyyətlər (v2.0 — nativ)

- 🗨️ **Tam nativ UI** — mesaj qabarcıqları, markdown render, Material Design 3
- ⚡ **Stream cavablar** — mətn real-time, hissə-hissə gəlir (SSE)
- 🧠 **Düşünmə rejimi** — modelin reasoning prosesini göstər/gizlət
- 📚 **Tarixçə** — bütün söhbətlər cihazda saxlanılır (SQLite), heç bir serverə göndərilmir
- 🤖 **Model seçimi** — GLM-5.3-Flash (qonaq, pulsuz), GLM-5.3 / GLM-5.2 (premium)
- 👤 **Qonaq rejimi** — heç bir qeydiyyat lazım deyil, avtomatik anonim hesab
- 🔑 **Premium token dəstəyi** — chat.z.ai hesabınızın tokenini Parametrlərdən əlavə edin
- 🌙 Tünd rejim, mesaj kopyalama, cavabı dayandırma, xətada yenidən cəhd

## 🔧 Texniki arxitektura

| Təbəqə | Texnologiya |
|--------|-------------|
| UI | Java + Material 3 + RecyclerView + Markwon (markdown) |
| Şəbəkə | OkHttp + SSE (Server-Sent Events) |
| Məlumat | SQLite (chats, messages) + SharedPreferences |
| Backend | `chat.z.ai` rəsmi API: `/api/v1/auths/`, `/api/models`, `/api/v2/chat/completions` |

Mühit: Android 7.0+ (minSdk 24), hədəf Android 14 (API 34).

## ⚠️ Məlum məhdudiyyətlər

- Fayl/şəkil göndərmə hələ yoxdur (növbəti versiyalarda)
- Premium modellər üçün öz hesab tokeniniz lazımdır (Parametrlər → Hesab)
- Nadir hallarda server captcha tələb edə bilər — bu halda rəsmi saytdan istifadə edin

## 🔐 İmzalama

Release APK repoda saxlanmayan `zai-release.keystore` ilə imzalanır. Açar və parollar GitHub Secrets-də saxlanılır. Gələcək yeniləmələr eyni açarla imzalanmalıdır.

## 📜 Lisenziya

MIT — bax [LICENSE](LICENSE)
