package zai.mobile

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * BAŞLANĞIC SMOKE TESTİ — "proqram açılmır" halını JVM-də təkrarlamaq üçün
 * hər iki Activity-nin onCreate + tam UI quruluşu işə salınır.
 * Şəbəkə: lokal ServerSocket (hermetik, sürətli) — baseOverride ilə yönləndirilir.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StartupSmokeTest {

    private var server: Tiny401Server? = null

    @After
    fun tearDown() {
        ZaiApi.baseOverride = null
        server?.stop()
    }

    @Test
    fun loginActivity_buildsAndShowsSignIn() {
        ZaiApi.baseOverride = startLocalServer()
        val controller = Robolectric.buildActivity(LoginActivity::class.java).setup()
        val a = controller.get()
        val texts = allTexts(a.findViewById(android.R.id.content))
        assertTrue("başlıq yoxdur: $texts", texts.any { it.contains("Sign in to Z.ai") })
        assertTrue(texts.any { it.contains("Continue with Email") })
        assertTrue(texts.any { it.contains("Skip for now") })
        assertTrue(texts.any { it.contains("Terms of Service") })
        controller.destroy()
    }

    @Test
    fun chatActivity_buildsWithAuthAndWelcome() {
        ZaiApi.baseOverride = startLocalServer()
        // saxta auth seed — Login-i ötür, birbaşa Chat açılır
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("zai_state", Context.MODE_PRIVATE).edit()
            .putString("token", "eyJfake.token.for.smoke")
            .putString("user_id", "u-smoke")
            .putString("user_name", "SmokeUser")
            .putBoolean("is_guest", false)
            .putString("device_id", "uid_smoketest123")
            .commit()

        val controller = Robolectric.buildActivity(ChatActivity::class.java).setup()
        val a = controller.get()
        val texts = allTexts(a.findViewById(android.R.id.content))
        assertTrue("welcome başlığı yoxdur: $texts", texts.any { it.contains("More than coding") })
        assertTrue("istifadəçi adı yoxdur: $texts", texts.any { it.contains("SmokeUser") })
        assertTrue("kompozitor yoxdur: $texts", texts.any { it.contains("Send a Message") })
        assertTrue("footer yoxdur: $texts", texts.any { it.contains("By using Z.ai") })
        assertTrue("model çipi yoxdur: $texts", texts.any { it.contains("GLM") })
        controller.destroy()
    }

    @Test
    fun markdown_renderProducesViews() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val box = LinearLayout(ctx)
        Markdown.render(
            ctx,
            MarkdownParse.parse(
                "# H1 başlıq\n\nSalam **dünya** `kod` [keçid](https://x.y)\n\n" +
                    "```kotlin\nfun x() {}\n```\n\n- madde\n1. birinci\n> sitat"),
            box)
        assertTrue("kifayət qədər view yoxdur: ${box.childCount}", box.childCount >= 6)
        val texts = allTexts(box)
        assertTrue(texts.any { it.contains("H1 başlıq") })
        assertTrue(texts.any { it.contains("fun x() {}") })
        assertTrue(texts.any { it.contains("madde") })
    }

    @Test
    fun crashLog_roundTrip() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        CrashLog.install(ctx)
        val f = java.io.File(ctx.filesDir, "last_crash.txt")
        f.parentFile?.mkdirs()
        f.writeText("time: test\ncrash: simulated")
        assertTrue(f.exists())
        // Login açılışı crash hesabatını göstərib faylı silməlidir
        ZaiApi.baseOverride = startLocalServer()
        val controller = Robolectric.buildActivity(LoginActivity::class.java).setup()
        assertTrue("crash faylı silinməlidir", !f.exists())
        controller.destroy()
    }

    // ---------------- lokal HTTP server (xalis java.net) ----------------

    private fun startLocalServer(): String {
        val s = Tiny401Server()
        s.start()
        server = s
        return s.base()
    }

    /** Hər sorğuya 401 + JSON qaytaran minik server. */
    private class Tiny401Server {
        private val serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        @Volatile private var running = true

        fun start() {
            Thread {
                while (running) {
                    val socket = try { serverSocket.accept() } catch (_: Exception) { break }
                    serve(socket)
                }
            }.apply { isDaemon = true; name = "tiny401-accept" }.start()
        }

        private fun serve(socket: Socket) {
            Thread {
                try {
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
                    // header-ları boş sətirə qədər oxu (GET body-si yoxdur)
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    val body = """{"detail":"offline test"}""".toByteArray(Charsets.UTF_8)
                    socket.getOutputStream().apply {
                        write(
                            ("HTTP/1.1 401 Unauthorized\r\n" +
                                "Content-Type: application/json\r\n" +
                                "Content-Length: ${body.size}\r\n" +
                                "Connection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
                        write(body)
                        flush()
                    }
                } catch (_: Exception) {
                } finally {
                    runCatching { socket.close() }
                }
            }.apply { isDaemon = true; name = "tiny401-serve" }.start()
        }

        fun stop() {
            running = false
            runCatching { serverSocket.close() }
        }

        fun base(): String = "http://127.0.0.1:${serverSocket.localPort}"
    }

    // ---------------- köməkçilər ----------------

    private fun allTexts(v: View?): List<String> {
        val out = mutableListOf<String>()
        fun walk(x: View) {
            if (x is TextView) {
                out += x.text?.toString().orEmpty()
                if (x is android.widget.EditText) out += x.hint?.toString().orEmpty()
            }
            if (x is ViewGroup) for (i in 0 until x.childCount) walk(x.getChildAt(i))
        }
        v?.let { walk(it) }
        return out
    }
}
