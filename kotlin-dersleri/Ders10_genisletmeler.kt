// ============================================================================
// DƏRS 10 — GENİŞLƏNDİRMƏ FUNKSİYALARI (EXTENSION FUNCTIONS)
// ----------------------------------------------------------------------------
// Mövcud sinfə (dəyişdirmədən!) yeni funksiya/property əlavə etmək.
// Kotlin stdlib-inin böyük hissəsi məhz extension-lardır.
// NÖVZƏRƏ AL: extension STATİK bağlanır — virtual polymorphism DƏSTƏKLƏMİR.
// ============================================================================

// ---------- 1. Sadə extension ----------
fun String.tərsinə(): String = reversed()
fun String.ql(): String = "`$this`"                       // markdown inline kodu!
fun Int.faiz(ümumi: Int): Double = this * 100.0 / ümumi

// ---------- 2. NULLABLE qəbul edən extension ----------
// Alıcı null olsa belə çağırıla bilər — this nullable olur!
fun String?.yenəBoşdur(): Boolean = this == null || isBlank()
fun String?.yoxsa(başqa: String): String = this ?: başqa

// ---------- 3. Extension PROPERTY ----------
// DƏYƏR SAXLAMIR (backing field yoxdur!) — hər oxunuşda hesablanır.
val String.ilkHərf: Char get() = first()
val Int.kvadratı: Int get() = this * this
val List<Any?>.nullOlmayanlar: List<Any> get() = filterNotNull()

// ---------- 4. Generic extension ----------
fun <T> List<T>.ikinci(): T? = getOrNull(1)
fun <T : Comparable<T>> List<T>.orta(): T = sorted()[size / 2]
fun <T, R> T.çevir(f: (T) -> R): R = f(this)

// ---------- 5. Koleksiya extension-ları (praktik nümunələr) ----------
fun List<Int>.cəmiPozitiv(): Int = filter { it > 0 }.sum()
fun Map<String, Int>.ənBöyükAçarlıDəyər(): Int? = maxByOrNull { it.key }?.value

// ---------- 6. STATİK bağlanma DƏRSİ ----------
open class Açar { open fun ad() = "Açar" }
class Zild : Açar() { override fun ad() = "Zild" }
// Extension həmişə BİLDİRİLƏN tip üzrə seçilir (runtime tipinə baxmır!):
fun Açar.kimdir() = "mən Açar-ın extension-uyam: ${ad()}"
fun Zild.kimdir() = "mən Zild-in extension-uyam"

fun main() {
    // Sadə:
    check("abc".tərsinə() == "cba")
    check("val x".ql() == "`val x`")
    check(45.faiz(60) == 75.0)

    // Nullable extension:
    val s: String? = null
    check(s.yenəBoşdur())
    check("  ".yenəBoşdur())
    check(s.yoxsa("boş") == "boş")
    check("dolu".yoxsa("boş") == "dolu")

    // Extension property:
    check("kotlin".ilkHərf == 'k')
    check(12.kvadratı == 144)
    check(listOf(1, null, 2).nullOlmayanlar == listOf(1, 2))

    // Generic:
    check(listOf("a", "b", "c").ikinci() == "b")
    check(listOf<Int>().ikinci() == null)
    check(listOf(5, 1, 9).orta() == 5)
    check(3.çevir { it * 14 } == 42)                       // T=Int, R=Int

    // Praktik:
    check(listOf(-1, 2, -3, 4).cəmiPozitiv() == 6)
    check(mapOf("a" to 1, "b" to 5, "c" to 3).ənBöyükAçarlıDəyər() == 3)  // "c" ən böyük açar

    // STATİK bağlanma — ƏSAS dərs:
    val açar: Açar = Zild()          // STATIC tip Açar, RUNTIME tipi Zild
    check(açar.ad() == "Zild")       // adi metod → DİNAMİK (törəmə işləyir)
    check(açar.kimdir().startsWith("mən Açar"))  // extension → STATİK (Açar seçildi!)
    val zild: Zild = Zild()
    check(zild.kimdir().startsWith("mən Zild"))  // burada Zild extension-u

    // ---------- 7. stdlib-dən məşhur extension-lar (bəzilərini artıq bilirsən) ----------
    check("salam dünya".replaceFirstChar { it.uppercase() } == "Salam dünya")
    check("a-b-c".split("-").joinToString(".") == "a.b.c")
    check("42".toIntOrNull() == 42)
    check("x".repeat(3) == "xxx")
    check(listOf(1, 2).also { /* log */ } == listOf(1, 2))
    check(10.let { it + 1 }.also { it2 -> check(it2 == 11) } == 11)

    // Pair/Triple — infix `to` extension nəticəsidir:
    val cüt = "a" to 1
    check(cüt.first == "a" && cüt.second == 1)

    println("Dərs 10: hamısı keçdi ✔")
}
