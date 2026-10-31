// ============================================================================
// DƏRS 17 — FAYL GİRİŞ/ÇIXIŞI (I/O) VƏ REGEX
// ----------------------------------------------------------------------------
// Kotlin, java.io üzərinə çox rahat extension-lar qoyur:
// readText/writeText/appendText/use/forEachLine/walkTopDown...
// ============================================================================

import java.io.File

fun main() {
    val qovluq = File(System.getProperty("java.io.tmpdir"), "zai_ders17")
    qovluq.mkdirs()

    // ---------- 1. Yazma / oxuma ----------
    val fayl = File(qovluq, "dərs.txt")
    fayl.writeText("birinci sətir\n")               // bütün mətni yaz (üstünə)
    fayl.appendText("ikinci sətir\n")               // sonuna əlavə
    fayl.appendText("üçüncü sətir\n")
    check(fayl.readText().lines().size == 4)        // son sətirdən sonra \n → 4-cü boşdur
    check(fayl.readLines().size == 3)               // readLines boş son sətri atmır amma sətirləri verir
    check(fayl.readLines()[1] == "ikinci sətir")

    // BAYT səviyyəsində:
    fayl.writeBytes(byteArrayOf(1, 2, 3, 4))
    check(fayl.readBytes().contentEquals(byteArrayOf(1, 2, 3, 4)))

    // ---------- 2. use { } — AVTOMATIK bağlanan resurslar ----------
    // Java try-with-resources-un Kotlin tərzi — hər Closeable üçün:
    File(qovluq, "yazıçı.txt").outputStream().use { out ->
        out.write("use ilə yazıldı".toByteArray())
    }
    val oxundu = File(qovluq, "yazıçı.txt").bufferedReader().use { it.readLine() }
    check(oxundu == "use ilə yazıldı")

    // Böyük faylları SƏTİR-BƏ-SƏTİR oxuma (RAM-a yükləmədən!):
    val böyük = File(qovluq, "böyük.txt")
    böyük.bufferedWriter().use { w ->
        repeat(1000) { w.write("sətir $it\n") }
    }
    var sətirSayı = 0
    böyük.useLines { lines -> sətirSayı = lines.count() }   // axın halında sayır
    check(sətirSayı == 1000)
    val ilk10 = mutableListOf<String>()
    böyük.forEachLine { if (it.startsWith("sətir 9")) ilk10 += it }
    check(ilk10.size == 111)   // 9, 90..99, 900..999

    // ---------- 3. Fayl sistemi gəzinti ----------
    File(qovluq, "alt").mkdirs()
    File(qovluq, "alt/içəridə.txt").writeText("x")
    val bütünFayllar = qovluq.walkTopDown().filter { it.isFile }.toList()
    check(bütünFayllar.size == 4)   // bu AN: dərs.txt, yazıçı.txt, böyük.txt, içəridə.txt (binarlar aşağıda!)
    check(bütünFayllar.any { it.name == "içəridə.txt" })
    check(qovluq.exists() && qovluq.isDirectory)
    // silmə:
    File(qovluq, "alt/içəridə.txt").delete()
    check(!File(qovluq, "alt/içəridə.txt").exists())

    // ---------- 4. Stream-lərin köçürülməsi ----------
    val mənbə = File(qovluq, "mənbə.bin")
    mənbə.writeBytes(ByteArray(1024) { it.toByte() })
    val hədəf = File(qovluq, "hədəf.bin")
    mənbə.inputStream().use { girişi ->
        hədəf.outputStream().use { çıxışı ->
            girişi.copyTo(çıxışı)                   // 8KB-lıq buferlə avtomatik
        }
    }
    check(hədəf.length() == 1024L)
    // indi isə 5 fayl var (içəridə.txt yuxarıda silinmişdi):
    check(qovluq.walkTopDown().filter { it.isFile }.count() == 5)

    // ---------- 5. REGEX ----------
    // Kotlin Regex — java.util.regex üzərinə güclü interfeys:
    val jsonTapma = Regex("\"model\"\\s*:\\s*\"([^\"]+)\"")
    val json = """{"stream": true, "model": "GLM-5.3-Flash", "x": 1}"""
    // containsMatchIn — sadəcə VAR/YOX:
    check(jsonTapma.containsMatchIn(json))
    // find + groups — tutulan qruplar:
    val uyğun = jsonTapma.find(json)!!
    check(uyğun.value == "\"model\": \"GLM-5.3-Flash\"")
    check(uyğun.groupValues[1] == "GLM-5.3-Flash")       // 1-ci qrup (mötərizədəki)
    check(uyğun.groups[1]!!.value == "GLM-5.3-Flash")

    // findAll — bütün uyğunluqlar:
    val rəqəmlər = Regex("\\d+").findAll("a1 b22 c333").map { it.value }.toList()
    check(rəqəmlər == listOf("1", "22", "333"))

    // matchEntire — BÜTÜN sətir uyğun gəlməli; matchAt — müəyyən mövqedən:
    check(Regex("\\d{4}-\\d{2}-\\d{2}").matches("2026-10-31"))
    check(!Regex("\\d+").matches("123abc"))
    check(Regex("\\d+").matchAt("123abc", 0) != null)

    // əvəzetmə və bölünmə:
    check(Regex("\\s+").replace("a   b \t c", " ") == "a b c")
    check(Regex("[,;]").split("a,b;c") == listOf("a", "b", "c"))
    check("2026-10-31".split("-").map { it.toInt() } == listOf(2026, 10, 31))
    // Regex opsionalları:
    check(Regex("saLAM", RegexOption.IGNORE_CASE).matches("salam"))
    // Adlandırılmış qruplar (QEYD: Java regex qrup adları YALNIZ ASCII hərflər qəbul edir!):
    val isoTarix = Regex("(?<year>\\d{4})-(?<month>\\d{2})-(?<day>\\d{2})")
    val m = isoTarix.find("tarix: 2026-10-31!")!!
    check(m.groups["year"]!!.value == "2026")
    check(m.groups["month"]!!.value == "10")

    // Kotlin-in üçlü stringi ilə regex yazmaq (\\ qurtuluşu):
    val sseSətri = Regex("""^data:\s*(.*)$""")     // \s və \. — üçlü dırnaqda təbii!
    val uyğun2 = sseSətri.find("data: {\"delta\": \"salam\"}")!!
    check(uyğun2.groupValues[1] == """{"delta": "salam"}""")   // SSE parse (Dərs 18!)

    // Təmizlik:
    qovluq.deleteRecursively()
    check(!qovluq.exists())

    println("Dərs 17: hamısı keçdi ✔")
}
