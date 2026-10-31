package zai.mobile

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Xalis məntiq testləri — imza alqoritmi, SSE təhlili, markdown parser,
 * chat zənciri, xəta xəritələri. Robolectric runner org.json üçündür.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LogicTest {

    // ---------------- Signature ----------------

    @Test
    fun hmacSha256_rfc4231_vector() {
        // RFC 4231 Test Case 2: key "Jefe", data "what do ya want for nothing?"
        val hmac = Signature.hmacSha256Hex(
            "Jefe".toByteArray(),
            "what do ya want for nothing?".toByteArray())
        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843", hmac)
    }

    @Test
    fun base64_knownVectors() {
        assertEquals("TWFu", Signature.base64("Man".toByteArray()))
        assertEquals("TWE=", Signature.base64("Ma".toByteArray()))
        assertEquals("TQ==", Signature.base64("M".toByteArray()))
        assertEquals("", Signature.base64(ByteArray(0)))
        assertEquals("c2FsYW0=", Signature.base64("salam".toByteArray()))
    }

    @Test
    fun formEncode_specials() {
        assertEquals("hello+world", Signature.formEncode("hello world"))
        assertEquals("a%2Fb", Signature.formEncode("a/b"))
        assertEquals("A-b_c.d*e", Signature.formEncode("A-b_c.d*e"))
        assertEquals("%C3%A7", Signature.formEncode("ç"))
    }

    @Test
    fun signature_make_producesValidFields() {
        val info = Signature.make("test prompt", "tok123", "user1")
        assertEquals(64, info.signature.length)
        assertTrue(info.signature.matches(Regex("[0-9a-f]{64}")))
        assertTrue(info.urlParams.contains("platform=web"))
        assertTrue(info.urlParams.contains("token=tok123"))
        assertTrue(info.urlParams.contains("host=chat.z.ai"))
        assertTrue(info.urlParams.contains("is_mobile=true"))
        assertTrue(info.timestamp.toLong() > 1_700_000_000_000)
    }

    // ---------------- SSE ----------------

    @Test
    fun sse_delta_allNestingVariants() {
        assertEquals(
            StreamEvent.Delta("Salam"),
            Sse.parseEvent("""{"data":{"message":{"delta_content":"Salam","phase":"answer"}}}"""))
        assertEquals(
            StreamEvent.Delta("d2"),
            Sse.parseEvent("""{"data":{"data":{"message":{"delta_content":"d2"}}}}"""))
        assertEquals(
            StreamEvent.Delta("m1"),
            Sse.parseEvent("""{"message":{"delta_content":"m1"}}"""))
        assertEquals(
            StreamEvent.Delta("d3"),
            Sse.parseEvent("""{"data":{"data":{"data":{"delta_content":"d3"}}}}"""))
    }

    @Test
    fun sse_phase_labels() {
        assertEquals(
            StreamEvent.Phase("Thinking…"),
            Sse.parseEvent("""{"data":{"message":{"phase":"think"}}}"""))
        assertEquals(
            StreamEvent.Phase("Searching the web…"),
            Sse.parseEvent("""{"data":{"message":{"phase":"web_search"}}}"""))
        assertEquals(
            null,
            Sse.parseEvent("""{"data":{"message":{"phase":"answer"}}}"""))
    }

    @Test
    fun sse_done() {
        assertEquals(
            StreamEvent.Done,
            Sse.parseEvent("""{"data":{"message":{"done":true}}}"""))
    }

    @Test
    fun sse_error_and_captcha() {
        val err = Sse.parseEvent(
            """{"data":{"message":{"error":{"code":"x","detail":"user_limit reached"}}}}""")
        assertTrue(err is StreamEvent.Error)
        assertTrue((err as StreamEvent.Error).message.startsWith("RETRYABLE:"))

        assertEquals(
            StreamEvent.CaptchaRequired,
            Sse.parseEvent("""{"data":{"error":{"detail":"please captcha"}}}"""))
    }

    @Test
    fun sse_invalidJson_returnsNull() {
        assertEquals(null, Sse.parseEvent("not json at all"))
        assertEquals(null, Sse.parseEvent(""))
    }

    @Test
    fun sse_accumulator_joinsSplitJson() {
        val acc = Sse.Accumulator()
        assertEquals("""{"a":1}""", acc.feed("""{"a":1}"""))
        assertEquals(null, acc.feed("""{"text":"line1"""))
        // SSE-nin çoxsətirli birləşdirməsi: hissələr REAL \n ilə birləşir
        // (org.json sətir içi yeni-sətiri qəbul edir — Rust versiyası ilə eyni)
        val expected = """{"text":"line1""" + "\n" + """line2"}"""
        assertEquals(expected, acc.feed("""line2"}"""))
        assertEquals("""{"b":2}""", acc.feed("""{"b":2}"""))
        acc.feed("""{"x":""")
        acc.reset()
        assertEquals("""{"y":1}""", acc.feed("""{"y":1}"""))
    }

    // ---------------- MarkdownParse ----------------

    @Test
    fun markdown_blocks() {
        val src = "# Title\n\ntext here\n\n```kotlin\nfun x() {}\n```\n\n- item\n\n1. first\n\n> quoted"
        val nodes = MarkdownParse.parse(src)
        assertTrue(nodes[0] is MdNode.Heading)
        assertEquals(1, (nodes[0] as MdNode.Heading).level)
        assertTrue(nodes.any { it is MdNode.Code && it.lang == "kotlin" && it.body.contains("fun x") })
        assertTrue(nodes.any { it is MdNode.ListItem && !it.ordered })
        assertTrue(nodes.any { it is MdNode.ListItem && it.ordered && it.index == 1 })
        assertTrue(nodes.any { it is MdNode.Quote })
    }

    @Test
    fun markdown_inlineSpans() {
        val parts = MarkdownParse.parseInline("a **bold** b *it* c `code` d [link](https://x.y)")
        assertTrue(parts.any { it.bold && it.text == "bold" })
        assertTrue(parts.any { it.italic && it.text == "it" })
        assertTrue(parts.any { it.code && it.text == "code" })
        val link = parts.first { it.link != null }
        assertEquals("link", link.text)
        assertEquals("https://x.y", link.link)
    }

    // ---------------- ChatParse ----------------

    @Test
    fun chatParse_walksParentChain() {
        val detail = JSONObject("""
        {
          "title": "Test chat",
          "history": {
            "currentId": "c",
            "messages": {
              "a": {"id":"a","parentId":null,"childrenIds":["b"],"role":"user",
                    "content":"sual","timestamp":1,"models":["m"]},
              "b": {"id":"b","parentId":"a","childrenIds":["c"],"role":"assistant",
                    "content":"orta cavab","timestamp":2,"models":["m"]},
              "c": {"id":"c","parentId":"b","childrenIds":[],"role":"user",
                    "content":[{"type":"text","text":"ikinci sual"},
                               {"type":"image_url","image_url":{"url":"https://f/x.png"}}],
                    "timestamp":3,"models":["m"]}
            }
          }
        }""")
        val msgs = ChatParse.messages(detail)
        assertEquals(3, msgs.size)
        assertEquals(listOf("a", "b", "c"), msgs.map { it.id })
        assertEquals("sual", msgs[0].content)
        assertEquals("ikinci sual", msgs[2].content)
        assertEquals(1, msgs[2].attachments.size)
        assertEquals("image", msgs[2].attachments[0].media)
        assertEquals("Test chat", ChatParse.title(detail))
    }

    @Test
    fun chatParse_emptyHistory() {
        assertEquals(0, ChatParse.messages(JSONObject("""{"history":{"messages":{}}}""")).size)
        assertEquals(0, ChatParse.messages(JSONObject("""{}""")).size)
    }

    @Test
    fun chatParse_contentToText() {
        assertEquals("plain", ChatParse.contentToText("plain"))
        val arr = JSONArray("""[{"type":"text","text":"a"},{"type":"text","text":"b"}]""")
        assertEquals("a\nb", ChatParse.contentToText(arr))
        assertEquals("", ChatParse.contentToText(null))
    }

    // ---------------- xəta xəritələri ----------------

    @Test
    fun wafAndRetryableClassification() {
        assertTrue(isWafStatus(405)); assertTrue(isWafStatus(403))
        assertTrue(isWafStatus(429)); assertTrue(isWafStatus(509))
        assertFalse(isWafStatus(500)); assertFalse(isWafStatus(200))

        assertTrue(isRetryableError("RETRYABLE:x")); assertTrue(isRetryableError("WAF block (HTTP 405)"))
        assertTrue(isRetryableError("HTTP 502: bad gateway"))
        assertFalse(isRetryableError("HTTP 400: bad request"))
    }

    @Test
    fun friendlyErrors() {
        assertTrue(friendlyError("426", "").contains("update"))
        assertTrue(friendlyError("x", "user_limit hit").startsWith("RETRYABLE:"))
        assertTrue(friendlyError("x", "server is busy").startsWith("RETRYABLE:"))
        assertEquals("raw detail", friendlyError("x", "raw detail"))
    }

    @Test
    fun phaseLabels() {
        assertEquals("Thinking…", phaseLabel("reasoning"))
        assertEquals("Running code…", phaseLabel("code_interpreter"))
        assertEquals("Preparing…", phaseLabel("unknown_phase"))
    }

    // ---------------- köməkçi funksiyalar ----------------

    @Test
    fun mediaKindClassification() {
        assertEquals("image", ZaiApi.mediaKind("image/png", "f.txt"))
        assertEquals("image", ZaiApi.mediaKind("application/octet-stream", "f.PNG"))
        assertEquals("video", ZaiApi.mediaKind("video/mp4", "f.mp4"))
        assertEquals("video", ZaiApi.mediaKind("application/octet-stream", "f.webm"))
        assertEquals("doc", ZaiApi.mediaKind("application/pdf", "f.pdf"))
    }

    @Test
    fun humanSizeFormatting() {
        assertEquals("512 B", ZaiApi.humanSize(512))
        assertEquals("2.0 KB", ZaiApi.humanSize(2048))
        assertEquals("1.5 MB", ZaiApi.humanSize((1.5 * 1024 * 1024).toLong()))
    }

    @Test
    fun chatNewObject_structure() {
        val obj = chatNewObject("prompt", "m1", "u1", 1_700_000_000_000)
        assertEquals("", obj.optString("id"))
        assertEquals("New Chat", obj.optString("title"))
        assertEquals("general_agent", obj.optString("type"))
        val msgs = obj.optJSONObject("history")?.optJSONObject("messages")
        assertNotNull(msgs)
        val userMsg = msgs?.optJSONObject("u1")
        assertNotNull(userMsg)
        assertEquals("user", userMsg?.optString("role"))
        assertEquals("prompt", userMsg?.optString("content"))
    }

    // ---------------- device id ----------------

    @Test
    fun deviceIdFormat() {
        val id = Session.makeDeviceId()
        assertTrue("format yanlışdır: $id", id.matches(Regex("uid_[a-zA-Z0-9]{13}")))
        // toxum = vaxt ^ pid — eyni millisaniyədə eyni nəticə mümkündür,
        // ona görə unikallıq iddiası YOX (app-da bir dəfə yaranıb saxlanılır)
    }
}
