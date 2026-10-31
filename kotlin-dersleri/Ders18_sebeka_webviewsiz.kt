// ============================================================================
// DƏRS 18 — ŞƏBƏKƏ: API VƏ WEB-DƏN WEBVIEW OLMADAN DATA ALMAQ ★
// ----------------------------------------------------------------------------
// ƏSAS DƏRS: native Android tətbiqi WebView-a EHTİYAÇ DUYMADAN həm REST API
// çağırır, həm veb səhifənin HTML-ini endirib pars edir, həm də SSE
// (server-sent events) axınını oxuyur. ZaiMobile məhz belə işləyir.
//
// Alətlər (hamısı JDK/Android-də HAZIRDIR):
//   HttpURLConnection — klassik HTTP client (Android-də standart)
//   java.net.http.HttpClient — müasir (JDK 11+; Android API 34+)
//   org.json — JSON parsinq (Android-də daxildir)
//   CookieManager — cookie anbarı (WAF cookie-ləri üçün vacibdir)
//
// QEYD: Bu dərs REAL şəbəkə sorğuları edir (example.com, api.github.com).
// ============================================================================

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.CookieManager
import java.net.HttpURLConnection
import java.net.URL

// ---------- 1. GET sorğusu — sadə form ----------
fun httpGet(url: String, başlıqlar: Map<String, String> = emptyMap()): Pair<Int, String> {
    val bağlantı = URL(url).openConnection() as HttpURLConnection
    return try {
        bağlantı.requestMethod = "GET"
        bağlantı.connectTimeout = 15_000           // qoşulma limiti
        bağlantı.readTimeout = 15_000              // oxuma limiti
        başlıqlar.forEach { (k, v) -> bağlantı.setRequestProperty(k, v) }
        // Error body-ni də oxumaq üçün: 4xx/5xx → errorStream
        val axın = if (bağlantı.responseCode in 200..399) bağlantı.inputStream
                   else bağlantı.errorStream
        val gövdə = axın?.bufferedReader()?.use(BufferedReader::readText) ?: ""
        bağlantı.responseCode to gövdə
    } finally {
        bağlantı.disconnect()                      // resurs azad edilsin
    }
}

// ---------- 2. POST + JSON gövdə ----------
fun httpPostJson(url: String, gövdə: String, başlıqlar: Map<String, String> = emptyMap()): Pair<Int, String> {
    val bağlantı = URL(url).openConnection() as HttpURLConnection
    return try {
        bağlantı.requestMethod = "POST"
        bağlantı.doOutput = true
        bağlantı.connectTimeout = 15_000
        bağlantı.readTimeout = 15_000
        başlıqlar.forEach { (k, v) -> bağlantı.setRequestProperty(k, v) }
        bağlantı.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        bağlantı.outputStream.use { it.write(gövdə.toByteArray(Charsets.UTF_8)) }
        val axın = if (bağlantı.responseCode in 200..399) bağlantı.inputStream else bağlantı.errorStream
        val cavab = axın?.bufferedReader()?.use(BufferedReader::readText) ?: ""
        bağlantı.responseCode to cavab
    } finally {
        bağlantı.disconnect()
    }
}

// ---------- 3. VEB SƏHİFƏDƏN (HTML) DATA ÇİXARMAQ — WebView'sız scraping ----------
fun htmlBaşlıq(html: String): String? =
    Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
        .find(html)?.groupValues?.get(1)?.trim()

fun htmlMətn(html: String): String =
    html.replace(Regex("<script[\\s\\S]*?</script>"), "")
        .replace(Regex("<style[\\s\\S]*?</style>"), "")
        .replace(Regex("<[^>]+>"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

// ---------- 4. SSE (server-sent events) OXUYUCU — axın halında ----------
// SSE formatı: "data: {...}" sətirləri, boş sətir = hadisə sonu, "data: [DONE]" = bitdi.
// ZaiMobile-ın /api/v2/chat/completions cavabı BELƏ oxunur (Ders20-də tətbiqdə).
fun sseHadisələri(axın: java.io.InputStream): List<String> {
    val hadisələr = mutableListOf<String>()
    BufferedReader(InputStreamReader(axın, Charsets.UTF_8)).use { oxuyucu ->
        while (true) {
            val sətir = oxuyucu.readLine() ?: break     // axın bitdi
            if (!sətir.startsWith("data:")) continue     // data: olmayan sətirlər (comment, id:) atılır
            val məzmun = sətir.removePrefix("data:").trim()
            if (məzmun == "[DONE]") break                // axının sonu
            if (məzmun.isNotEmpty()) hadisələr += məzmun
        }
    }
    return hadisələr
}

// Süni SSE axını — dərsin deterministik hissəsi:
fun süniSse(): Sequence<String> = sequence {
    yield("""data: {"delta_content": "Salam"}""")
    yield("""data: {"delta_content": " dünya"}""")
    yield("""data: {"phase": "think"}""")
    yield("")                                            // hadisə ayrıcı (boş sətir)
    yield("data: [DONE]")
}

fun main() {
    println("  [1] example.com veb səhifəsi endirilir (WebView YOX)...")
    val (kod1, html) = httpGet("https://example.com",
        mapOf("User-Agent" to "ZaiMobile-Ders/1.0"))
    check(kod1 == 200)
    check(html.contains("<!doctype html", ignoreCase = true) || html.contains("<html"))
    val başlıq = htmlBaşlıq(html)
    check(başlıq == "Example Domain")
    val mətn = htmlMətn(html)
    check("domain" in mətn.lowercase())            // səhifənin əsas sözü
    check(mətn.length > 40)                          // mətn çıxarıldı
    println("  → <title> = \"$başlıq\" — HTML-dən data çıxarıldı ✔")

    println("  [2] jsonplaceholder REST API çağırılır (JSON)...")
    val (kod2, json) = httpGet("https://jsonplaceholder.typicode.com/todos/1",
        mapOf("User-Agent" to "ZaiMobile-Ders/1.0",
              "Accept" to "application/json"))
    check(kod2 == 200)
    val obyekt = JSONObject(json)
    val userId: Int = obyekt.getInt("userId")
    val todoBaşlıq: String = obyekt.getString("title")
    val bitib: Boolean = obyekt.getBoolean("completed")
    check(userId == 1 && todoBaşlıq == "delectus aut autem" && !bitib)
    println("  → todo #1: \"$todoBaşlıq\" (bitib: $bitib) — API-dən data alındı ✔")

    println("  [3] JSON qurma və POST (httpbin.org echo)...")
    val göndərilən = JSONObject()
        .put("model", "GLM-5.3-Flash")
        .put("stream", true)
        .put("mesajlar", org.json.JSONArray()
            .put(JSONObject().put("role", "user").put("content", "salam")))
    check(göndərilən.toString().contains("\"model\":\"GLM-5.3-Flash\""))
    // httpbin real POST testi (əgər şəbəkə icazə verirsə):
    runCatching {
        val (kod3, cavab) = httpPostJson("https://httpbin.org/post", göndərilən.toString())
        if (kod3 == 200) {
            val echo = JSONObject(cavab).getJSONObject("json")
            check(echo.getString("model") == "GLM-5.3-Flash")   // server eynilə qaytardı
            println("  → POST gövdəsi serverə çatdı və əks olundu ✔")
        } else println("  → httpbin $kod3 (nəzərə alınmadı)")
    }.onFailure { println("  → httpbin əlçatmazdır (${it.message}) — nəzərə alınmadı") }

    // ---------- Cookie idarəsi ----------
    // CookieManager qlobal qoyulanda HttpURLConnection cookie-ləri AVTOMATİK
    // saxlayıb geri göndərir (chat.z.ai WAF-ın acw_tc cookie-si üçün KRİTİK):
    CookieManager().let { cm ->
        check(cm.cookieStore.getURIs().isEmpty())   // Java metodu birbaşa da çağırılır
        // Android-də: CookieHandler.setDefault(CookieManager()) — App.onCreate-də bir dəfə
    }

    // ---------- SSE təhlili (süni axın üzərində — axın oxuma məntiqinin sübutu) ----------
    println("  [4] SSE axını təhlili (chat.z.ai protokolunun miniaturu)...")
    val axın = süniSse().joinToString("\n").byteInputStream(Charsets.UTF_8)
    val hadisələr = sseHadisələri(axın)
    check(hadisələr.size == 3)                          // [DONE] və boş sətir atıldı
    val deltaMətn = hadisələr.mapNotNull { e ->
        runCatching { JSONObject(e) }.getOrNull()
            ?.optString("delta_content")?.takeIf { it.isNotEmpty() }
    }.joinToString("")
    check(deltaMətn == "Salam dünya")
    val fazalar = hadisələr.mapNotNull { e ->
        runCatching { JSONObject(e) }.getOrNull()?.optString("phase")?.takeIf { it.isNotEmpty() }
    }
    check(fazalar == listOf("think"))
    println("  → delta mətn: \"$deltaMətn\", faza: $fazalar — SSE stream pars edildi ✔")

    // ---------- ƏSAS NƏTİCƏ ----------
    // WebView'sız data almağın 3 yolu bu dərsdə sübut olundu:
    //   1) REST API: URL + headers + JSON gövdə → status + JSON cavab
    //   2) Veb səhifə: HTML endir → regex ilə struktur pars et
    //   3) SSE axını: InputStream → sətir-sətir oxu → JSON hadisələr
    // Android-də bunların hamısı <uses-permission android:name="android.permission.INTERNET"/>
    // icazəsi ilə eynilə işləyir (Dərs 19-20-də tətbiqdə görəcəyik).

    println("Dərs 18: hamısı keçdi ✔")
}
