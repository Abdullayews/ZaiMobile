// ============================================================================
// DƏRS 01 — KOTLIN ƏSASLARI: val/var, tiplər, string şablonları
// ----------------------------------------------------------------------------
// Kotlin STATİK TİPLİ dildir: hər dəyişənin tipi kompayl vaxtında məlum olsa
// da, tipi çox vaxt yazmaq lazım deyil — kompilyator onu ÇIXARIR (inference).
// Bu dərsdə: dəyişənlər, əsas tiplər, string şablonları, operatorlar,
// if-ifadəsi, Any/Unit/Nothing.
// ============================================================================

fun main() {
    // ---------- 1. val vs var ----------
    // val = təkrar təyin edilə BİLMƏYƏN istinad (final).
    // var = təkrar təyin edilə bilən dəyişən.
    val pi = 3.14159          // Double çıxarılır, bir daha dəyişdirilə bilməz
    var sayğac = 0            // Int çıxarılır
    sayğac = sayğac + 1
    check(sayğac == 1)
    check(pi == 3.14159)

    // val OBYEKTDƏ DƏYİŞİKLİĞİ qadağan etmir — yalnız İSTİNADI dəyişməyi:
    val list = mutableListOf(1, 2)
    list.add(3)               // QANUNİ: obyektin daxili dəyişir
    check(list.size == 3)

    // ---------- 2. Əsas tiplər (hər şey OBYEKTDİR — primitiv yoxdur!) ----------
    // Kotlin-də int/long kimi "primitiv" yoxdur; performans üçün kompilyator
    // arxa planda JVM primitivlərindən istifadə edir, amma SƏN həmişə
    // obyektlə işləyirsən → Int? kimi nullable yazmaq problem deyil.
    val b: Byte = 120         // 8 bit
    val sh: Short = 3000      // 16 bit
    val i: Int = 2_000_000    // 32 bit; _ ayırıcı oxunaqlılıq üçündür
    val l: Long = 9_000_000_000L  // 64 bit; L şərti
    val f: Float = 2.5f       // 32 bit float; f şərti
    val d: Double = 2.5       // 64 bit float (əsas kəsr tipi)
    val c: Char = 'ə'         // Tək simvol — TIRMƏDA, string DEYİL
    val bool: Boolean = true
    check(i + 1 == 2_000_001)
    check(b + 20 == 140)      // NƏZƏRƏ AL: aritmetika nəticəsi Int olur

    // KÖHNLƏŞMƏ (widening) IMPLICIT YOXDUR — Java-dan fərqlənən məqam:
    // val x: Long = i         // XƏTA: Int → Long avtomatik GETMİR
    val uz: Long = i.toLong() // Açıq çevrilmə lazımdır
    check(uz == 2_000_000L)
    check("42".toInt() == 42)     // String → Int
    check(255.toString(16) == "ff") // əsas (radix) ilə stringə
    check(7 / 2 == 3)             // tam bölmə (Java kimi)
    check(7 % 2 == 1)             // qalıq
    check(7.0 / 2 == 3.5)         // kəsrli bölmə

    // Bit əməliyyatları: and/or/xor/shl/shr — SÖZLƏ (&& kimi simvollar YOX)
    check((0b1010 and 0b0110) == 0b0010)
    check((1 shl 4) == 16)

    // ---------- 3. String şablonları (Kotlin-in super gücü) ----------
    val ad = "ZaiMobile"
    val versiya = 7.0
    val mətn = "Tətbiq: $ad, versiya: $versiya"   // $ ilə birbaşa
    val hesab = "cəm: ${1 + 2 + 3}"               // ${} ilə ifadə
    check(mətn == "Tətbiq: ZaiMobile, versiya: 7.0")
    check(hesab == "cəm: 6")
    val dollar = "Qiymət: \$100"    // $ özünü yazmaq üçün \$
    check(dollar == "Qiymət: \$100")

    // Raw string (üç dırnaq): escape lazım deyil, yeni sətirlər saxlanılır
    val json = """
        {
          "model": "GLM-5.3-Flash",
          "stream": true
        }
    """.trimIndent()                   // ortaq boşluqları kəsir
    check(json.startsWith("{"))
    check("\"model\"" in json)

    // String funksiyaları
    check("kotlin".length == 6)
    check("Kotlin".first() == 'K')
    check("kotlin".uppercase() == "KOTLIN")
    check("  spa ce  ".trim() == "spa ce")
    check("a,b,c".split(",") == listOf("a", "b", "c"))

    // ---------- 4. Müqayisə: == (struktur) vs === (istinad) ----------
    // Java-dan FƏRQ: == məzmunu müqayisə edir (equals çağırır)
    val s1 = "ab" + "c"
    val s2 = "abc"
    check(s1 == s2)                    // struktur bərabərlik
    check("abc" === s2)                // literal-pool: eyni obyekt (bu halda)
    val sb = StringBuilder("abc").toString()
    check(sb == s2 && sb !== s2)       // məzmun eyni, obyekt fərqli

    // ---------- 5. if İFADƏDİR (ifadə = qiymət qaytarır) ----------
    val temp = 30
    val vəziyyət = if (temp > 25) "isti" else "sərin"
    check(vəziyyət == "isti")

    // Ranges: 1..10, until, downTo, step
    check(5 in 1..10)
    check(10 !in 1 until 10)           // until — yuxarı daxil ETMİR
    check('a' in 'a'..'z')             // simvollar da range-də olur

    // ---------- 6. Any, Unit, Nothing ----------
    // Any     = bütün tiplərin anası (Java Object)
    // Unit    = "qiymət yoxdur" (Java void); funksiyalar default Unit qaytarır
    // Nothing = "heç vaxt qayıtmır" (xəta atır / sonsuz) — Dərs 12-də dərinləşəcəyik
    val hərŞey: Any = 42
    check(hərŞey is Int)
    fun heçNəQaytarmır() { }           // Unit yazmaq ŞƏRT DEYİL (default)
    check(heçNəQaytarmır() == Unit)    // Unit-in TEK nüsxəsi var

    println("Dərs 01: hamısı keçdi ✔")
}
