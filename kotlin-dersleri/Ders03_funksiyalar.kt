// ============================================================================
// DƏRS 03 — FUNKSİYALAR: default/named arqumentlər, vararg, infix,
//                     operator aşırı yüklənməsi, tailrec, ::istinadlar
// ============================================================================

// ---------- 1. Default arqumentlər ----------
// Java-da overload zəncirləri yazmaq əvəzinə Kotlin-də default dəyərlər var.
fun salam(ad: String, nöqtə: String = "!", üstün: Boolean = false): String =
    (if (üstün) "SALAM, " else "Salam, ") + ad + nöqtə

// ---------- 2. İnfix funksiya (nöqtəsiz çağırış: a to b kimi) ----------
infix fun Int.qüvvət(üsyan: Int): Int {
    var n = 1
    repeat(üsyan) { n *= this }
    return n
}

// ---------- 3. tailrec — quyruq rekursiyasını dövrə çevirir (stack partlamır) ----------
tailrec fun faktorial(n: Long, akk: Long = 1): Long =
    if (n <= 1) akk else faktorial(n - 1, akk * n)

// ---------- 4. Operator aşırı yüklənməsi (operator overloading) ----------
class Vektor(val x: Int, val y: Int) {
    operator fun plus(o: Vektor) = Vektor(x + o.x, y + o.y)   // a + b
    operator fun times(k: Int) = Vektor(x * k, y * k)         // a * 3
    operator fun get(i: Int) = when (i) { 0 -> x; 1 -> y; else -> error("indeks") }
    operator fun contains(p: Pair<Int, Int>) = p.first == x && p.second == y
    override fun toString() = "($x, $y)"
}

// ---------- 5. Vararg ----------
fun cəmle(vararg ədədlər: Int): Int = ədədlər.sum()  // ədədlər: IntArray kimi

fun main() {
    // Default + NAMED arqumentlər — sırayı pozmaq olar:
    check(salam("Aydın") == "Salam, Aydın!")
    check(salam("Aydın", nöqtə = "?") == "Salam, Aydın?")
    check(salam("Aydın", üstün = true) == "SALAM, Aydın!")

    // İnfix çağırış:
    check(2 qüvvət 10 == 1024)
    check(2.qüvvət(10) == 1024)              // adi çağırış da mümkündür

    // tailrec: 20! dəqiq hesablanır (Int daşardı deyil — Long istifadə etdik)
    check(faktorial(20) == 2_432_902_008_176_640_000L)

    // Operatorlar:
    val v = Vektor(1, 2) + Vektor(3, 4) * 2  // (1,2) + (6,8)
    check(v.toString() == "(7, 10)")
    check(v[0] == 7 && v[1] == 10)
    check((7 to 10) in v)

    // vararg:
    check(cəmle(1, 2, 3) == 6)
    check(cəmle() == 0)
    val massiv = intArrayOf(4, 5)
    check(cəmle(*massiv) == 9)               // spread operatoru *
    check(cəmle(1, *massiv, 1) == 11)        // qarışdırma da olar

    // ---------- 6. Funksiya TİPLƏRİ və :: istinadlar ----------
    // Funksiya da DƏYƏRDİR: dəyişənə qoy, parametr kimi ötür.
    val ikiqat: (Int) -> Int = { it * 2 }
    val topl: (Int, Int) -> Int = { a, b -> a + b }
    check(ikiqat(21) == 42)
    check(topl(20, 22) == 42)

    // :: adlı funksiyaya istinad
    fun kvadrat(x: Int) = x * x
    val f: (Int) -> Int = ::kvadrat
    check(f(9) == 81)
    // Metoda/property-yə istinad: String::length
    val uzunluqlar = listOf("ab", "cde").map(String::length)
    check(uzunluqlar == listOf(2, 3))

    // Yüksək dərəcəli funksiya (higher-order) — funksiya qəbul edən funksiya
    fun tətbiqEt(x: Int, f: (Int) -> Int) = f(x)
    check(tətbiqEt(5, ::kvadrat) == 25)
    check(tətbiqEt(5) { it + 1 } == 6)       // trailing lambda (Dərs 08)

    // Funksiya QAYTARAN funksiya (closure — dəyişəni "yadda saxlayır")
    fun sayğacYarat(): () -> Int {
        var n = 0
        return { ++n }                       // n bağlanır (captured)
    }
    val next = sayğacYarat()
    check(next() == 1 && next() == 2 && next() == 3)

    // ---------- 7. Lokal (içəridə yazılmış) funksiyalar ----------
    fun xarici(data: List<Int>): Int {
        fun daxili(təmiz: List<Int>) = təmiz.filter { it > 0 }.sum()
        return daxili(data) + daxili(data.map { -it })
    }
    check(xarici(listOf(1, -2, 3)) == 4 + 2) // (1+3) + (2)

    // ---------- 8. Unit-qaytaran funksiya == prosedur ----------
    fun log(msg: String) = println("[log] $msg")
    log("funksiyalar dərsi")

    println("Dərs 03: hamısı keçdi ✔")
}
