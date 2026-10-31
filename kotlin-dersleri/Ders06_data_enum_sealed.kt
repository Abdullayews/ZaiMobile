// ============================================================================
// DƏRS 06 — DATA CLASS, ENUM, SEALED CLASS, OBJECT EXPRESSION
// ----------------------------------------------------------------------------
// Kotlin-in "modelləmə üçün" əsas silahları — ZaiMobile-da mesaj, model,
// söhbət tipləri məhz bunlarla yazılacaq.
// ============================================================================

// ---------- 1. data class — DATA üçün avtomatik toString/equals/hashCode/copy ----------
// ŞƏRT: əsas konstruktorunda yalnız val/var xüsusiyyətlər olmalıdır.
data class Nöqtə(val x: Int, val y: Int)

// data class + default dəyərlər + əlavə (gövdədə) üzv
data class Mesaj(
    val id: String,
    val rolu: String,                 // "user" | "assistant"
    val məzmun: String = "",
    val fazalar: List<String> = emptyList(),
) {
    val boş get() = məzmun.isBlank()
    fun qısa(n: Int = 20) = məzmun.take(n) + if (məzmun.length > n) "…" else ""
}

// ---------- 2. enum — adlandırılmış sabitlər TOPLUMU (sinif kimi güclü) ----------
enum class Səviyyə(val etiket: String, val minimum: Int) {
    ASAN("Asan", 1),
    ORTA("Orta", 2),
    ÇƏTİN("Çətin", 3);

    // enum daxilində metod + abstract üzv də ola bilər
    fun keçildimi(bal: Int) = bal >= minimum
}

// ---------- 3. sealed class — "məhdud iyerarxiya": bütün törəmələr EYNİ FAYLDA ----------
// when üzərində EXHAUSTIVE (tam) yoxlama verir → else yazmaq lazım deyil,
// yeni variant əlavə olunanda kompilyator BÜTÜN when-ləri xəbərdar edir.
// Bu, ZaiMobile-ın SSE hadisə modelinin əsasıdır (Delta/Phase/Done/Error...).
sealed interface AxınHadisəsi {
    data class Delta(val mətn: String) : AxınHadisəsi
    data class Fazа(val ad: String) : AxınHadisəsi
    data object Bitdi : AxınHadisəsi
    data class Xəta(val səbəb: String) : AxınHadisəsi
}

// sealed class versiyası (interfeys də olar — yuxarıdakı kimi)
sealed class Nəticə<out T> {
    data class Uğur<out T>(val dəyər: T) : Nəticə<T>()
    data class Uğursuz(val xəta: String) : Nəticə<Nothing>()
}

fun main() {
    // ---------- data class ----------
    val p1 = Nöqtə(1, 2)
    val p2 = Nöqtə(1, 2)
    check(p1 == p2)                          // equals AVTOMATİK (struktur!)
    check(p1.toString() == "Nöqtə(x=1, y=2)") // toString AVTOMATİK
    check(p1.hashCode() == p2.hashCode())
    check(p1 !== p2)                         // amma fərqli obyektlər

    // copy() — bəzi sahələri dəyişib nüsxə almaq (immutable modellərin ruhu):
    val p3 = p1.copy(y = 99)
    check(p3 == Nöqtə(1, 99))
    check(p1 == Nöqtə(1, 2))                 // orijinal DƏYİŞMƏDİ

    // DESTRUKTURİNG — componentN() avtomatik yaranır:
    val (x, y) = p1
    check(x == 1 && y == 2)
    val (id, rolu) = Mesaj("m1", "user", "salam")
    check(id == "m1" && rolu == "user")   // component1=id, component2=rolu, component3=məzmun
    // copy ilə dəyişmə + destrukturing birlikdə (data siniflərin gücü)
    val (_, _, mətn) = Mesaj("m2", "assistant", "cavab")
    check(mətn == "cavab")                   // _ = lazım deyil, atılır

    val msj = Mesaj("m3", "user", "çox uzun mesaj mətni burada", listOf("a"))
    check(!msj.boş)
    check(msj.qısa(9) == "çox uzun …")
    check(msj.copy(rolu = "assistant").rolu == "assistant")

    // ---------- enum ----------
    check(Səviyyə.ORTA.etiket == "Orta")
    check(Səviyyə.values().size == 3)         // klassik üsul
    check(Səviyyə.entries.size == 3)          // müasir (1.9+) tercih olunan
    check(Səviyyə.valueOf("ASAN") == Səviyyə.ASAN)
    check(Səviyyə.ÇƏTİN.keçildimi(3))
    check(!Səviyyə.ORTA.keçildimi(1))
    // enum + when = tam (else lazım deyil)
    fun bal(s: Səviyyə) = when (s) {
        Səviyyə.ASAN -> 10
        Səviyyə.ORTA -> 20
        Səviyyə.ÇƏTİN -> 30
    }
    check(bal(Səviyyə.ASAN) == 10)
    check(Səviyyə.ASAN.ordinal == 0)          // sıra nömrəsi

    // ---------- sealed + exhaustive when ----------
    fun render(h: AxınHadisəsi): String = when (h) {   // ELSE YOX — tam!
        is AxınHadisəsi.Delta -> "mətn: ${h.mətn}"     // smart cast avtomatik
        is AxınHadisəsi.Fazа -> "faza: ${h.ad}"
        AxınHadisəsi.Bitdi -> "bitdi"
        is AxınHadisəsi.Xəta -> "xəta: ${h.səbəb}"
    }
    check(render(AxınHadisəsi.Delta("salam")) == "mətn: salam")
    check(render(AxınHadisəsi.Fazа("Thinking…")) == "faza: Thinking…")
    check(render(AxınHadisəsi.Bitdi) == "bitdi")
    check(render(AxınHadisəsi.Xəta("şəbəkə")) == "xəta: şəbəkə")

    // data object — toString sadəaddir (1.9-dan): "Bitdi" (paket/ixtisas YOX)
    check(AxınHadisəsi.Bitdi.toString() == "Bitdi")

    // sealed + generic nəticə tipləri (ZaiApi-in qayıtış tipi belə olacaq):
    fun yüklə(ok: Boolean): Nəticə<String> =
        if (ok) Nəticə.Uğur("data") else Nəticə.Uğursuz("bağlantı yoxdur")
    val n1 = yüklə(true)
    val n2 = yüklə(false)
    check((n1 as Nəticə.Uğur).dəyər == "data")
    check((n2 as Nəticə.Uğursuz).xəta == "bağlantı yoxdur")

    // ---------- 4. OBJECT EXPRESSION — anonim sinif (Java anonymous class) ----------
    // İnterfeysi/dar sinifi RUNTIME-da, ad vermədən implement et:
    val müqayisəçi = object : Comparator<Nöqtə> {
        override fun compare(a: Nöqtə, b: Nöqtə) = a.x - b.x
    }
    val sıralı = listOf(Nöqtə(3, 0), Nöqtə(1, 0), Nöqtə(2, 0)).sortedWith(müqayisəçi)
    check(sıralı.map { it.x } == listOf(1, 2, 3))
    // Bir dəfəlik obyekt (state saxlayan anonim):
    var zərb = 0
    val sayaq = object {
        fun vur() { zərb++ }
    }
    sayaq.vur(); sayaq.vur()
    check(zərb == 2)

    println("Dərs 06: hamısı keçdi ✔")
}
