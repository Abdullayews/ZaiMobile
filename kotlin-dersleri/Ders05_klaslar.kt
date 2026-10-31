// ============================================================================
// DƏRS 05 — KLASSLAR: konstruktorlar, init, xüsusiyyətlər, görünürlük,
//                    nested/inner, companion object, object (singleton)
// ============================================================================

// ---------- 1. BİRƏBİR (primary) konstruktor — sinif başının ÖZÜNDƏ ----------
// "val/var" yazsan → konstruktor parametri eyni anda XÜSUSİYYƏT (property) olur.
class İstifadəçi(val ad: String, var yaş: Int) {
    // init bloku — konstruktor dərhal icra olunan hissəsi (birdən çox ola bilər)
    init {
        require(yaş >= 0) { "yaş mənfi ola bilməz" }
    }

    // Gövdədə xüsusiyyət — ad-dan törənilən (derived property)
    val tanıtım get() = "$ad ($yaş)"

    // CUSTOM getter/setter + backing field (field açar sözü)
    var ləqəb: String = ""
        get() = if (field.isEmpty()) ad else field   // field = saxlanan dəyər
        set(dəyər) { field = dəyər.trim() }          // yazarkən təmizlə

    // private set — xaricdən oxunar, YALNIZ daxildən yazılar
    var girişSayı: Int = 0
        private set

    fun girişEt() { girişSayı++ }
}

// ---------- 2. İKİNCİL (secondary) konstruktor: this(...) ilə ----------
class Düzbucaqlı(val en: Int, val hündür: Int) {
    var ad = ""
    // Kvadrat üçün qısa yol: ikinci konstruktor birincini çağırır
    constructor(olçü: Int) : this(olçü, olçü)
    constructor(en: Int, hündür: Int, ad: String) : this(en, hündür) { this.ad = ad }
    val sahə get() = en * hündür
}

// ---------- 3. Görünürlük (visibility) ----------
// public    — hər yerdən (DEFAULT — Java-dan fərqli olaraq yazmağa ehtiyac yoxdur)
// internal  — eyni modul (module) daxilində
// protected — yalnız sinif + törəmələr (paketi YOX — Java-dan fərqli!)
// private   — yalnız bu sinif daxilində
class Bank {
    private var balans = 0
    internal val log = mutableListOf<String>()   // modul daxilində görünür
    fun yatır(m: Int) { if (m > 0) { balans += m; log += "+$m" } }
    fun çıxar(m: Int): Boolean {
        if (m > balans) return false
        balans -= m; log += "-$m"; return true
    }
    fun bax() = balans                            // private-ə oxuma yolu
}

// ---------- 4. NESTED vs INNER ----------
class Xarici {
    private val sirri = "gizli"

    // NESTED (statik kimi) — Xarici-nin NÜMUNƏSİNƏ çıxışı YOXDUR
    class Nested { fun salam() = "nested salam" }

    // INNER — Xarici-nin nümunəsinə çıxışı VAR (this@Xarici ilə)
    inner class Inner {
        fun sirriOxu() = "inner görür: ${this@Xarici.sirri}"
    }
}

// ---------- 5. companion object — sinif səviyyəli "statik" üzvlər ----------
// Kotlin-də `static` açar sözü YOXDUR; onun əvəzi companion object-dir.
class Temperatura private constructor(val c: Double) {
    companion object {
        const val SIFIR = -273.15                 // const = kompayl vaxtı sabiti
        fun fromF(f: Double) = Temperatura((f - 32) * 5 / 9)  // factory
        fun fromC(c: Double) = Temperatura(c.toDouble())
    }
    override fun toString() = "$c°C"
}

// ---------- 6. object — SINGLETON (tək nüsxə), eyni anda sinif + nüsxə ----------
object Ayarlar {
    var tema = "dark"
    fun toggle() { tema = if (tema == "dark") "light" else "dark" }
}

fun main() {
    // Primary konstruktor + custom accessor-lar:
    val u = İstifadəçi("Nurlan", 30)
    check(u.tanıtım == "Nurlan (30)")
    u.ləqəb = "  proqramçı  "
    check(u.ləqəb == "proqramçı")     // setter trim etdi
    check(u.ləqəb == u.ləqəb)         // getter: field artıq təmizdir
    val u2 = İstifadəçi("Ayla", 25)
    check(u2.ləqəb == "Ayla")         // boş ləqəb → ad qaytarır
    u2.girişEt(); u2.girişEt()
    check(u2.girişSayı == 2)          // private set → xaricdən yazmaq OLMAZ

    // Secondary konstruktorlar:
    check(Düzbucaqlı(5).sahə == 25)               // kvadrat yolu
    check(Düzbucaqlı(4, 5).sahə == 20)
    check(Düzbucaqlı(2, 3, "kiçik").ad == "kiçik")

    // Visibility:
    val b = Bank()
    b.yatır(100)
    check(b.bax() == 100)
    check(!b.çıxar(200))
    check(b.log == listOf("+100", "-200") || b.log == listOf("+100"))
    // çıxar(200) false qaytardı → log-a yazılmadı:
    check(b.log == listOf("+100"))
    // b.balans        // XƏTA: private
    // b.balans = 0    // XƏTA: private

    // Nested / Inner fərqi:
    check(Xarici.Nested().salam() == "nested salam")    // nümunəsiz çağırıla bilən növ
    check(Xarici().Inner().sirriOxu() == "inner görür: gizli")

    // Companion object:
    val t = Temperatura.fromF(212.0)
    check(t.toString() == "100.0°C")
    check(Temperatura.SIFIR == -273.15)

    // object (singleton) — eyni nüsxə:
    check(Ayarlar.tema == "dark")
    Ayarlar.toggle()
    check(Ayarlar.tema == "light")
    Ayarlar.toggle()

    // Sinifə aid = nümunələr AYRI:
    val k1 = İstifadəçi("X", 1)
    val k2 = İstifadəçi("X", 1)
    check(k1 !== k2)                       // müxtəlif obyektlər
    check(Ayarlar === Ayarlar)             // object həmişə eyni

    println("Dərs 05: hamısı keçdi ✔")
}
