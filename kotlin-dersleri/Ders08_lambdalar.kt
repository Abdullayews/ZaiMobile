// ============================================================================
// DƏrs 08 — LAMBDA VƏ FUNKSİONAL PROQRAMLAŞDIRMA
// ----------------------------------------------------------------------------
// Lambda = adsiz funksiya. Kotlin-də lambda son parametr olduqda mötərizədən
// ÇIXARILIR (trailing lambda) — bu, bütün Kotlin API-nin "görməli" üslubudur.
// ============================================================================

// Yüksək dərəcəli funksiyalar — öz map/filter-imizi yazırıq:
fun <T, R> mənXəritə(list: List<T>, f: (T) -> R): List<R> {
    val nəticə = ArrayList<R>(list.size)
    for (element in list) nəticə.add(f(element))
    return nəticə
}
fun <T> mənFiltr(list: List<T>, şərt: (T) -> Boolean): List<T> {
    val nəticə = ArrayList<T>()
    for (e in list) if (şərt(e)) nəticə.add(e)
    return nəticə
}

// Lambda ALICI İLƏ (function literal with receiver) — DSL-in əsası:
// blok daxilində `this` = qəbul edən obyekt → buildString/apply belə işləyir
fun sənəd(qur: StringBuilder.() -> Unit): String = StringBuilder().apply(qur).toString()

fun main() {
    val ədədlər = listOf(1, 2, 3, 4, 5, 6)

    // ---------- 1. Lambda sintaksisi: { parametrlər -> gövdə } ----------
    val kvadrat = { x: Int -> x * x }            // tipi: (Int) -> Int
    check(kvadrat(7) == 49)

    // TƏK parametr üçün ad verməyə ehtiyac yoxdur → `it`:
    val cüt = ədədlər.filter { it % 2 == 0 }
    check(cüt == listOf(2, 4, 6))

    // Bir neçə parametr:
    val cəmlənmiş = ədədlər.fold(0) { akk, n -> akk + n }
    check(cəmlənmiş == 21)

    // Parametrləri DESTRUKTUR etmək (map-larda çox rahat):
    val cütlüklər = mapOf("a" to 1, "b" to 2)
    val açarlar = cütlüklər.map { (k, v) -> "$k=$v" }
    check(açarlar == listOf("a=1", "b=2"))

    // ---------- 2. Trailing lambda + öz funksiyalarımız ----------
    check(mənXəritə(ədədlər) { it * 10 } == listOf(10, 20, 30, 40, 50, 60))
    check(mənFiltr(ədədlər) { it > 3 } == listOf(4, 5, 6))

    // ---------- 3. SCOPE FUNKSİYALARI — 5 qılınc ustası ----------
    // let   — obyekti bloka salır, NƏTİCƏ blokun qiymətidir; null-yoxlama üçün
    // run   — let kimidir, amma `this` ilə (receiver); obyekt üzərində hesab → nəticə
    // with  — run kimi, amma obyekt PARAMETR kimi: with(obj) { ... }
    // apply — obyekti QAYTARIR; konfiqurasiya üçün (builder üslubu)
    // also  — obyekti qaytarır, amma `it` ilə; yan təsir (log/validasiya) üçün

    data class Parametrlər(var url: String = "", var timeout: Int = 0, var retry: Int = 0)

    // apply — obyekt qur və qaytar:
    val p = Parametrlər().apply {
        url = "https://chat.z.ai"
        timeout = 30
        retry = 6
    }
    check(p.url == "https://chat.z.ai" && p.retry == 6)

    // let — dəyişmə və nəticə ver:
    val uzunluq = p.url.let { it.length }
    check(uzunluq == "https://chat.z.ai".length)

    // run — receiver ilə hesabla:
    val hesabat = p.run { "url=$url, timeout=${timeout}s, retry=$retry" }
    check(hesabat == "url=https://chat.z.ai, timeout=30s, retry=6")

    // with — eyni, parametr üslubunda:
    check(with(p) { timeout * retry } == 180)

    // also — araya "yan təsir" qoy, obyekt dəyişməz qalır:
    var logSayı = 0
    val p2 = p.also { logSayı++ }.also { check(it === p) }
    check(logSayı == 1 && p2 === p)

    // Null ilə scope funksiyaları:
    val nullDəyər: String? = null
    check(nullDəyər?.let { "var" } ?: "yoxdur" == "yoxdur")

    // ---------- 4. takeIf / takeUnless — şərtli öz-özünə filtri ----------
    // şərt DOĞRUDURSA obyektin ÖZÜ, yoxsa null qaytarır
    val ad = "ZaiMobile"
    check(ad.takeIf { it.length > 5 } == "ZaiMobile")
    check(ad.takeIf { it.isEmpty() } == null)
    check(ad.takeUnless { it.isEmpty() } == "ZaiMobile")
    // Elvis ilə birləşəndə güclü idiom yaranır:
    fun təsdiqlə(s: String?): String =
        s?.takeIf { it.isNotBlank() } ?: "(boş)"
    check(təsdiqlə("  ") == "(boş)")
    check(təsdiqlə("ok") == "ok")

    // ---------- 5. Receiver-lı lambda → DSL ----------
    val html = sənəd {
        append("<html>\n")
        append("  <body>Salam DSL</body>\n")
        append("</html>")
    }
    check(html.contains("<body>Salam DSL</body>"))
    // buildString — stdlib-dəki hazır versiyası:
    check(buildString { repeat(3) { append("ab") } } == "ababab")

    // ---------- 6. Closure + dəyişən tutma ----------
    val artımlılar = mutableListOf<() -> Int>()
    for (i in 1..3) artımlılar.add { i * 100 }    // hər lambda ÖZ i-sini saxlayır
    check(artımlılar.map { it() } == listOf(100, 200, 300))

    // Anonymous funksiya (return davranışı fərqlidir — return ÖZÜNÜ bitirir):
    val f = fun(x: Int) = x + 1
    check(f(1) == 2)

    println("Dərs 08: hamısı keçdi ✔")
}
