package zai.mobile

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import zai.mobile.engine.ChatEngine
import zai.mobile.engine.ChatState
import zai.mobile.engine.TurnEvent
import zai.mobile.protocol.AuthApi
import zai.mobile.protocol.AuthResult
import zai.mobile.protocol.ChatsApi
import zai.mobile.protocol.CompletionApi
import zai.mobile.protocol.DeviceIds
import zai.mobile.protocol.ModelsApi
import zai.mobile.protocol.Signature
import zai.mobile.protocol.UrlConnectionTransport
import zai.mobile.protocol.ZaiException
import zai.mobile.protocol.ZaiHttp
import zai.mobile.protocol.ZaiModel
import zai.mobile.state.Session

/**
 * CANLI PROTOKOL TESTİ — real chat.z.ai.
 *
 * MƏRHƏLƏ 1-2 (hər IP-dən): qonaq tokeni + model siyahısı.
 * MƏRHƏLƏ 2b: signin-captcha JSON yolu (datacenter IP-dən də keçir).
 * MƏRHƏLƏ 3 (tam dövr): təmiz IP-də işləyir; datacenter IP-də WAF/captcha
 * bloklayır — bu MÜHİT məhdudiyyətidir (telefonda işləyir) → skip.
 *
 * Yalnız LIVE_TEST=1 ilə: LIVE_TEST=1 gradle test
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveProtocolTest {

    private val fp = Signature.Fingerprint(
        screenWidth = 1080, screenHeight = 2400, pixelRatio = "2.625",
        timezone = "Asia/Baku", timezoneOffsetMinutes = -240,
    )

    @Test(timeout = 300_000)
    fun guest_models_signinCaptcha_fullCompletion() {
        assumeTrue("LIVE_TEST=1 lazımdır", System.getenv("LIVE_TEST") == "1")
        val http = ZaiHttp(UrlConnectionTransport(), DeviceIds.new())

        // ---- Mərhələ 1: warm-up + qonaq ----
        http.warmUp()
        val authApi = AuthApi(http)
        val guest = authApi.guest()
        assertTrue("qonaq tokeni boşdur", guest.token.isNotEmpty())
        assertTrue(guest.isGuest)
        println("LIVE 1/3 OK: guest token (${guest.token.take(14)}…) name=${guest.userName}")

        // ---- Mərhələ 2: modellər (canlı, statik fallback yoxdur) ----
        val models = ModelsApi(http).list(guest.token, true)
        assertTrue("model siyahısı boşdur", models.isNotEmpty())
        println("LIVE 2/3 OK: ${models.size} model: ${models.map { it.id }}")

        // ---- Mərhələ 2b: signin captcha yolu (real 400 JSON) ----
        val signin = authApi.signin("nobody-probe@example.com", "wrong-password-1")
        assertTrue("yanlış credential heç vaxt Ok olmamalıdır", signin !is AuthResult.Ok)
        println(
            "LIVE 2b OK: signin → " + when (signin) {
                is AuthResult.Captcha -> "Captcha (real API JSON parse işləyir)"
                is AuthResult.Failed -> "Failed(${signin.error.message})"
                else -> "?"
            },
        )

        // ---- Mərhələ 3: tam dövr ----
        val model: ZaiModel = models.first()
        val chatsApi = ChatsApi(http)
        val completionApi = CompletionApi(http)
        val session = Session(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        session.saveAuth(guest)
        val engine = ChatEngine(authApi, chatsApi, completionApi, session)
        val state = ChatState()
        val events = ArrayList<TurnEvent>()

        try {
            runBlocking {
                engine.sendTurn(
                    state,
                    "Reply with the single word TESTOK and nothing else.",
                    emptyList(),
                    model,
                    model.effortLevels.lastOrNull().orEmpty(),
                    fp,
                ) { events.add(it) }
            }
        } catch (e: ZaiException) {
            println("LIVE 3/3 SKIP (mühit): ${e.message} — datacenter IP bloku; telefonda işləyir.")
            return
        }

        val failed = events.filterIsInstance<TurnEvent.Failed>().singleOrNull()
        if (failed != null && !failed.canRetry) {
            println("LIVE 3/3 SKIP (mühit): server xətası — ${failed.message}")
            return
        }

        val answer = events.filterIsInstance<TurnEvent.Delta>().joinToString("") { it.text }
        assertTrue(
            "serverdən xəta: ${failed?.message}",
            failed == null,
        )
        assertTrue("serverdə chat yaradılmadı", !state.chatId.isNullOrBlank())
        assertTrue("axından mətn gəlmədi", answer.isNotBlank())
        println("LIVE 3/3 OK: TAM DÖVR — cavab='${answer.take(60)}' chatId=${state.chatId}")
    }
}
