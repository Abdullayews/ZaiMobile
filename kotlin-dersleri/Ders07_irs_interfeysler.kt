// ============================================================================
// DƏRS 07 — İRS (inheritance), INTERFEYSLƏR, ABSTRAKT SINIFLƏR, SMART CAST
// ----------------------------------------------------------------------------
// Kotlin-də siniflər və metodlar DEFAULT OLARAQ FINAL-dır (final açar sözü
// Java-da var idi, Kotlin-də ƏKSİNƏdir!). İrs üçün açıq şəkildə `open` de.
// ============================================================================

// ---------- 1. open + override ----------
open class Heyvan(val ad: String) {
    open val ayaqSayı = 4                      // xüsusiyyət DƏ override olunur
    open fun səs() = "bəzi səs"
    fun tanıtım() = "$ad: ${səs()}"            // final — override OLMAZ
}

open class Pişik(ad: String) : Heyvan(ad) {
    override val ayaqSayı = 4
    override fun səs() = "miyav"
}

open class Balıq(ad: String) : Heyvan(ad) {
    override val ayaqSayı = 0
    override fun səs() = "…(susur)"
}

// ---------- 2. abstract — nümunələnə BİLMƏYƏN, üzsüz (abstract) üzvlü ----------
abstract class Forma {
    abstract val sahə: Double                  // üzv — törəmə MÜTLƏQ yazmalı
    open fun tanıtım() = "sahə = $sahə"        // amma adüzlər də ola bilər
}
class Dairə(val r: Double) : Forma() {
    override val sahə get() = 3.14159 * r * r
}

// ---------- 3. interface — çoxlu implementasiya ----------
// Java-dan FƏRQ: interfeysdə PROPERTY də ola bilər (val üzvü və ya accessor-lu)
interface Səslənən {
    val sürət: Int                             // üzv xüsusiyyət (override lazım)
    fun səsÇıxar(): String                     // üzv metod
    fun tanıt() = "sürət $sürət: ${səsÇıxar()}"// DEFAULT implementasiya (Java 8 kimi)
}
interface ÇapOlunan {
    fun çapEt() = "çap edilir"
}
// İKİ interfeys birlikdə — Kotlin-də çoxlu irs interfeyslərlə mümkündür:
class Siqnal(override val sürət: Int) : Səslənən, ÇapOlunan {
    override fun səsÇıxar() = "bip!" * (sürət / 10 + 1)
    // EYNI adlı default-lar toqquşarsa — MÜTLƏQ öz versiyanı yaz:
    override fun çapEt() = super<Səslənən>.tanıt() + " | " + super<ÇapOlunan>.çapEt()
}
// String "çoxaltma" köməkçisi (Dərs 10-da extension kimi yenidən görəcəyik)
operator fun String.times(n: Int): String = buildString { repeat(n) { append(this@times) } }

fun main() {
    // Polimorfizm — eyni interfeys, müxtəlif davranış:
    val heyvanlar: List<Heyvan> = listOf(Pişik("Mesi"), Balıq("Nemo"))
    check(heyvanlar[0].səs() == "miyav")
    check(heyvanlar[1].səs() == "…(susur)")
    check(heyvanlar.map { it.ayaqSayı } == listOf(4, 0))
    // tanıtım() final idi, amma DAXİLİNDƏ səs() VIRTUAL çağırılır → törəmənin versiyası:
    check(heyvanlar[0].tanıtım() == "Mesi: miyav")

    // abstract:
    val f: Forma = Dairə(2.0)
    check(f.tanıtım() == "sahə = 12.56636")
    check(f.sahə == 3.14159 * 4)

    // interface-lər:
    val s = Siqnal(20)
    check(s.səsÇıxar() == "bip!" * 3)
    check(s.tanıt() == "sürət 20: bip!bip!bip!")
    check(s.çapEt().startsWith("sürət 20"))

    // ---------- 4. is / as və SMART CAST ----------
    // is yoxlamasından SONRA kompilyator avtomatik cast edir (smart cast):
    val o: Any = Pişik("Lisa")
    if (o is Pişik) {
        check(o.səs() == "miyav")              // artıq Pişik kimi işlənir
    }
    // when ilə smart cast:
    fun adlandır(o: Any): String = when (o) {
        is Pişik -> "pişik ${o.ad}"
        is Balıq -> "balıq ${o.ad}"
        is Int -> "ədəd $o"
        else -> "bilinməz"
    }
    check(adlandır(Balıq("Dori")) == "balıq Dori")
    check(adlandır(42) == "ədəd 42")
    // as — MƏCBURİ cast (alınmasa ClassCastException):
    val p = o as Pişik
    check(p.ad == "Lisa")

    // ---------- 5. super çağırışı ----------
    class ÖzPişik(ad: String) : Pişik(ad) {
        override fun səs() = super.səs() + " (öz versiya)"
    }
    check(ÖzPişik("A").səs() == "miyav (öz versiya)")

    // NÖMRİ: extension funksiyalar statik bağlanır (Dərs 10), override OLMAZ;
    // property/metodlar isə dinamik (virtual) bağlanır — yuxarıda gördük.

    println("Dərs 07: hamısı keçdi ✔")
}
