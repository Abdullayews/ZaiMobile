// ============================================================================
// DƏRS 12 — İSTİSNALAR (EXCEPTIONS) və RESULT TİPİ
// ----------------------------------------------------------------------------
// Kotlin-də CHECKED exception YOXDUR (Java-dan fərq) — heç bir funksiya
// səni try-catch yazmağa MƏCBUR etmir. Amma idarəetmə sənin əlindədir.
// ============================================================================

// ---------- 1. Xüsusi istisna sinifləri ----------
class ŞəbəkəXətası(val kod: Int, mesaj: String) : Exception("HTTP $kod: $mesaj")
class CaptchaTələbi : Exception("captcha tələb olundu")

// ---------- 2. Nothing — "heç vaxt qayıtmayan" tip ----------
// throw İFADƏDİR və tipi Nothing-dir. Nothing HƏR tipin alt tipidir →
// Elvis-in sağ tərəfində istənilən tip kimi keçər.
fun mütləqDəyər(v: String?): String =
    v ?: throw ŞəbəkəXətası(500, "dəyər yoxdur")   // Nothing → String kimi oxunur

fun sonsuz(): Nothing { while (true) { } }          // tipi Nothing (qayıtmır)

fun main() {
    // ---------- 3. try İFADƏ kimi ----------
    val nəticə: Int = try {
        "123".toInt()                    // uğurlu budaq → dəyər
    } catch (e: NumberFormatException) {
        -1                               // xəta budağı → dəyər
    } finally {
        // finally HƏR HALDA icra olunur (təmizlik üçün) — qiymət qaytarmır
        // println("çevrilmə bitdi")
    }
    check(nəticə == 123)
    val xətalı: Int = try { "abc".toInt() } catch (e: NumberFormatException) { -1 }
    check(xətalı == -1)

    // ---------- 4. throw + xüsusi istisnalar ----------
    try {
        throw ŞəbəkəXətası(429, "WAF block")
    } catch (e: ŞəbəkəXətası) {
        check(e.kod == 429)
        check(e.message == "HTTP 429: WAF block")
    } catch (e: CaptchaTələbi) {
        check(false)                     // bu budaq işləmədi
    }

    // Kotlin-də multi-catch (catch (A | B e)) YOXDUR — ardıcıl catch-lər:
    fun riskli(növ: Int): String = when (növ) {
        0 -> throw CaptchaTələbi()
        1 -> throw ŞəbəkəXətası(503, "busy")
        else -> "ok"
    }
    val tutulan = mutableListOf<String>()
    for (növ in 0..2) {
        try {
            tutulan += riskli(növ)
        } catch (e: CaptchaTələbi) {
            tutulan += "captcha"
        } catch (e: ŞəbəkəXətası) {
            tutulan += "şəbəkə(${e.kod})"
        }
    }
    check(tutulan == listOf("captcha", "şəbəkə(503)", "ok"))

    // ---------- 5. runCatching → Result<T> — funksional xəta idarəsi ----------
    val r1 = runCatching { "42".toInt() }            // Result<Int> = Uğur(42)
    val r2 = runCatching { "x".toInt() }             // Result<Int> = Uğursuz(NFE)

    check(r1.isSuccess && r1.getOrNull() == 42)
    check(r2.isFailure && r2.exceptionOrNull() is NumberFormatException)

    // Ən məşhur üsullar:
    check(r1.getOrElse { -1 } == 42)
    check(r2.getOrElse { -1 } == -1)
    check(r1.getOrDefault(0) == 42)
    check(r1.map { it * 2 }.getOrNull() == 84)               // uğuru çevir
    check(r2.map { it * 2 }.isFailure)
    check(r1.mapCatching { "y".toInt() }.isFailure)          // içindəki də xətaya düşə bilər
    check(r1.recover { -1 }.getOrNull() == 42)               // xətanı dəyərlə əvəz et
    check(r2.recover { -1 }.getOrNull() == -1)

    // fold — hər iki halı bir yerdə işlə:
    val mətn = r2.fold({ "ədəd: $it" }, { "xəta: ${it.javaClass.simpleName}" })
    check(mətn == "xəta: NumberFormatException")

    // Result zəncirləmə — praktik "parsinq boru kəməri":
    fun satırıTəhlil(s: String): Result<Int> =
        runCatching { s.trim().toInt() }
            .mapCatching { if (it in 0..100) it else error("$it diapazonda deyil") }
    check(satırıTəhlil(" 50 ").getOrNull() == 50)
    check(satırıTəhlil("150").isFailure)
    check(satırıTəhlil("abc").isFailure)

    // ---------- 6. İyerarxiya ----------
    // Throwable
    // ├── Error          — ölümcül (OutOfMemoryError...) — tutmaq adətən YANLIŞDIR
    // └── Exception
    //     ├── RuntimeException (unchecked) — NPE, IndexOutOfBounds, IllegalStateException...
    //     └── digərləri (Java-da checked) — IOException...
    // İyerarxiya — dəyişəndə saxlayanda kompilyator "həmişə true" xəbərdarlığı vermir:
    val atılan1: Throwable = NullPointerException()
    val atılan2: Throwable = ŞəbəkəXətası(1, "x")
    check(atılan1 is RuntimeException)
    check(atılan2 is Exception)
    // Kotlin-də @Throws — Java çağırıcılarına imzada bildirmək üçün:
    // @Throws(ŞəbəkəXətası::class) fun javaÜçün() { ... }

    // throw İFADƏ kimi (Nothing):
    check(mütləqDəyər("var") == "var")
    check(runCatching { mütləqDəyər(null) }.exceptionOrNull() is ŞəbəkəXətası)

    // İstisnanı "yenidən atmaq" (rethrow):
    fun qabukla(): Nothing = throw CaptchaTələbi()
    try {
        qabukla()
    } catch (e: CaptchaTələbi) {
        check(true)
    }

    println("Dərs 12: hamısı keçdi ✔")
}
