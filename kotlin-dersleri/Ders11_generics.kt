// ============================================================================
// DƏRS 11 — GENERICS: parametrləşdirilmiş tiplər, variance (in/out),
//                   star-projection, reified + inline
// ============================================================================

// ---------- 1. Generic sinif ----------
class Qutu<T>(var məzmun: T) {
    fun qoy(v: T) { məzmun = v }
    fun götür(): T = məzmun
}

// ---------- 2. Generic funksiya + BOUND (hədd) ----------
fun <T : Comparable<T>> ənBöyüyü(vararg ədədlər: T): T? =
    ədədlər.maxOrNull()

// Çoxlu hədd — `where`:
fun <T> kopyalaVeÇapla(element: T): String
        where T : CharSequence, T : Comparable<T> =
    if (element.length > 0) "$element (${element.compareTo(element)})" else "(boş)"

// ---------- 3. VARIANCE — in / out ----------
// OUT (covariant, istehsalçı): yalnız ÇIXARIŞ mövqeyində istifadə olunur →
//   Qutu<out Heyvan> = Qutu<Pişik> qəbul edilə bilər (Pişik IS-A Heyvan)
// IN (contravariant, istehlakçı): yalnız GİRİŞ mövqeyində →
//   İstehlakçı<in Pişik> = İstehlakçı<Heyvan> qəbul edilə bilər
class İstehsalçı<out T>(private val dəyər: T) {
    fun al(): T = dəyər            // T yalnız ÇIXIŞDA → out QANUNİ
    // fun qoy(v: T) { }           // XƏTA: out tip GİRİŞDƏ ola bilməz
}
class İstehlakçı<in T> {
    fun işlə(v: T): String = "işləndi: $v"   // T yalnız GİRİŞDƏ → in QANUNİ
    // fun al(): T                 // XƏTA: in tip ÇIXIŞDA ola bilməz
}

// Sözleşmə: List<out E> — bunun üçün List<Animal> = List<Cat> mümkündür.

// ---------- 4. REIFIED + inline — runtime-da TİPİ GÖRMEK ----------
// Normal generic-də T runtime-da silinir (type erasure). inline + reified
// ilə T funksiya gövdəsinə kopyalanır → is yoxlaması MÜMKÜN olur:
inline fun <reified T> List<Any>.təkcə(): List<T> = filterIsInstance<T>()
inline fun <reified T> Any.tipindədir(): Boolean = this is T

fun main() {
    // Generic sinif:
    val sətirQutusu = Qutu("salam")
    val ədədQutusu = Qutu(42)
    check(sətirQutusu.götür().length == 5)
    check(ədədQutusu.götür() + 1 == 43)
    ədədQutusu.qoy(100)
    check(ədədQutusu.götür() == 100)
    // T çıxarılır: Qutu("x") → Qutu<String>

    // Bound-lu generic:
    check(ənBöyüyü(1, 9, 3) == 9)
    check(ənBöyüyü("alma", "armud") == "armud")     // String Comparable-dır!
    check(ənBöyüyü<Int>() == null)
    check(kopyalaVeÇapla("ab") == "ab (0)")

    // Variance:
    val pişikİstehsalçısı: İstehsalçı<Animal> = İstehsalçı(Pişik())
    check(pişikİstehsalçısı.al().səs() == "miyav")  // İstehsalçı<Pişik> → İstehsalçı<Animal> OK
    val heyvanİstehlakçısı: İstehlakçı<Pişik> = İstehlakçı<Animal>()
    check(heyvanİstehlakçısı.işlə(Pişik()).startsWith("işləndi"))

    // USE-SITE variance — yerdə bildirmək:
    val heyvanlar: List<Animal> = listOf(Pişik(), Balıq())   // List<out E> sayəsində
    check(heyvanlar.size == 2)

    // Array-in variance FƏRQLİDIR (mutable olduğundan invariant!):
    // val a: Array<Animal> = arrayOf<Pişik>(Pişik())   // XƏTA — təhlükəli olardı
    // Amma out-projection ilə "yalnız oxu" sözü verə bilərsən:
    val massiv: Array<out Animal> = arrayOf(Pişik(), Balıq())
    check(massiv[0].səs() == "miyav")               // oxumaq olar
    // massiv[0] = Balıq()                           // XƏTA: yazmaq OLMAZ

    // Star-projection — "hər hansı tip, maraq deyil":
    val siyahı: List<*> = listOf(1, 2, 3)
    check(siyahı.size == 3)                          // elementlər Any? kimi görünür

    // reified:
    val qarışıq = listOf("a", 1, "b", 2.5, "c")
    check(qarışıq.təkcə<String>() == listOf("a", "b", "c"))
    check(qarışıq.təkcə<Int>() == listOf(1))
    check("s".tipindədir<String>() && !("s".tipindədir<Int>()))
    check(42.tipindədir<Int>())

    println("Dərs 11: hamısı keçdi ✔")
}

// Bu dərsin əyani dəstəyi üçün kiçik iyerarxiya:
open class Animal { open fun səs() = "?" }
class Pişik : Animal() { override fun səs() = "miyav" }
class Balıq : Animal() { override fun səs() = "…" }
