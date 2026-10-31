// ============================================================================
// DƏRS 02 — NƏZARƏT AXINI: when, for/while, range-lər, etiketlər
// ----------------------------------------------------------------------------
// Java-nın switch-i Kotlin-də "when"dir və ÇOX güclüdür: sabitlər, aralıqlar,
// tip yoxlamaları, şərtlər və capture (qiyməti dəyişənə bağlama) dəstəklənir.
// ============================================================================

fun main() {
    // ---------- 1. when — subject (arqument) ilə ----------
    fun təsvirEt(x: Int): String = when (x) {
        0 -> "sıfır"                   // dəqiq dəyər
        1, 2, 3 -> "kiçik"             // bir neçə dəyər vergüllə
        in 4..9 -> "orta"              // aralıq üzvlüyü
        else -> "böyük"
    }
    check(təsvirEt(0) == "sıfır")
    check(təsvirEt(2) == "kiçik")
    check(təsvirEt(7) == "orta")
    check(təsvirEt(100) == "böyük")

    // when İFADƏDİR — qiymət qaytarır; tam (exhaustive) olmalıdır
    val kod = 200
    val status = when (kod) {
        in 200..299 -> "ok"
        in 400..499 -> "client error"
        in 500..599 -> "server error"
        else -> "bilinməz"
    }
    check(status == "ok")

    // Tip yoxlaması + CAPTURE: `is Tip -> d` — d ARTIQ cast olunub işlənir
    fun uzunluq(o: Any): Int = when (o) {
        is String -> o.length           // smart cast burada avtomatik
        is Int -> o
        is List<*> -> o.size
        else -> -1
    }
    check(uzunluq("abcde") == 5)
    check(uzunluq(42) == 42)
    check(uzunluq(listOf(1, 2)) == 2)

    // ---------- 2. when — subject-siz (şərt zənciri kimi) ----------
    val bal = 87
    val qiymət = when {
        bal >= 91 -> "A"
        bal >= 81 -> "B"
        bal >= 71 -> "C"
        else -> "F"
    }
    check(qiymət == "B")

    // Capture: when daxilində val təyin etmək olar
    val httpKod = 403
    val izah = when (val k = httpKod % 100) {
        3 -> "qadağa ($k)"
        else -> "başqa ($k)"
    }
    check(izah == "qadağa (3)")

    // ---------- 3. for — range, downTo, step, until ----------
    check((1..10).sum() == 55)                // range-in özü kolleksiyadır
    var cəm = 0
    for (i in 1..100 step 7) cəm += i         // 1, 8, 15, ...
    check(cəm == (1..100 step 7).sum())
    check((10 downTo 1 step 3).toList() == listOf(10, 7, 4, 1))
    check((0 until 3).toList() == listOf(0, 1, 2))

    // Kolleksiyalar üzrə dövr
    for (ad in listOf("a", "b")) { }
    for ((idx, ad) in listOf("x", "y").withIndex()) {   // indeks + dəyər
        check((idx == 0 && ad == "x") || (idx == 1 && ad == "y"))
    }
    // Map üzrə dövr — DESTRUKTURİNG (ətraflı Dərs 06)
    val cütlüklər = mapOf("a" to 1, "b" to 2)
    for ((k, v) in cütlüklər) check(cütlüklər[k] == v)
    // String üzrə dövr
    check("kotlin".count { it == 't' } == 1)

    // repeat — sadə təkrar (it = 0..n-1)
    var təkrar = 0
    repeat(5) { təkrar += it }
    check(təkrar == 10)

    // ---------- 4. while / do-while ----------
    var n = 1
    while (n < 100) n *= 2
    check(n == 128)
    do { n /= 2 } while (n > 8)               // əvvəlcə bir dəfə MÜTLƏQ icra
    check(n == 8)

    // ---------- 5. break / continue + ETİKETLƏR (labels) ----------
    var sayılmış = 0
    outer@ for (i in 1..5) {                  // outer etiketi
        for (j in 1..5) {
            if (j == 3) continue@outer        // iç dövrü burax, xaricə keç
            if (i == 4) break@outer           // İKİ dövrü də dayandır
            sayılmış++
        }
    }
    // i=1..3 (i=4-də break), hər birində j=1,2 → 3*2 = 6
    check(sayılmış == 6)

    // Lambda-da return: return@label YALNIZ lambdanı bitirir
    val list = listOf(1, 2, 3, 4)
    val nəticə = list.map lam@{
        if (it == 3) return@lam 0             // bu element üçün 0 ver
        it * 10
    }
    check(nəticə == listOf(10, 20, 0, 40))

    println("Dərs 02: hamısı keçdi ✔")
}
