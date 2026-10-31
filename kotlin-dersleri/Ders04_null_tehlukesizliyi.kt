// ============================================================================
// DƏRS 04 — NULL TƏHLÜKƏSİZLİYİ: ?, ?., ?:, !!, let, lateinit, lazy
// ----------------------------------------------------------------------------
// Kotlin-in ən məşhur xüsusiyyəti. İki tip kateqoriyası var:
//   String   — heç vaxt null ola bilməz
//   String?  — null ola BİLƏR
// NullPointerException kompayl vaxtında tutulur (Java-da isə runtime-da).
// ============================================================================

// Funksiya null qaytara bilərsə — imzada SUAL İŞARƏSİ ŞƏRTİDİR:
fun tap(adlar: List<String>, axtar: String): String? =
    adlar.firstOrNull { it.equals(axtar, ignoreCase = true) }

fun main() {
    val adlar = listOf("Kotlin", "Rust", "Swift")

    // ---------- 1. Nullable dəyişən ----------
    val tapılmış: String? = tap(adlar, "kotlin")
    val yox: String? = tap(adlar, "java")

    // tapılmış.length        // XƏTA: String? üzərində .length olmaz
    check(tapılmış?.length == 6)       // SAFE CALL: null-dursa → null
    check(yox?.length == null)         // null-dursa → null, XƏTA YOX

    // ---------- 2. Elvis operatoru ?: (null üçün standart dəyər) ----------
    val uzunluq: Int = yox?.length ?: 0        // "yoxdursa 0 al"
    check(uzunluq == 0)
    val ad: String = tapılmış ?: "naməlum"
    check(ad == "Kotlin")

    // Elvis-in sağ tərəfi İFADƏ ola bilər — throw/return da (Nothing tipi!):
    fun məcburi(v: String?): String = v ?: error("boş ola bilməz")
    check(məcburi("ok") == "ok")
    check(runCatching { məcburi(null) }.isFailure)

    // ---------- 3. !! — "əminəm ki, null deyil" (SON ÇARƏ) ----------
    // Null olsa NPE atır — istifadəsi məsləhət deyil, amma bəzən lazımdır.
    val mütləq: Int = tapılmış!!.length
    check(mütləq == 6)

    // ---------- 4. let + ?. — "null deyilsə, bununla işlə" ----------
    yox?.let { check(false) }               // null → blok İŞLƏMİR
    tapılmış?.let { check(it == "Kotlin") } // null deyil → blok işləyir

    // Zəncirvari safe call-lar — birində null dayansa, hamısı null:
    class Ünvan(val şəhər: String? = null)
    class İstifadəçi(val ad: String, val ünvan: Ünvan? = null)
    val u1 = İstifadəçi("Aysel", Ünvan("Bakı"))
    val u2 = İstifadəçi("Eldar")                    // ünvan = null
    check(u1.ünvan?.şəhər == "Bakı")
    check(u2.ünvan?.şəhər == null)                  // zəncir təhlükəsiz

    // ---------- 5. Safe cast as? ----------
    val o: Any = "mətn"
    val rəqəm: Int? = o as? Int        // cast alınmasa → EXCEPTION YOX, null
    check(rəqəm == null)
    check((o as? String)?.length == 4)

    // ---------- 6. lateinit — "sonradan doldurulacaq non-null" ----------
    // Konstruktor vaxtında yox (məs: Android onCreate-də) sonradan təyin
    // olunacaq xüsusiyyətlər üçün. Yalnız VAR + primitiv olmayan tip.
    class Xidmət {
        lateinit var verilənlər: List<String>
        fun hazırla() { verilənlər = listOf("a") }
        fun hazırDır() = this::verilənlər.isInitialized
    }
    val x = Xidmət()
    check(!x.hazırDır())
    x.hazırla()
    check(x.hazırDır() && x.verilənlər.size == 1)

    // ---------- 7. by lazy — "ilk dəfə lazım olanda hesabla, sonra XATİRLA" ----------
    // val üçündür, mövzu (thread) təhlükəsizdir (default SYNCHRONIZED).
    class Konfiq {
        val ağırHesab: String by lazy {
            println("  (lazy YALNIZ BİR dəfə hesablanır)")
            "ağır hesablama nəticəsi"
        }
    }
    val k = Konfiq()
    check(k.ağırHesab == "ağır hesablama nəticəsi")
    check(k.ağırHesab == "ağır hesablama nəticəsi") // 2-ci çagrışda keşdən

    // ---------- 8. Platform tipləri (Java interop-а qabaqcadan baxış) ----------
    // Java-dan gələn dəyərlərin tipi "String!" kimi göstərilir (platform type)
    // — null ola biləcəyi məlum deyil → SƏN qorumaq məcbursan:
    val javaDəyər: String? = System.getProperty("zai.yoxdur.varmi")
    check(javaDəyər == null)
    val envPath: String? = System.getenv("PATH")
    check((envPath?.length ?: 0) > 0)

    println("Dərs 04: hamısı keçdi ✔")
}
