package zai.mobile

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import zai.mobile.state.Session
import zai.mobile.ui.BootActivity
import zai.mobile.ui.ChatActivity
import zai.mobile.ui.LoginActivity

/**
 * "Proqram açılır" sübutu — hər ekranı Robolectric-də REAL lifecycle ilə açır,
 * skriptləşdirilmiş (real formalarlı) transportla tam axını işə salır.
 * Bu, istifadəçinin "app açılmır" probleminin bütün sinifini yoxlayır.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StartupSmokeTest {

    private lateinit var script: ScriptedTransport

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        App.resetForTest()
        App.ioForTest = UnconfinedTestDispatcher()
        script = ScriptedTransport()
        App.transportForTest = script
        commonScripts()
    }

    @After
    fun tearDown() {
        App.resetForTest()
        Dispatchers.resetMain()
    }

    private fun commonScripts() {
        script.on({ it.method == "GET" && it.path == "/" }) { _, _ ->
            ScriptedTransport.Out(
                200,
                mapOf("set-cookie" to listOf("acw_tc=warm123; Path=/")),
            ) { "".byteInputStream() }
        }
        script.on({ it.method == "GET" && it.path.startsWith("/api/v1/auths/") }) { call, _ ->
            if (call.header("Authorization") == null) {
                ScriptedTransport.ok(
                    """{"token":"gtok","id":"u1","name":"Guest-1793467000","role":"guest"}""",
                )
            } else {
                ScriptedTransport.ok(
                    """{"token":"utok","id":"u2","name":"Tester","role":"user"}""",
                )
            }
        }
        script.on({ it.method == "GET" && it.path.startsWith("/api/models") }) { _, _ ->
            ScriptedTransport.ok(
                """{"data":[{"id":"x-preview-l","name":"GLM-5.3-Flash",""" +
                    """"info":{"min_available_level":1,"meta":{"info":{"thinking_effort_level":["low","high","max"]}}}}]}""",
            )
        }
        script.on({ it.method == "POST" && it.path.startsWith("/api/v1/auths/signin") }) { _, _ ->
            ScriptedTransport.bad(400, "{\"detail\":\"The captcha verification failed.\"}")
        }
    }

    // ---- view ağacı axtarışları ----

    private fun <T : View> findAll(root: View, cls: Class<T>, out: ArrayList<T>) {
        if (cls.isInstance(root)) out.add(cls.cast(root)!!)
        if (root is ViewGroup) for (i in 0 until root.childCount) findAll(root.getChildAt(i), cls, out)
    }

    private fun texts(root: View): List<String> {
        val out = ArrayList<TextView>()
        findAll(root, TextView::class.java, out)
        return out.map { it.text.toString() }
    }

    private fun editTexts(root: View): List<EditText> {
        val out = ArrayList<EditText>()
        findAll(root, EditText::class.java, out)
        return out
    }

    private fun flushMain() {
        Shadows.shadowOf(Looper.getMainLooper()).runToEndOfTasks()
    }

    // ---- testlər ----

    @Test
    fun bootSplashToChatAsGuest() {
        val controller = Robolectric.buildActivity(BootActivity::class.java)
        controller.create().start().resume()
        flushMain()

        val app = ApplicationProvider.getApplicationContext<Application>()
        val next = Shadows.shadowOf(app).nextStartedActivity
        assertNotNull("ChatActivity açılış niyyəti gəlmədi", next)
        assertEquals(ChatActivity::class.java.name, next.component?.className)
        assertTrue(controller.get().isFinishing)

        val session = Session(app)
        assertEquals("gtok", session.token)
        assertTrue(session.isGuest)
        // WAF cookie jar dolub
        assertTrue(script.calls.first().let { it.method == "GET" && it.path == "/" })
        assertTrue(script.calls.any { it.header("Cookie")?.contains("acw_tc=warm123") == true })
    }

    @Test
    fun bootNetworkErrorShowsRetry() {
        script.on({ it.method == "GET" && it.path == "/" }) { _, _ ->
            throw java.io.IOException("no route to host")
        }
        val controller = Robolectric.buildActivity(BootActivity::class.java)
        controller.create().start().resume()
        flushMain()
        // error UI görünür; istifadəçi Retry basa bilər
        val all = texts(controller.get().window.decorView)
        assertTrue(all.contains(controller.get().getString(R.string.err_title)))
    }

    @Test
    fun chatScreenWelcomeAndFullSendFlow() {
        // söhbət axını skriptləri
        script.on({ it.method == "POST" && it.path.startsWith("/api/v1/chats/new") }) { _, _ ->
            ScriptedTransport.ok("{\"id\":\"c1\"}")
        }
        script.on({ it.method == "POST" && it.path.startsWith("/api/v2/chat/completions") }) { _, _ ->
            ScriptedTransport.sse(
                """{"data":{"message":{"phase":"hidden-thinking"}}}""",
                """{"data":{"message":{"delta_content":"Hello"}}}""",
                """{"data":{"message":{"delta_content":" world"}}}""",
                """{"data":{"message":{"done":true}}}""",
                "[DONE]",
            )
        }
        script.on({ it.method == "POST" && it.path.startsWith("/api/v1/chats/c1") && it.path != "/api/v1/chats/c1x" }) { _, _ ->
            ScriptedTransport.ok("{}")
        }
        script.on({ it.method == "GET" && it.path.startsWith("/api/v1/chats/c1") }) { _, _ ->
            ScriptedTransport.ok(
                """{"title":"Probe title","history":{"currentId":"m1","messages":""" +
                    """{"m1":{"id":"m1","parentId":null,"role":"assistant","content":"Hello world","timestamp":1}}}}""",
            )
        }

        val controller = Robolectric.buildActivity(ChatActivity::class.java)
        controller.create().start().resume()
        flushMain()
        val activity = controller.get()
        val decor = activity.window.decorView

        // modellər canlı yükləndi
        assertTrue(texts(decor).contains("GLM-5.3-Flash"))
        // welcome görünür
        assertTrue(texts(decor).contains(activity.getString(R.string.welcome_hint)))

        // yaz + göndər (real düymə yolu)
        val edits = editTexts(decor)
        assertTrue(edits.isNotEmpty())
        edits.first().setText("Say Hello")
        val buttons = ArrayList<ImageButton>()
        findAll(decor, ImageButton::class.java, buttons)
        val send = buttons.first { it.contentDescription == activity.getString(R.string.cd_send) }
        send.performClick()
        flushMain()

        // istifadə balonu + axın mətni render olundu
        assertTrue(texts(decor).contains("Say Hello"))
        assertTrue("axın mətni render olunmalı", texts(decor).contains("Hello world"))
        // başlıq serverdən gəldi
        assertTrue(texts(decor).contains("Probe title"))
        // chats/new və update çağırıldı
        assertEquals(1, script.countOf("POST", "/api/v1/chats/new"))
        assertTrue(script.countOf("POST", "/api/v1/chats/c1") >= 1)
        assertTrue(script.countOf("POST", "/api/v2/chat/completions") >= 1)
        // imza başlığı gedib
        val comp = script.calls.first { it.path.startsWith("/api/v2/chat/completions") }
        assertNotNull(comp.header("X-Signature"))
        assertTrue(comp.header("X-Signature")!!.length == 64)
        assertTrue(comp.path.contains("signature_timestamp="))
        assertTrue(comp.bodyText.contains("\"signature_prompt\":\"Say Hello\""))
    }

    @Test
    fun chatModelsFailShowsBannerNoFallback() {
        // eyni path üçün ilk qayda qalır → təmizlə, yalnız xəta qaydası qoy
        script.clearRules()
        script.on({ it.method == "GET" && it.path.startsWith("/api/models") }) { _, _ ->
            ScriptedTransport.bad(500, "{\"detail\":\"boom\"}")
        }
        val controller = Robolectric.buildActivity(ChatActivity::class.java)
        controller.create().start().resume()
        flushMain()
        val activity = controller.get()
        val all = texts(activity.window.decorView)
        // statik fallback YOXDUR — düz xəta görünür
        assertTrue(all.contains(activity.getString(R.string.models_failed)))
        assertTrue("heç bir model adı göstərilməməli", !all.contains("GLM-5.3-Flash"))
    }

    @Test
    fun loginCaptchaFallsBackToToken() {
        val controller = Robolectric.buildActivity(LoginActivity::class.java)
        controller.create().start().resume()
        flushMain()
        val activity = controller.get()
        val decor = activity.window.decorView

        val edits = editTexts(decor)
        assertTrue(edits.size >= 2)
        edits[0].setText("user@example.com")
        edits[1].setText("secret123")
        // Continue düyməsi: textButton -> android.widget.Button
        val btns = ArrayList<android.widget.Button>()
        findAll(decor, android.widget.Button::class.java, btns)
        val cont = btns.first { it.text == activity.getString(R.string.continue_label) }
        cont.performClick()
        flushMain()

        // captcha → token addımı görünür
        assertTrue(texts(decor).contains(activity.getString(R.string.login_captcha_title)))
        val tokenEdits = editTexts(decor).filter { it.hint == activity.getString(R.string.token_hint) }
        assertTrue(tokenEdits.isNotEmpty())
        val jwt = "eyJhbGciOiJFUzI1NiIsInR5cCI6IkpXVCJ9.testtoken." + "x".repeat(80)
        tokenEdits.first().setText(jwt)
        val apply = btns.first { it.text == activity.getString(R.string.apply) }
        apply.performClick()
        flushMain()

        assertTrue(activity.isFinishing)
        val session = Session(ApplicationProvider.getApplicationContext<Application>())
        assertEquals("utok", session.token)
        assertTrue(!session.isGuest)
        assertEquals("Tester", session.userName)
    }

    @Test
    fun crashReportShowsOnNextBoot() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        java.io.File(app.filesDir, "last_crash.txt").writeText(
            "time: 1\nstack:\njava.lang.RuntimeException: probe crash",
        )
        val controller = Robolectric.buildActivity(BootActivity::class.java)
        controller.create().start().resume()
        flushMain()
        // diaqnoz dialoqu açıldı və fayl təmizləndi
        assertTrue(!java.io.File(app.filesDir, "last_crash.txt").exists())
        // dialoq ayrı pəncərədədir — ShadowDialog ilə tap
        val shown = org.robolectric.shadows.ShadowDialog.getShownDialogs()
        assertTrue(shown.isNotEmpty())
        val decor = shown.last().window!!.decorView
        assertTrue(texts(decor).any { it.contains("probe crash") })
    }
}
