// ============================================================================
// DƏRS 14 — JAVA İLE QARŞILIQLI İŞ (JAVA INTEROP)
// ----------------------------------------------------------------------------
// Kotlin %100 Java ilə uyğunluq içindədir: JVM sinifləri birbaşa çağırılır,
// getter/setter-lər property kimi görünür, SAM interfeysləri lambda qəbul edir.
// ============================================================================

// Java-ya görüləcək annotasiyalar (bura Kotlin-dən Java çağıranlar üçün):
class Alətlər {
    companion object {
        @JvmStatic                                  // Java: Alətlər.faktorial(5) (nümunəsiz!)
        fun faktorial(n: Int): Long = if (n <= 1) 1 else n * faktorial(n - 1)

        @JvmField                                   // getter YOX, birbaşa sahə kimi görünür
        val versiya = "7.0.0"

        @JvmOverloads                               // hər default üçün ayrıca overload yaranır
        fun salam(ad: String, vurğu: String = "!"): String = "Salam $ad$vurğu"
    }
}

fun main() {
    // ---------- 1. JDK sinifləri birbaşa ----------
    val list = java.util.ArrayList<String>()        // java.util.ArrayList
    list.add("kotlin")
    list.add("java")
    check(list.size == 2)                           // size() metodu property kimi!
    check(list.get(0) == "kotlin")                  // get(i) → [] operatoru da olar:
    check(list[0] == "kotlin")

    val map = java.util.HashMap<String, Int>()
    map["az"] = 994                                  // put() → []= operatoru
    check(map["az"] == 994)                          // get() → [] operatoru

    // System.currentTimeMillis, System.out — birbaşa:
    check(System.currentTimeMillis() > 1_700_000_000_000)
    val path: String? = System.getenv("HOME")
    check(!path.isNullOrEmpty())

    // ---------- 2. SAM (Single Abstract Method) konversiyası ----------
    // Java interfeysləri bir metoddan ibarətdirsə → lambda göndərilə bilər:
    val thread = Thread(Runnable { println("  fonda işləyirəm") })   // Runnable SAM!
    check(thread.state == Thread.State.NEW)
    val gələcək = java.util.concurrent.CompletableFuture.supplyAsync {
        "nəticə: 42"
    }
    check(gələcək.get() == "nəticə: 42")

    val müqayisə: java.util.Comparator<String> =
        Comparator { a, b -> a.length - b.length }                // SAM + lambda
    check(listOf("bb", "a", "ccc").sortedWith(müqayisə).first() == "a")

    // ---------- 3. Platform tipləri — JAVA-dan gələn null təhlükəsi ----------
    // Java metodu null qaytara bilər, amma imzada görünmür → String! (platform).
    // QAYDA: Java çağırışlarının nəticəsini DƏRHAL ? ilə qəbul et və yoxla:
    val javaXassə: String? = System.getProperty("os.name")        // həmişə var deyil
    check(javaXassə != null)                                       // bu maşında var
    println("  os.name = $javaXassə")

    // ---------- 4. java.time müasir tarix/saat API ----------
    val indi = java.time.LocalDateTime.now()
    check(indi.year >= 2026)
    val formatlı = java.time.format.DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm:ss").format(indi)
    check(formatlı.length == 19)
    check(formatlı[4] == '-' && formatlı[10] == ' ')
    // ISO-8601 — ZaiMobile-ın API imzasında istifadə olunacaq:
    val iso = java.time.ZonedDateTime
        .now(java.time.ZoneOffset.UTC)
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"))
    check(iso.endsWith("Z") && iso.length == 24)

    // ---------- 5. Java IO (Dərs 17-də Kotlin Fayl API-si ilə davam) ----------
    val fayl = java.io.File(System.getProperty("java.io.tmpdir"), "zai_ders14.txt")
    fayl.writeText("Kotlin→Java IO salamı")
    check(fayl.readText() == "Kotlin→Java IO salamı")
    check(fayl.exists())
    fayl.delete()
    check(!fayl.exists())

    // ---------- 6. Annotasiyaların təsiri ----------
    check(Alətlər.faktorial(10) == 3_628_800L)      // @JvmStatic sayəsində sinifdən
    check(Alətlər.versiya == "7.0.0")               // @JvmField
    check(Alətlər.salam("Dünya") == "Salam Dünya!") // @JvmOverloads default-lu versiya

    // ---------- 7. Checked exceptionlar Kotlin-də MƏCBURİ DEYİL ----------
    // java.io.IOException Java-da checked-dir, Kotlin tutmağı TƏLƏB ETMİR:
    val oxunan: Int = try {
        java.io.FileReader("/yoxdur/yoxdur.txt").read()
    } catch (e: java.io.IOException) {
        -1
    }
    check(oxunan == -1)                              // amma TUTMAQ mümkün və ağıllıdır

    // ---------- 8. @JvmName — bytecode-dakı adı dəyişmək ----------
    // fayl səviyyəsində: @file:JvmName("Util") — Java tərəfdən çağırış adını dəyişir.
    // (Müddəa: eyni imzalı extension funksiyalar toqquşanda da istifadə olunur.)

    // ---------- 9. Kotlin-dən Java massivlərinə ----------
    // Java metodu int[] gözləyirsə → IntArray (boxed Array<Int> DEYİL!):
    val primitive: IntArray = intArrayOf(1, 2, 3)
    check(primitive.sum() == 6)
    val qutulu: Array<Int> = arrayOf(1, 2, 3)        // Integer[] kimi
    check(qutulu.sum() == 6)
    check(primitive.javaClass.name == "[I")          // həqiqətən int[]-dir!
    check(qutulu.javaClass.name == "[Ljava.lang.Integer;")

    println("Dərs 14: hamısı keçdi ✔")
}
