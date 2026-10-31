package zai.mobile

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import zai.mobile.engine.ChatEngine
import zai.mobile.engine.ChatState
import zai.mobile.engine.TurnEvent
import zai.mobile.protocol.AttachPart
import zai.mobile.protocol.Attachments
import zai.mobile.protocol.AuthApi
import zai.mobile.protocol.AuthInfo
import zai.mobile.protocol.AuthResult
import zai.mobile.protocol.ChatObjects
import zai.mobile.protocol.ChatsApi
import zai.mobile.protocol.CompletionApi
import zai.mobile.protocol.DeviceIds
import zai.mobile.protocol.ModelsApi
import zai.mobile.protocol.Phases
import zai.mobile.protocol.Signature
import zai.mobile.protocol.Sse
import zai.mobile.protocol.StreamEvent
import zai.mobile.protocol.StreamOutcome
import zai.mobile.protocol.UploadApi
import zai.mobile.protocol.ZaiException
import zai.mobile.protocol.ZaiModel
import zai.mobile.state.Session
import zai.mobile.ui.Md
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Protokol + mühərrik + markdown unit testləri (Robolectric — org.json üçün).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LogicTest {

    private val fp = Signature.Fingerprint(
        screenWidth = 1080, screenHeight = 2400, pixelRatio = "2.625",
        timezone = "Asia/Baku", timezoneOffsetMinutes = -240,
    )

    private fun http(script: ScriptedTransport) = zai.mobile.protocol.ZaiHttp(
        script, "uid_test0001234", "https://chat.z.ai",
    )

    // ---------- Signature ----------

    @Test
    fun hmacRfc4231Vector() {
        // RFC 4231 Test Case 1 (HMAC-SHA256): key=0x0b×20, msg="Hi There"
        val got = Signature.hmacHex(ByteArray(20) { 0x0b }, "Hi There")
        assertEquals("b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7", got)
    }

    @Test
    fun formEncodeCases() {
        assertEquals("hello+world", Signature.formEncode("hello world"))
        assertEquals("a*b-c.d_e", Signature.formEncode("a*b-c.d_e"))
        assertEquals("100%25", Signature.formEncode("100%"))
        assertEquals("%C9%99%21%7E", Signature.formEncode("ə!~"))
        assertEquals("", Signature.formEncode(""))
    }

    @Test
    fun signatureStructure() {
        val r = Signature.sign("Hello world", "u1", "tok123", fp)
        assertTrue(r.path.startsWith("/api/v2/chat/completions?"))
        assertTrue("timestamp əvvəl", r.path.indexOf("timestamp=") < r.path.indexOf("requestId="))
        assertTrue(r.path.indexOf("requestId=") < r.path.indexOf("user_id=u1"))
        assertTrue(r.path.indexOf("user_id=u1") < r.path.indexOf("version=0.0.1"))
        assertTrue(r.path.indexOf("version=0.0.1") < r.path.indexOf("platform=web"))
        assertTrue(r.path.indexOf("platform=web") < r.path.indexOf("token=tok123"))
        assertTrue(r.path.contains("timezone=Asia%2FBaku"))
        assertTrue(r.path.contains("timezone_offset=-240"))
        assertTrue(r.path.contains("screen_resolution=1080x2400"))
        assertTrue(r.path.endsWith("&signature_timestamp=${r.timestamp}"))
        assertEquals(64, r.xSignature.length)
        assertTrue(r.xSignature.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun deviceIdFormat() {
        val id = DeviceIds.new()
        assertTrue(DeviceIds.isValid(id))
        assertFalse(DeviceIds.isValid("uid_short"))
        assertFalse(DeviceIds.isValid("uid_with-symbol!"))
    }

    // ---------- SSE ----------

    private fun collect(stream: String, stopped: () -> Boolean = { false }): Pair<StreamOutcome, List<StreamEvent>> {
        val events = ArrayList<StreamEvent>()
        val outcome = Sse.read(
            stream.byteInputStream(),
            { },
            { e -> events.add(e) },
            stopped,
        )
        return outcome to events
    }

    @Test
    fun sseMultiLineJoin() {
        val stream = """
            data: {"data":
            data: {"message":{"delta_content":"Hi"}}}

            data: [DONE]
        """.trimIndent() + "\n"
        val (outcome, events) = collect(stream)
        assertEquals(StreamOutcome.COMPLETED, outcome)
        assertTrue(events.contains(StreamEvent.Delta("Hi")))
    }

    @Test
    fun sseDoneFlagWithoutDoneLine() {
        val stream = """
            data: {"data":{"message":{"delta_content":"A"}}}
            data: {"data":{"message":{"delta_content":"B","done":true}}}
        """.trimIndent() + "\n"
        val (outcome, events) = collect(stream)
        assertEquals(StreamOutcome.COMPLETED, outcome)
        assertEquals(2, events.filterIsInstance<StreamEvent.Delta>().size)
        assertTrue(events.contains(StreamEvent.Done))
    }

    @Test
    fun sseEofWithoutDoneIsDrop() {
        val stream = """
            data: {"data":{"message":{"delta_content":"A"}}}
        """.trimIndent() + "\n"
        val (outcome, _) = collect(stream)
        assertEquals(StreamOutcome.DROPPED, outcome)
    }

    @Test
    fun sseStopMidStream() {
        val lines = listOf(
            "data: {\"data\":{\"message\":{\"delta_content\":\"One\"}}}",
            "data: {\"data\":{\"message\":{\"delta_content\":\"Two\"}}}",
            "data: [DONE]",
        )
        var stopAfterFirst = false
        val events = ArrayList<StreamEvent>()
        val outcome = Sse.read(
            MergeStream(lines.map { it.byteInputStream() }.asSequence()),
            { },
            { e ->
                events.add(e)
                if (e is StreamEvent.Delta) stopAfterFirst = true
            },
            { stopAfterFirst },
        )
        assertEquals(StreamOutcome.STOPPED, outcome)
        assertTrue(events.contains(StreamEvent.Delta("One")))
        assertFalse(events.contains(StreamEvent.Delta("Two")))
    }

    @Test
    fun sseErrorEvent() {
        val stream = """
            data: {"error":{"error_code":"426","message":"version outdated"}}
        """.trimIndent() + "\n"
        val (outcome, events) = collect(stream)
        assertEquals(StreamOutcome.FAILED, outcome)
        val err = events.filterIsInstance<StreamEvent.Error>().single()
        assertTrue(err.ex is ZaiException.Outdated)
    }

    @Test
    fun sseMessagePathFallbacks() {
        val stream = """
            data: {"message":{"delta_content":"X"}}
            data: {"data":{"data":{"data":{"delta_content":"Y"}}}}
        """.trimIndent() + "\n"
        val (_, events) = collect(stream)
        assertTrue(events.contains(StreamEvent.Delta("X")))
        assertTrue(events.contains(StreamEvent.Delta("Y")))
    }

    @Test
    fun phaseLabels() {
        assertEquals("Thinking…", Phases.label("hidden-thinking"))
        assertEquals("Searching the web…", Phases.label("web_search"))
        assertEquals("Using tools…", Phases.label("tool_response"))
        assertEquals("Running code…", Phases.label("code_interpreter"))
        assertEquals("Writing title…", Phases.label("title"))
        assertEquals("Preparing…", Phases.label("whatever"))
    }

    // ---------- Errors ----------

    @Test
    fun friendlyErrorMapping() {
        assertTrue(ZaiException.fromHttp(401, "{\"detail\":\"The captcha verification failed.\"}") is ZaiException.Captcha)
        val rate = ZaiException.fromHttp(429, "{\"detail\":\"user_limit reached\"}")
        assertTrue(rate is ZaiException.RateLimit && rate.isRetryable)
        val busy = ZaiException.fromStreamError(JSONObject("{\"error_code\":\"x\",\"message\":\"global_limit\"}"))
        assertTrue(busy is ZaiException.Busy && busy.isRetryable)
        val dropped = ZaiException.Dropped()
        assertTrue(dropped.isRetryable)
        val outdated = ZaiException.fromStreamError(JSONObject("{\"code\":\"426\",\"detail\":\"outdated\"}"))
        assertFalse(outdated.isRetryable)
        val unauth = ZaiException.fromHttp(403, "{\"detail\":\"no\"}")
        assertTrue(unauth is ZaiException.Unauthorized && !unauth.isRetryable)
    }

    // ---------- ZaiHttp: WAF + cookies ----------

    @Test
    fun wafRetryAndCookies() {
        val script = ScriptedTransport()
        script.on({ it.method == "GET" && it.path == "/" }) { _, _ ->
            ScriptedTransport.Out(200, mapOf("set-cookie" to listOf("acw_tc=abc123; Path=/")) ) { "".byteInputStream() }
        }
        script.on({ it.method == "GET" && it.path.startsWith("/api/models") }) { call, attempt ->
            if (attempt <= 2) ScriptedTransport.waf() else ScriptedTransport.ok("{\"data\":[]}")
        }
        val h = http(script)
        h.warmUp()
        assertEquals("abc123", h.cookies["acw_tc"])
        val api = ModelsApi(h)
        api.list("tok", true)
        assertEquals(3, script.countOf("GET", "/api/models"))
        // cookie hər sorğuda gedir
        assertTrue(script.calls.last().header("Cookie")?.contains("acw_tc=abc123") == true)
        // warm-up cookie-ləri sorğuda gedir
        assertTrue(script.calls.any { it.header("Cookie")?.contains("acw_tc=abc123") == true })
    }

    @Test
    fun wafExhaustedThrows() {
        val script = ScriptedTransport()
        script.on({ it.method == "GET" && it.path == "/" }) { _, _ -> ScriptedTransport.ok("{}") }
        script.on({ it.method == "GET" && it.path.startsWith("/api/models") }) { _, _ -> ScriptedTransport.waf() }
        val h = http(script)
        h.warmUp()
        val api = ModelsApi(h)
        val ex = runCatching { api.list("tok", true) }.exceptionOrNull()
        assertTrue(ex is ZaiException.WafBlocked)
    }

    // ---------- Auth ----------

    @Test
    fun signinCaptchaDetection() {
        val script = ScriptedTransport()
        script.on({ it.method == "GET" && it.path == "/" }) { _, _ -> ScriptedTransport.ok("") }
        script.on({ it.method == "POST" && it.path.startsWith("/api/v1/auths/signin") }) { _, _ ->
            ScriptedTransport.bad(400, "{\"detail\":\"The captcha verification failed.\"}")
        }
        val api = AuthApi(http(script))
        val r = api.signin("a@b.c", "pw")
        assertTrue(r is AuthResult.Captcha)
    }

    @Test
    fun verifyInvalidReturnsNull() {
        val script = ScriptedTransport()
        script.on({ it.method == "GET" && it.path == "/" }) { _, _ -> ScriptedTransport.ok("") }
        script.on({ it.method == "GET" && it.path.startsWith("/api/v1/auths/") && it.header("Authorization") != null }) { _, _ ->
            ScriptedTransport.bad(401, "{\"detail\":\"bad token\"}")
        }
        val api = AuthApi(http(script))
        assertEquals(null, api.verify("expired"))
    }

    // ---------- Models ----------

    @Test
    fun modelsGuestFilter() {
        val body = """
            {"data":[
              {"id":"glm-flash","name":"GLM-5.3-Flash","info":{"min_available_level":1,"meta":{"info":{"thinking_effort_level":["low","high","max"]}}}},
              {"id":"glm-max","name":"GLM-5.3-Max","info":{"min_available_level":5,"meta":{"info":{"thinking_effort_level":["low","high"]}}}}
            ]}
        """.trimIndent()
        val script = ScriptedTransport()
        script.on({ it.method == "GET" && it.path == "/" }) { _, _ -> ScriptedTransport.ok("") }
        script.on({ it.method == "GET" && it.path.startsWith("/api/models") }) { _, _ -> ScriptedTransport.ok(body) }
        val api = ModelsApi(http(script))
        val guest = api.list("t", true)
        assertEquals(listOf("glm-flash"), guest.map { it.id })
        assertEquals(listOf("low", "high", "max"), guest[0].effortLevels)
        val user = api.list("t", false)
        assertEquals(2, user.size)
    }

    // ---------- Chats ----------

    private fun detailJson(title: String): String {
        return """
            {"title":"$title","history":{"currentId":"m3","messages":{
              "m1":{"id":"m1","parentId":null,"role":"user","content":"Hi there","timestamp":1},
              "m2":{"id":"m2","parentId":"m1","role":"assistant","content":"Hello! How can I help?","timestamp":2},
              "m3":{"id":"m3","parentId":"m2","role":"user","content":[{"type":"text","text":"See this"},{"type":"image_url","image_url":{"url":"https://x/i.png"}}],"timestamp":3}
            }}}
        """.trimIndent()
    }

    @Test
    fun chatDetailChainWalk() {
        val script = ScriptedTransport()
        script.on({ it.method == "GET" && it.path == "/" }) { _, _ -> ScriptedTransport.ok("") }
        script.on({ it.method == "GET" && it.path.startsWith("/api/v1/chats/c9") }) { _, _ ->
            ScriptedTransport.ok(detailJson("Chain test"))
        }
        val api = ChatsApi(http(script))
        val d = api.detail("t", "c9")
        assertEquals("Chain test", d.title)
        assertEquals(3, d.messages.size)
        assertEquals("user", d.messages[0].role)
        assertEquals("Hi there", d.messages[0].text)
        assertEquals("Hello! How can I help?", d.messages[1].text)
        assertEquals("See this", d.messages[2].text)
        assertEquals(1, d.messages[2].attachments.size)
        assertEquals("image_url", d.messages[2].attachments[0].kind)
        assertEquals("https://x/i.png", d.messages[2].attachments[0].url)
    }

    @Test
    fun chatPaginationStopsOnRepeatedFirstId() {
        val page = { n: Int ->
            """[{"id":"a$n","title":"T$n","updated_at":${1700000000 + n}},{"id":"b$n","title":"U$n","updated_at":1}]"""
        }
        val script = ScriptedTransport()
        script.on({ it.method == "GET" && it.path == "/" }) { _, _ -> ScriptedTransport.ok("") }
        script.on({ it.method == "GET" && it.path.startsWith("/api/v1/chats/") }) { call, _ ->
            val p = call.path.substringAfter("page=").substringBefore("&").toInt()
            // səhifə 2 eyni ilk id "a1" qaytarır → təkrar aşkarlanır
            ScriptedTransport.ok(if (p == 1) page(1) else """[{"id":"a1","title":"T1","updated_at":1}]""")
        }
        val api = ChatsApi(http(script))
        val list = api.list("t")
        assertEquals(2, list.size)
        assertEquals("a1", list[0].id)
    }

    // ---------- Upload ----------

    @Test
    fun uploadParseAndMultipart() {
        val script = ScriptedTransport()
        script.on({ it.method == "GET" && it.path == "/" }) { _, _ -> ScriptedTransport.ok("") }
        script.on({ it.method == "POST" && it.path.startsWith("/api/v1/storage") }) { call, _ ->
            assertTrue(call.bodyText.contains("name=\"file\"; filename=\"pic.png\""))
            assertTrue(call.bodyText.contains("Content-Type: image/png"))
            ScriptedTransport.ok(
                """{"code":0,"data":{"file":{"id":"f1","filename":"pic.png","type":"image","direct_url":"https://z.ai/f1.png"}}}""",
            )
        }
        val api = UploadApi(http(script))
        val res = api.upload("t", zai.mobile.protocol.PreparedFile("pic.png", "image/png", ByteArray(10), "image"))
        assertEquals("f1", res.fileId)
        assertEquals("https://z.ai/f1.png", res.directUrl)
        val part = res.toPart()
        assertEquals("image_url", part.kind)
        assertEquals("https://z.ai/f1.png", part.url)
    }

    @Test
    fun uploadNoDirectUrlFallsBackToFileId() {
        val script = ScriptedTransport()
        script.on({ it.method == "GET" && it.path == "/" }) { _, _ -> ScriptedTransport.ok("") }
        script.on({ it.method == "POST" && it.path.startsWith("/api/v1/storage") }) { _, _ ->
            ScriptedTransport.ok(
                """{"code":0,"data":{"file":{"id":"f9","filename":"doc.pdf","type":"doc","direct_url":""}}}""",
            )
        }
        val api = UploadApi(http(script))
        val res = api.upload("t", zai.mobile.protocol.PreparedFile("doc.pdf", "application/pdf", ByteArray(4), "doc"))
        assertEquals("file_url", res.toPart().kind)
        assertEquals("f9", res.toPart().url)
    }

    @Test
    fun attachmentsLimitsAndClassify() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val at = Attachments(ctx.contentResolver)
        assertEquals("image", at.classify("image/png", "x.png"))
        assertEquals("video", at.classify("video/mp4", "x.mp4"))
        assertEquals("doc", at.classify("application/pdf", "x.pdf"))
        assertEquals("image", at.classify("", "photo.GIF"))
        // 60MB-dən böyük fayl hazırlanmır (SAF ölçüsündən yoxlanılır)
        val big = zai.mobile.protocol.PickedFile(
            android.net.Uri.parse("content://x/1"), "big.bin", "application/octet-stream",
            Attachments.MAX_BYTES + 1,
        )
        assertTrue(at.prepare(big) == null)
    }

    // ---------- Session ----------

    @Test
    fun sessionRoundTripAndDevicePersistence() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val s1 = Session(ctx)
        val dev = s1.deviceId
        assertTrue(DeviceIds.isValid(dev))
        s1.saveAuth(AuthInfo("tok1", "u1", "Tester", false))
        s1.saveModelSelection("glm-flash", "high")
        val s2 = Session(ctx)
        assertEquals("tok1", s2.token)
        assertEquals("u1", s2.userId)
        assertFalse(s2.isGuest)
        assertEquals(dev, s2.deviceId)
        assertEquals("glm-flash", s2.modelId)
        s2.clearAuth()
        val s3 = Session(ctx)
        assertEquals("", s3.token)
        assertTrue(s3.isGuest)
        assertEquals(dev, s3.deviceId) // deviceId qalır
        assertEquals("glm-flash", s3.modelId) // model seçimi qalır
    }

    // ---------- ChatObjects ----------

    @Test
    fun chatObjectShapes() {
        val ts = 1700000000000L
        val new = ChatObjects.newChat("glm-flash", "Hi", "u1", ts)
        val hist = new.getJSONObject("history")
        assertEquals("u1", hist.getString("currentId"))
        val msg = hist.getJSONObject("messages").getJSONObject("u1")
        assertEquals("user", msg.getString("role"))
        assertEquals("Hi", msg.getString("content"))
        assertEquals("high", new.getString("reasoning_effort"))
        assertEquals("general_agent", new.getString("type"))

        val upd = ChatObjects.updatedChat(
            model = "glm-flash", modelName = "GLM",
            turns = listOf("user" to "Hi", "assistant" to "Hello!"),
            userMsgId = "u1", assistantMsgId = "a1",
            reasoningEffort = "max", ts = ts,
        )
        val um = upd.getJSONObject("history")
        assertEquals("a1", um.getString("currentId"))
        val a1 = um.getJSONObject("messages").getJSONObject("a1")
        assertEquals("assistant", a1.getString("role"))
        assertTrue(a1.getBoolean("done"))
        assertFalse(a1.getBoolean("error"))
        assertEquals("max", upd.getString("reasoning_effort"))
    }

    // ---------- Engine: tam dönüş + Reset ----------

    private val model = ZaiModel("glm-flash", "GLM-5.3-Flash", 1, listOf("low", "high", "max"))

    private fun engineWith(
        script: ScriptedTransport,
        session: Session,
    ): ChatEngine {
        val h = http(script)
        return ChatEngine(AuthApi(h), ChatsApi(h), CompletionApi(h), session)
    }

    @Test
    fun engineFullTurnWithWafAndReset() {
        val script = ScriptedTransport()
        script.on({ it.method == "GET" && it.path == "/" }) { _, _ -> ScriptedTransport.ok("") }
        script.on({ it.method == "GET" && it.path.startsWith("/api/v1/auths/") && it.header("Authorization") == null }) { _, _ ->
            ScriptedTransport.ok("{\"token\":\"gtok\",\"id\":\"u1\",\"name\":\"Guest\",\"role\":\"guest\"}")
        }
        script.on({ it.method == "POST" && it.path.startsWith("/api/v1/chats/new") }) { _, _ ->
            ScriptedTransport.ok("{\"id\":\"c1\"}")
        }
        script.on({ it.method == "POST" && it.path.startsWith("/api/v2/chat/completions") }) { _, attempt ->
            when (attempt) {
                1 -> ScriptedTransport.waf()
                2 -> ScriptedTransport.sse(
                    // çoxsətirli + qismən, sonra EOF ([DONE] YOX) → DROPPED
                    """{"data":{"message":""",
                    """{"delta_content":"Hel"}}""",
                    """{"data":{"message":{"delta_content":"lo"}}}""",
                )
                else -> ScriptedTransport.sse(
                    """{"data":{"message":{"phase":"hidden-thinking"}}}""",
                    """{"data":{"message":{"delta_content":"Hello"}}}""",
                    """{"data":{"message":{"delta_content":" world"}}}""",
                    """{"data":{"message":{"done":true}}}""",
                    "[DONE]",
                )
            }
        }
        script.on({ it.method == "POST" && it.path.startsWith("/api/v1/chats/c1") }) { _, _ ->
            ScriptedTransport.ok("{}")
        }
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val session = Session(ctx)
        session.saveAuth(AuthInfo("gtok", "u1", "Guest", true))
        val engine = engineWith(script, session)
        val state = ChatState()
        val events = ArrayList<TurnEvent>()

        runBlocking {
            engine.sendTurn(state, "Hello", emptyList(), model, "high", fp) { events.add(it) }
        }

        assertTrue(events.contains(TurnEvent.ChatCreated("c1")))
        assertEquals("c1", state.chatId)
        // Reset gəldi (1-ci cəhdin qismən mətni təmizləndi)
        assertTrue(events.contains(TurnEvent.Reset))
        // son nəticə
        val completed = events.filterIsInstance<TurnEvent.Completed>().single()
        assertEquals("Hello world", completed.fullText)
        // fazalar
        assertTrue(events.filterIsInstance<TurnEvent.Phase>().any { it.label == "Thinking…" })
        // turns
        assertEquals(2, state.turns.size)
        assertEquals("user", state.turns[0].role)
        assertEquals("Hello world", state.turns[1].text)
        // update çağırıldı
        assertEquals(1, script.countOf("POST", "/api/v1/chats/c1"))
        // completions açılışları: 1 WAF + 1 DROPPED-axını + 1 uğurlu
        assertEquals(3, script.countOf("POST", "/api/v2/chat/completions"))
    }

    @Test
    fun engineGuest401ReauthOnce() {
        val script = ScriptedTransport()
        script.on({ it.method == "GET" && it.path == "/" }) { _, _ -> ScriptedTransport.ok("") }
        script.on({ it.method == "GET" && it.path.startsWith("/api/v1/auths/") }) { call, _ ->
            if (call.header("Authorization") == null) {
                // qonaq tokeni (yenilənmiş)
                ScriptedTransport.ok("{\"token\":\"gtok2\",\"id\":\"u1\",\"name\":\"Guest\",\"role\":\"guest\"}")
            } else {
                ScriptedTransport.bad(401, "{\"detail\":\"expired\"}")
            }
        }
        script.on({ it.method == "POST" && it.path.startsWith("/api/v1/chats/new") }) { _, _ ->
            ScriptedTransport.ok("{\"id\":\"c1\"}")
        }
        script.on({ it.method == "POST" && it.path.startsWith("/api/v2/chat/completions") }) { call, attempt ->
            // 1-ci streamOnce: 401 → qonaq yenidən; 2-ci: uğurlu axın
            if (attempt == 1) {
                ScriptedTransport.bad(401, "{\"detail\":\"token expired\"}")
            } else {
                assertTrue(call.header("Authorization")!!.contains("gtok2"))
                ScriptedTransport.sse(
                    """{"data":{"message":{"delta_content":"OK"}}}""",
                    "[DONE]",
                )
            }
        }
        script.on({ it.method == "POST" && it.path.startsWith("/api/v1/chats/c1") }) { _, _ ->
            ScriptedTransport.ok("{}")
        }
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val session = Session(ctx)
        session.saveAuth(AuthInfo("gtok", "u1", "Guest", true))
        val engine = engineWith(script, session)
        val state = ChatState()
        val events = ArrayList<TurnEvent>()
        runBlocking {
            engine.sendTurn(state, "Ping", emptyList(), model, "high", fp) { events.add(it) }
        }
        assertEquals("gtok2", session.token)
        val completed = events.filterIsInstance<TurnEvent.Completed>().single()
        assertEquals("OK", completed.fullText)
    }

    @Test
    fun engineAccount401FailsNonRetryable() {
        val script = ScriptedTransport()
        script.on({ it.method == "GET" && it.path == "/" }) { _, _ -> ScriptedTransport.ok("") }
        script.on({ it.method == "POST" && it.path.startsWith("/api/v1/chats/new") }) { _, _ ->
            ScriptedTransport.ok("{\"id\":\"c1\"}")
        }
        script.on({ it.method == "POST" && it.path.startsWith("/api/v2/chat/completions") }) { _, _ ->
            ScriptedTransport.bad(401, "{\"detail\":\"session expired\"}")
        }
        script.on({ it.method == "POST" && it.path.startsWith("/api/v1/chats/c1") }) { _, _ ->
            ScriptedTransport.ok("{}")
        }
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val session = Session(ctx)
        session.saveAuth(AuthInfo("utok", "u2", "Tester", false))
        val engine = engineWith(script, session)
        val state = ChatState()
        val events = ArrayList<TurnEvent>()
        runBlocking {
            engine.sendTurn(state, "Ping", emptyList(), model, "high", fp) { events.add(it) }
        }
        val failed = events.filterIsInstance<TurnEvent.Failed>().singleOrNull()
        assertNotNull(failed)
        assertFalse(failed!!.canRetry)
        // xəta markeri turns-da
        assertTrue(state.turns.last().error)
    }

    // ---------- Markdown ----------

    @Test
    fun markdownParse() {
        val src = """
            ## Title

            - a **bold**
            - b

            ```kotlin
            fun x()
            ```

            > quoted line

            [link](https://x.y) and *it* and `mono` and ~~strike~~.
        """.trimIndent()
        val blocks = Md.parse(src)
        assertTrue(blocks[0] is Md.Block.Heading)
        assertEquals(2, (blocks[1] as Md.Block.ListBlock).items.size)
        assertFalse((blocks[1] as Md.Block.ListBlock).ordered)
        val code = blocks[2] as Md.Block.Code
        assertEquals("kotlin", code.lang)
        assertTrue(code.text.contains("fun x()"))
        assertTrue(blocks[3] is Md.Block.Quote)
        val para = blocks[4] as Md.Block.Para
        // inline növləri
        val kinds = para.inl.map { it::class.simpleName }
        assertTrue(kinds.contains("Link"))
        assertTrue(kinds.contains("It"))
        assertTrue(kinds.contains("Mono"))
        assertTrue(kinds.contains("Strike"))
    }

    @Test
    fun markdownUnclosedFenceAndBold() {
        val blocks = Md.parse("text\n```js\nunclosed")
        val code = blocks.last() as Md.Block.Code
        assertEquals("js", code.lang)
        assertTrue(code.text.contains("unclosed"))

        val inl = Md.parseInline("**b *i* c**")
        val bold = inl[0] as Md.Inline.Bold
        assertEquals(3, bold.inner.size)
        assertTrue(bold.inner[1] is Md.Inline.It)

        // bağlanmamış ** literal qalır
        val lit = Md.parseInline("**no close")
        assertTrue(lit.single() is Md.Inline.Txt)
        assertEquals("**no close", (lit.single() as Md.Inline.Txt).v)
    }

    @Test
    fun markdownRenderBuildsViews() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val box = android.widget.LinearLayout(ctx)
        Md.render(
            ctx, box,
            Md.parse("# H\n\npara with [l](https://x)\n\n- i1\n- i2\n\n```go\ncode\n```"),
            { },
        )
        assertTrue(box.childCount >= 4)
    }

    // ---------- Sse.at yolu ----------

    @Test
    fun sseAtPathWalk() {
        val o = JSONObject("""{"data":{"message":{"delta_content":"D"}}}""")
        assertNotNull(Sse.at(o, "/data/message"))
        assertTrue(Sse.at(o, "/data/data/message") == null)
        assertEquals("D", Sse.at(o, "/data/message")!!.optString("delta_content"))
    }

    // ---------- yardımçı axın sinifi (STOPPED testi üçün) ----------

    private class MergeStream(private val seq: Sequence<InputStream>) : InputStream() {
        private val it = seq.iterator()
        private var current: InputStream? = null
        override fun read(): Int {
            while (true) {
                val c = current
                if (c == null) {
                    if (!it.hasNext()) return -1
                    current = it.next()
                    continue
                }
                val r = c.read()
                if (r >= 0) return r
                current = null
            }
        }
    }
}
