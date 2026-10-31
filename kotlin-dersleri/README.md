# Kotlin Dərsləri — ZaiMobile üçün tam kurs

ZaiMobile-ın Kotlin ilə sıfırdan yenidən yazılmasından ƏVVƏL tamamlanmış
sistematik Kotlin kursu. Hər dərs müstəqil `.kt` faylıdır, `main()` funksiyası
ilə işə salınır və daxili `check()` iddiaları ilə **özünü yoxlayır** —
"hamısı keçdi ✔" çap olunmadıqca dərs keçilməyib.

## Kursun gedişi

| # | Fayl | Mövzu |
|---|------|-------|
| 01 | `Ders01_esaslar.kt` | val/var, tiplər, string şablonları, Any/Unit/Nothing |
| 02 | `Ders02_nazaret_axini.kt` | when (bütün formaları), for/while, range, etiketlər |
| 03 | `Ders03_funksiyalar.kt` | default/named arqumentlər, vararg, infix, operatorlar, tailrec, `::` |
| 04 | `Ders04_null_tehlukesizliyi.kt` | `?` `?.` `?:` `!!`, let, lateinit, lazy, platform tipləri |
| 05 | `Ders05_klaslar.kt` | konstruktorlar, init, custom accessor, görünürlük, nested/inner, companion, object |
| 06 | `Ders06_data_enum_sealed.kt` | data class (copy/destructuring), enum, sealed + exhaustive when |
| 07 | `Ders07_irs_interfeysler.kt` | open/abstract/override, interfeys default-ları, smart cast |
| 08 | `Ders08_lambdalar.kt` | HOF, trailing lambda, receiver-lı literal, scope funksiyaları, DSL |
| 09 | `Ders09_kolleksiyalar.kt` | List/Map/Set, transformasiyalar, Sequence (lazy) fərqi |
| 10 | `Ders10_genisletmeler.kt` | extension funksiya/property, nullable receiver, statik bağlanma dərsi |
| 11 | `Ders11_generics.kt` | bound-lar, variance (`in`/`out`), star-projection, reified |
| 12 | `Ders12_istisnalar.kt` | try-ifadə, xüsusi istisnalar, Nothing, runCatching/Result |
| 13 | `Ders13_delegasiya.kt` | by lazy/observable/vetoable/notNull, by map, sinif `by`, xüsusi delegate |
| 14 | `Ders14_java_interop.kt` | JDK çağırışı, SAM, platform tipləri, @JvmStatic/@JvmField/@JvmOverloads |
| 15 | `Ders15_korutinler.kt` | suspend, launch/async, Dispatchers, ləğv, timeout, supervisor, Channel, Mutex |
| 16 | `Ders16_flow.kt` | flow operatorları, debounce/buffer/combine, retry/catch, StateFlow/SharedFlow |
| 17 | `Ders17_io_ve_regex.kt` | Fayl I/O (use/useLines/walkTopDown), Regex, adlandırılmış qruplar |
| 18 | `Ders18_sebeka_webviewsiz.kt` | ★ **WebView YOXDUR**: HTTP GET/POST, JSON, HTML scraping, SSE axını, cookie |
| 19 | `Ders19_android_esaslari.kt` | Activity lifecycle, View/ListView/Adapter, SharedPreferences, UI thread |
| 20 | `Ders20_fullscreen.kt` | ★ Immersive fullscreen, WindowInsetsController, insets, IME, cutout |

## Necə işə salmaq

Dərslər 01–18 adi JVM-də işləyir:

```bash
# sadə dərs:
kotlinc Ders01_esaslar.kt -d out && java -cp out Ders01_esaslarKt

# korutin/flow dərsləri (15, 16) — kotlinx-coroutines tələb edir:
kotlinc Ders15_korutinler.kt -cp kotlinx-coroutines-core-jvm-1.8.1.jar -d out
java -cp out:kotlinx-coroutines-core-jvm-1.8.1.jar:kotlin-stdlib.jar Ders15_korutinlerKt

# Ders18 — org.json tələb edir (Android-də daxilidir):
kotlinc Ders18_sebeka_webviewsiz.kt -cp json-20240303.jar -d out
java -cp out:json-20240303.jar:kotlin-stdlib.jar Ders18_sebeka_webviewsizKt
```

Dərslər 19–20 Android runtime tələb edir — **android.jar-ə qarşı kompilyasiya
yoxlaması** ilə təsdiqlənib:

```bash
kotlinc Ders19_android_esaslari.kt -cp $ANDROID_HOME/platforms/android-34/android.jar -d out
kotlinc Ders20_fullscreen.kt       -cp $ANDROID_HOME/platforms/android-34/android.jar -d out
```

## Nəticə (yoxlanış tarixi: 2026-10-31)

- Dərslər 01–18: **hamısı işə salınıb, bütün check() iddiaları keçib**
  (Dərs 18 REAL şəbəkə sorğuları ilə: example.com HTML, jsonplaceholder
  REST API, httpbin POST, SSE təhlili).
- Dərslər 19–20: **android-34 android.jar-ə qarşı kompilyasiya olunub**.

Kurs bitdikdən sonra ZaiMobile-ın Kotlin implementasiyası yazılıb
(`app/src/main/java/zai/mobile/`).
