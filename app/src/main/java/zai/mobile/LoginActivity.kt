package zai.mobile

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Giriş ekranı — chat.z.ai saytının görünüşü ilə (dark):
 * Google / Email / Github düymələri → email + şifrə ilə NATİVE giriş.
 * WebView YOXDUR; "Skip for now" = qonaq tokeni.
 * Captcha fallback: brauzerdə giriş + tokeni köçürüb gəl.
 */
class LoginActivity : Activity() {

    private lateinit var session: Session
    private lateinit var api: ZaiApi
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Görünüşlər
    private lateinit var kök: ScrollView
    private lateinit var sütun: LinearLayout
    private lateinit var welcomeBllok: LinearLayout
    private lateinit var emailBllok: LinearLayout
    private lateinit var emailGiriş: EditText
    private lateinit var şifrəGiriş: EditText
    private lateinit var göstərGizlət: TextView
    private lateinit var xətaMətn: TextView
    private lateinit var daxilOl: TextView
    private var loginBusy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = Session(this)
        api = ZaiApi(session.deviceId)

        buildUi()
        Fullscreen.apply(this)
        Fullscreen.applyInsets(kök, kök, kök)

        // Artıq token varsa birbaşa chat-ə
        if (session.hasAuth) {
            startActivity(Intent(this, ChatActivity::class.java))
            finish()
            return
        }
        CrashLog.maybeShow(this)
        intent.getStringExtra("exit_toast")?.let {
            Toast.makeText(this, it, Toast.LENGTH_LONG).show()
        }
        scope.launch { api.warmUp() }
    }

    // ------------------------------------------------------------ UI qurulması

    private fun buildUi() {
        val d = { v: Float -> Ui.dp(this, v) }
        kök = ScrollView(this).apply {
            setBackgroundColor(P.PAGE)
            isFillViewport = true
        }
        sütun = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(d(28f), d(40f), d(28f), d(24f))
        }

        // ---------- Welcome bloku ----------
        welcomeBllok = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val logo = ImageView(this).apply {
            setImageResource(R.drawable.logo)
            layoutParams = LinearLayout.LayoutParams(d(56f), d(56f)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = d(18f)
            }
        }
        val başlıq = Ui.text(this, "Sign in to Z.ai", 22f, P.TEXT, bold = true).apply {
            gravity = Gravity.CENTER
        }
        val altMətn = Ui.text(this, "Welcome back — use your Z.ai account", 13f, P.TEXT2).apply {
            gravity = Gravity.CENTER
            setPadding(0, d(4f), 0, d(24f))
        }

        fun sosialDüymə(etiket: String, ikon: Int?, vurğu: Boolean = false): FrameLayout {
            val yerdəyişmə = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            if (ikon != null) {
                val iv = ImageView(this).apply { setImageResource(ikon) }
                yerdəyişmə.addView(iv, LinearLayout.LayoutParams(d(18f), d(18f)).apply {
                    rightMargin = d(10f)
                })
            } else {
                val işarə = Ui.text(this, if (vurğu) "✉" else "", 15f, P.TEXT2)
                yerdəyişmə.addView(işarə, LinearLayout.LayoutParams(
                    if (vurğu) d(18f) else 0, d(18f)).apply {
                    rightMargin = d(10f)
                })
            }
            yerdəyişmə.addView(Ui.text(this, etiket, 14f, P.TEXT))
            return FrameLayout(this).apply {
                background = Ui.roundBg(P.PAGE, d(8f), P.BORDER, d(1f))
                isClickable = true
                setOnClickListener { emailFormunuGöstər() }
                addView(yerdəyişmə, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, d(46f)
                ).apply { bottomMargin = d(10f) }
            }
        }

        val googleBtn = sosialDüymə("Continue with Google", R.drawable.google)
        val emailBtn = sosialDüymə("Continue with Email", null)
        val githubBtn = sosialDüymə("Continue with GitHub", R.drawable.github)

        // "or" ayırıcısı
        val ayırıcı = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, d(6f), 0, d(6f))
        }
        fun xətt(): View = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(d(90f), d(1f)).apply { gravity = Gravity.CENTER_VERTICAL }
            setBackgroundColor(P.BORDER)
        }
        ayırıcı.addView(xətt())
        ayırıcı.addView(Ui.text(this, "  or  ", 12f, P.TEXT3).apply {
            gravity = Gravity.CENTER_VERTICAL
        })
        ayırıcı.addView(xətt())

        val skipBtn = TextView(this).apply {
            text = "Skip for now"
            textSize = 14f
            setTextColor(P.TEXT2)
            gravity = Gravity.CENTER
            background = Ui.roundBg(P.PAGE, d(8f), P.BORDER, d(1f))
            minimumHeight = d(46f)
            setPadding(d(16f), 0, d(16f), 0)
            setOnClickListener { qonaqOlarakDaxilOl() }
        }
        val qaydalar = Ui.text(
            this,
            "By using Z.ai you agree to our Terms of Service and Privacy Policy",
            10f, P.TEXT3,
        ).apply {
            gravity = Gravity.CENTER
            setPadding(0, d(16f), 0, 0)
            setLineSpacing(d(3f).toFloat(), 1f)
        }

        welcomeBllok.addView(logo)
        welcomeBllok.addView(başlıq)
        welcomeBllok.addView(altMətn)
        welcomeBllok.addView(googleBtn)
        welcomeBllok.addView(emailBtn)
        welcomeBllok.addView(githubBtn)
        welcomeBllok.addView(ayırıcı)
        welcomeBllok.addView(skipBtn)
        welcomeBllok.addView(qaydalar)

        // ---------- Email formu ----------
        emailBllok = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val geriBtn = TextView(this).apply {
            text = "←  Back"
            textSize = 14f
            setTextColor(P.TEXT2)
            setPadding(0, d(8f), 0, d(18f))
            setOnClickListener { welcomeFormunuGöstər() }
        }
        emailGiriş = daxiletmə("Enter Your Email", false)
        şifrəGiriş = daxiletmə("Enter Your Password", true)
        göstərGizlət = TextView(this).apply {
            text = "Show password"
            textSize = 12f
            setTextColor(P.TEXT2)
            setPadding(0, d(6f), 0, d(10f))
            setOnClickListener {
                val görünür = şifrəGiriş.transformationMethod != null
                şifrəGiriş.transformationMethod =
                    if (görünür) null else android.text.method.PasswordTransformationMethod.getInstance()
                this.text = if (görünür) "Hide password" else "Show password"
            }
        }
        daxilOl = TextView(this).apply {
            text = "Sign in"
            textSize = 15f
            setTextColor(P.INK)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = Ui.roundBg(P.TEXT, d(6f))
            minimumHeight = d(46f)
            setPadding(d(16f), 0, d(16f), 0)
            setOnClickListener { daxilOlmayıDene() }
        }
        xətaMətn = TextView(this).apply {
            textSize = 13f
            setTextColor(P.DANGER)
            setPadding(0, d(12f), 0, 0)
            visibility = View.GONE
        }
        emailBllok.addView(geriBtn)
        emailBllok.addView(emailGiriş)
        emailBllok.addView(şifrəGiriş)
        emailBllok.addView(göstərGizlət)
        emailBllok.addView(daxilOl)
        emailBllok.addView(xətaMətn)

        sütun.addView(welcomeBllok)
        sütun.addView(emailBllok)
        kök.addView(sütun)
        setContentView(kök)
    }

    private fun daxiletmə(ipucu: String, şifrədir: Boolean): EditText = EditText(this).apply {
        hint = ipucu
        setHintTextColor(P.PLACEHOLDER)
        setTextColor(P.TEXT)
        textSize = 15f
        background = Ui.roundBg(P.PAGE, d(8f), P.BORDER, d(1f))
        setPadding(d(14f), d(14f), d(14f), d(14f))
        minimumHeight = d(46f)
        imeOptions = if (şifrədir) EditorInfo.IME_ACTION_DONE else EditorInfo.IME_ACTION_NEXT
        if (şifrədir) {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) { daxilOlmayıDene(); true } else false
            }
        } else {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = d(10f) }
    }

    private fun d(v: Float): Int = Ui.dp(this, v)

    // ------------------------------------------------------------ axınlar

    private fun welcomeFormunuGöstər() {
        welcomeBllok.visibility = View.VISIBLE
        emailBllok.visibility = View.GONE
        xətaMətn.visibility = View.GONE
    }

    private fun emailFormunuGöstər() {
        welcomeBllok.visibility = View.GONE
        emailBllok.visibility = View.VISIBLE
    }

    private fun meşğulEt(b: Boolean) {
        loginBusy = b
        daxilOl.text = if (b) "Please wait…" else "Sign in"
        daxilOl.isEnabled = !b
        daxilOl.alpha = if (b) 0.6f else 1f
    }

    private fun xətaGöstər(msg: String) {
        xətaMətn.text = msg
        xətaMətn.visibility = View.VISIBLE
        meşğulEt(false)
    }

    private fun chatəKeç() {
        startActivity(Intent(this, ChatActivity::class.java))
        finish()
    }

    private fun daxilOlmayıDene() {
        if (loginBusy) return
        val email = emailGiriş.text.toString().trim()
        val şifrə = şifrəGiriş.text.toString()
        if (email.isEmpty() || !email.contains('@')) {
            xətaGöstər("Please enter a valid email address"); return
        }
        if (şifrə.isEmpty()) { xətaGöstər("Please enter your password"); return }
        xətaMətn.visibility = View.GONE
        meşğulEt(true)
        scope.launch {
            try {
                val auth = withContext(Dispatchers.IO) { api.signin(email, şifrə) }
                session.saveAuth(auth)
                chatəKeç()
            } catch (e: ApiException) {
                when (e.kind) {
                    ApiException.Kind.CAPTCHA -> { meşğulEt(false); captchaDialoquGöstər() }
                    else -> xətaGöstər(e.message ?: "Sign-in failed")
                }
            } catch (e: Exception) {
                xətaGöstər(e.message ?: "Network error")
            }
        }
    }

    private fun qonaqOlarakDaxilOl() {
        if (loginBusy) return
        meşğulEt(true)
        xətaMətn.visibility = View.GONE
        scope.launch {
            try {
                val auth = withContext(Dispatchers.IO) { api.guestAuth() }
                session.saveAuth(auth)
                chatəKeç()
            } catch (e: Exception) {
                xətaGöstər(e.message ?: "Guest sign-in failed")
            }
        }
    }

    // ------------------------------------------------------------ captcha fallback

    private fun captchaDialoquGöstər(avtoToken: String? = null) {
        val tokenGiriş = EditText(this).apply {
            hint = "Paste your token here (eyJ…)"
            setHintTextColor(P.PLACEHOLDER)
            setTextColor(P.TEXT)
            textSize = 13f
            setSingleLine(false)
            minLines = 3
            maxLines = 6
            setText(avtoToken)
            setPadding(d(12f), d(10f), d(12f), d(10f))
            background = Ui.roundBg(P.PAGE, d(8f), P.BORDER, d(1f))
        }
        val mesaj = Ui.text(
            this,
            "Z.ai requested a captcha check.\n\n1. Open chat.z.ai in your browser and sign in once\n" +
                "2. Copy your token (it starts with \"eyJ\")\n3. Paste it below",
            13f, P.TEXT2,
        )
        val qutu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(20f), d(10f), d(20f), d(6f))
            addView(mesaj)
            addView(tokenGiriş, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = d(12f) })
        }
        var dialoq: AlertDialog? = null
        dialoq = AlertDialog.Builder(this)
            .setTitle("Captcha required")
            .setView(qutu)
            .setPositiveButton("Sign in with token") { _, _ ->
                tokenİləDaxilOl(tokenGiriş.text.toString().trim())
            }
            .setNeutralButton("Open chat.z.ai") { _, _ ->
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://chat.z.ai/")))
                }
                dialoq?.dismiss()
                captchaDialoquGöstər()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun tokenİləDaxilOl(token: String) {
        if (token.length < 20) { xətaGöstər("Token looks too short"); return }
        meşğulEt(true)
        scope.launch {
            try {
                val auth = withContext(Dispatchers.IO) { api.verifyToken(token) }
                session.saveAuth(auth)
                chatəKeç()
            } catch (e: Exception) {
                xətaGöstər("Token invalid: ${e.message}")
            }
        }
    }

    /** Clipboard-da JWT aşkarlanarsa (eynənləşdirilmiş razılıqla) təklif et. */
    override fun onResume() {
        super.onResume()
        if (session.hasAuth || loginBusy) return
        runCatching {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val item = cm.primaryClip?.getItemAt(0)?.text?.toString()?.trim() ?: return
            if (item.startsWith("eyJ") && item.length > 50 && item.contains('.')) {
                captchaDialoquGöstər(item)
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        Fullscreen.onFocus(this, hasFocus)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
