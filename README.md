# Z.ai Mobile 📱

**chat.z.ai** saytının yüngül, WebView əsaslı Android tətbiqi. Brauzerdəki donma və yaddaş problemlərini aradan qaldırmaq üçün hazırlanıb — eyni interfeys, amma müstəqil tətbiq kimi işləyir.

## 📥 APK-nı endir

1. **[Releases](https://github.com/Abdullayews/ZaiMobile/releases)** səhifəsinə keçin
2. Ən son buraxılışdan **`ZaiMobile-v1.0.apk`** faylını endirin
3. Telefonda faylı açın → "Naməlum mənbələrdən quraşdırma" icazəsi verin → quraşdırın

Hər `main` branch-ına push edildikdə APK **avtomatik yenidən qurulur** (GitHub Actions).

## ✨ Xüsusiyyətlər

- 🗨️ chat.z.ai birbaşa tətbiqdə açılır (login, söhbət, bütün funksiyalar)
- 🖼️ Şəkil və fayl göndərmə (file chooser, çoxsaylı seçim dəstəyi)
- ⬇️ Fayl endirmə ( sistem Download Manager, bildirişlə birlikdə)
- 👆 Aşağı çəkib buraxaraq yeniləmə (swipe-to-refresh)
- 🔄 **Donma bərpası:** 45 saniyədən çox çəkən yükləmə avtomatik reload olunur; WebView render-prosesi qəzalandıqda tətbiq özünü bərpa edir; internet bərpa olanda səhifə avtomatik yenidən yüklənir
- 🎤 Səsli giriş üçün mikrofon dəstəyi
- 🎬 Tam ekran video dəstəyi
- 🌙 Tünd rejim uyğunluğu
- 🔗 Xarici keçidlər brauzerdə açılır (z.ai domenləri tətbiqdə qalır)

## ⚠️ Məlum məhdudiyyətlər

- **Google/Apple ilə giriş** özəl təhlükəsizlik siyasətlərinə görə WebView daxilində bloklanır — e-poçt/telefon ilə giriş istifadə edin
- `blob:` formatlı endirmələr (bəzi sayt xüsusiyyətləri) dəstəklənmir

## 🔧 Mənbədən qurma (özünüz)

```bash
git clone https://github.com/Abdullayews/ZaiMobile.git
cd ZaiMobile
gradle assembleRelease
# Nəticə: app/build/outputs/apk/release/app-release.apk
```

Və ya sadəcə **Actions** tab-ında "Android APK Build" workflow-unu işə salın (Run workflow).

## 🔐 İmzalama

Release APK repoda saxlanmayan `zai-release.keystore` ilə imzalanır. Açar və parollar GitHub Secrets-də saxlanılır:

| Secret | Təyinat |
|--------|---------|
| `KEYSTORE_BASE64` | base64 ilə kodlanmış keystore faylı |
| `KEYSTORE_PASSWORD` | keystore parolu |
| `KEY_ALIAS` | açar ləqəbi (`zai`) |
| `KEY_PASSWORD` | açar parolu |

Gələcək yeniləmələr eyni açarla imzalanmalıdır — əks halda istifadəçilər tətbiqi silib yenidən quraşdırmalı olur.

## 📜 Lisenziya

MIT — bax [LICENSE](LICENSE)
