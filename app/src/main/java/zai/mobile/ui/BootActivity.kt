package zai.mobile.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import zai.mobile.App
import zai.mobile.R
import zai.mobile.protocol.AuthApi
import zai.mobile.state.Session

/**
 * Açılış: splash → WAF warm-up → token yoxlama / qonaq → ChatActivity.
 * Şəbəkə xətasında düzünü göstərir + Retry. Saxta data YOXDUR.
 */
class BootActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var center: LinearLayout
    private lateinit var errorCol: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 31) runCatching { window.setHideOverlayWindows(true) }
        setContentView(buildUi())
        showCrashReportIfAny()
        startBoot()
    }

    private fun buildUi(): View {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER
        root.setPadding(dp(20f), dp(20f), dp(20f), dp(20f))

        center = LinearLayout(this)
        center.orientation = LinearLayout.VERTICAL
        center.gravity = Gravity.CENTER
        val word = TextView(this)
        word.text = getString(R.string.app_name)
        word.textSize = sp(34f)
        word.setTextColor(color(R.color.text))
        word.typeface = Typeface.create(serif(), Typeface.BOLD)
        word.gravity = Gravity.CENTER
        val prog = ProgressBar(this)
        prog.indeterminateTintList = android.content.res.ColorStateList.valueOf(color(R.color.accent))
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(28f)
        lp.bottomMargin = dp(10f)
        prog.layoutParams = lp
        val load = label(getString(R.string.boot_loading), 13f, R.color.text3)
        load.gravity = Gravity.CENTER
        center.addView(word)
        center.addView(prog)
        center.addView(load)
        root.addView(
            center,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )

        errorCol = LinearLayout(this)
        errorCol.orientation = LinearLayout.VERTICAL
        errorCol.gravity = Gravity.CENTER
        errorCol.visibility = View.GONE
        val errTitle = label(getString(R.string.err_title), 17f, R.color.text)
        errTitle.gravity = Gravity.CENTER
        errTitle.setTypeface(errTitle.typeface, Typeface.BOLD)
        val errBody = label(getString(R.string.err_body), 13.5f, R.color.text2)
        errBody.gravity = Gravity.CENTER
        val retry = textButton(getString(R.string.retry), filled = true) {
            startBoot()
        }
        retry.gravity = Gravity.CENTER
        val rp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        rp.topMargin = dp(16f)
        retry.layoutParams = rp
        errorCol.addView(errTitle)
        errorCol.addView(errBody)
        errorCol.addView(retry)
        root.addView(
            errorCol,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        return root
    }

    private fun startBoot() {
        center.visibility = View.VISIBLE
        errorCol.visibility = View.GONE
        val http = App.http(this)
        val session = Session(this)
        val authApi = AuthApi(http)
        scope.launch {
            try {
                val info = withContext(App.io()) {
                    http.warmUp()
                    if (session.hasAuth) {
                        val verified = authApi.verify(session.token)
                        if (verified != null) {
                            session.saveAuth(verified)
                            verified
                        } else {
                            val g = authApi.guest()
                            session.saveAuth(g)
                            g
                        }
                    } else {
                        val g = authApi.guest()
                        session.saveAuth(g)
                        g
                    }
                }
                startActivity(Intent(this@BootActivity, ChatActivity::class.java))
                finish()
            } catch (e: Exception) {
                center.visibility = View.GONE
                errorCol.visibility = View.VISIBLE
            }
        }
    }

    private fun showCrashReportIfAny() {
        val crash = (applicationContext as App).takeLastCrash() ?: return
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        val sv = ScrollView(this)
        val txt = TextView(this)
        txt.text = crash
        txt.typeface = android.graphics.Typeface.MONOSPACE
        txt.textSize = sp(10.5f)
        txt.setTextColor(color(R.color.text2))
        txt.setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
        sv.addView(txt)
        val head = label(getString(R.string.crash_body), 13f, R.color.text3)
        box.addView(head)
        box.addView(
            sv,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(220f),
            ),
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.crash_title))
            .setView(box)
            .setPositiveButton(getString(R.string.crash_copy)) { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("crash", crash))
                Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
