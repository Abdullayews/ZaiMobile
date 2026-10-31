package zai.mobile.ui

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import zai.mobile.App
import zai.mobile.R
import zai.mobile.protocol.AuthApi
import zai.mobile.protocol.AuthResult
import zai.mobile.protocol.ZaiException
import zai.mobile.state.Session

/**
 * Giriş — email + şifrə (POST /api/v1/auths/signin).
 * Captcha tələbi gəlsə: brauzerə aç + token köçür (clipboard aşkarlama,
 * istifadəçinin razılığı ilə) və ya tokeni əllə yapışdır.
 * WebView YOXDUR — yalnız sistem brauzeri (ACTION_VIEW).
 */
class LoginActivity : Activity() {

    companion object {
        const val RESULT_SIGNED_IN = 10
        const val SITE_AUTH = "https://chat.z.ai/auth"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val watchHandler = Handler(Looper.getMainLooper())

    private lateinit var session: Session
    private lateinit var authApi: AuthApi

    private lateinit var stepEmail: LinearLayout
    private lateinit var stepToken: LinearLayout
    private lateinit var statusEmail: TextView
    private lateinit var emailField: EditText
    private lateinit var pwField: EditText
    private lateinit var tokenField: EditText
    private lateinit var statusToken: TextView
    private lateinit var useDetected: TextView

    private var pwShown = false
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        val http = App.http(this)
        session = Session(this)
        authApi = AuthApi(http)
        watchClipboard()
    }

    private fun buildUi(): View {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(20f), dp(28f), dp(20f), dp(20f))

        val title = TextView(this)
        title.text = getString(R.string.login_title)
        title.textSize = sp(24f)
        title.setTextColor(color(R.color.text))
        title.typeface = Typeface.create(serif(), Typeface.BOLD)
        title.setPadding(0, 0, 0, dp(18f))
        root.addView(title)

        // ---- Addım 1: email + şifrə ----
        stepEmail = LinearLayout(this)
        stepEmail.orientation = LinearLayout.VERTICAL
        emailField = field(getString(R.string.email))
        emailField.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        pwField = field(getString(R.string.password), password = true)
        val pwRow = LinearLayout(this)
        pwRow.orientation = LinearLayout.HORIZONTAL
        pwRow.gravity = Gravity.CENTER_VERTICAL
        val pwToggle = TextView(this)
        pwToggle.text = getString(R.string.show_pw)
        pwToggle.textSize = sp(13f)
        pwToggle.setTextColor(color(R.color.accent))
        pwToggle.setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
        pwToggle.setOnClickListener {
            pwShown = !pwShown
            pwField.inputType = if (pwShown)
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            else
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            pwToggle.text = getString(if (pwShown) R.string.hide_pw else R.string.show_pw)
            pwField.setSelection(pwField.text.length)
        }
        pwRow.addView(
            pwField,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        pwRow.addView(pwToggle)
        stepEmail.addView(
            emailField,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12f) },
        )
        stepEmail.addView(
            pwRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        statusEmail = label("", 13f, R.color.danger)
        statusEmail.setPadding(0, dp(10f), 0, dp(10f))
        stepEmail.addView(statusEmail)
        val go = textButton(getString(R.string.continue_label)) { submitEmail() }
        stepEmail.addView(
            go,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val toToken = link(getString(R.string.token_hint))
        toToken.setPadding(0, dp(14f), 0, 0)
        toToken.setOnClickListener { showStep(stepToken) }
        stepEmail.addView(toToken)
        root.addView(stepEmail)

        // ---- Addım 2: token ----
        stepToken = LinearLayout(this)
        stepToken.orientation = LinearLayout.VERTICAL
        stepToken.visibility = View.GONE
        val how = label(getString(R.string.token_how), 12.5f, R.color.text3)
        how.setPadding(0, 0, 0, dp(10f))
        stepToken.addView(how)
        val open = textButton(getString(R.string.open_site)) {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SITE_AUTH)))
            }
        }
        stepToken.addView(
            open,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        tokenField = field(getString(R.string.token_hint), singleLine = false)
        tokenField.minLines = 2
        stepToken.addView(
            tokenField,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(12f)
                bottomMargin = dp(6f)
            },
        )
        statusToken = label("", 13f, R.color.danger)
        statusToken.setPadding(0, dp(6f), 0, dp(6f))
        stepToken.addView(statusToken)
        val apply = textButton(getString(R.string.apply)) { submitToken() }
        stepToken.addView(
            apply,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        useDetected = link(getString(R.string.use_detected))
        useDetected.visibility = View.GONE
        useDetected.setPadding(0, dp(12f), 0, 0)
        useDetected.setOnClickListener {
            tokenField.setText(currentDetectedToken.orEmpty())
            submitToken()
        }
        stepToken.addView(useDetected)
        val back = link(getString(R.string.back_to_email))
        back.setPadding(0, dp(16f), 0, 0)
        back.setOnClickListener { showStep(stepEmail) }
        stepToken.addView(back)
        root.addView(stepToken)

        val scroll = android.widget.ScrollView(this)
        scroll.addView(root)
        return scroll
    }

    private fun showStep(step: View) {
        stepEmail.visibility = if (step === stepEmail) View.VISIBLE else View.GONE
        stepToken.visibility = if (step === stepToken) View.VISIBLE else View.GONE
        statusEmail.text = ""
        statusToken.text = ""
    }

    private fun submitEmail() {
        if (busy) return
        val email = emailField.text.toString().trim()
        val pw = pwField.text.toString()
        if (email.isBlank() || pw.isBlank()) {
            statusEmail.text = getString(R.string.login_failed)
            return
        }
        statusEmail.text = ""
        setBusy(true)
        scope.launch {
            val result = withContext(App.io()) { authApi.signin(email, pw) }
            setBusy(false)
            when (result) {
                is AuthResult.Ok -> applyAuth(result.info)
                is AuthResult.Captcha -> {
                    showStep(stepToken)
                    statusToken.text = getString(R.string.login_captcha_title)
                }
                is AuthResult.Failed -> {
                    statusEmail.text = result.error.message.orEmpty()
                        .ifBlank { getString(R.string.wrong_credentials) }
                }
            }
        }
    }

    private fun submitToken() {
        if (busy) return
        val t = tokenField.text.toString().trim()
        if (!t.startsWith("eyJ") || t.length < 60) {
            statusToken.text = getString(R.string.token_hint)
            return
        }
        statusToken.text = ""
        setBusy(true)
        scope.launch {
            try {
                val info = withContext(App.io()) { authApi.applyToken(t) }
                setBusy(false)
                applyAuth(info)
            } catch (e: ZaiException) {
                setBusy(false)
                statusToken.text = e.message
            }
        }
    }

    private fun applyAuth(info: zai.mobile.protocol.AuthInfo) {
        session.saveAuth(info)
        setResult(RESULT_SIGNED_IN)
        Toast.makeText(this, info.userName.ifBlank { "z.ai" }, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun setBusy(b: Boolean) {
        busy = b
        window.findViewById<ViewGroup>(android.R.id.content)?.alpha = if (b) 0.6f else 1f
    }

    // ---- clipboard aşkarlama (istifadəçi razılığı = düymə) ----

    private var currentDetectedToken: String? = null

    private fun watchClipboard() {
        watchHandler.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip
            val txt = clip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
            currentDetectedToken = if (txt != null && txt.startsWith("eyJ") && txt.length > 60) txt else null
            if (currentDetectedToken != null && stepToken.visibility == View.VISIBLE && statusToken.text.isBlank()) {
                useDetected.visibility = View.VISIBLE
            }
            watchClipboard()
        }, 3000)
    }

    override fun onDestroy() {
        watchHandler.removeCallbacksAndMessages(null)
        scope.cancel()
        super.onDestroy()
    }
}
