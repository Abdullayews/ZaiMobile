package zai.mobile

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.util.Log
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Date

/**
 * Self-diagnostika: tutulmayan crash-i fayla yazır, növbəti açılışda dialoqda
 * göstərir. "Proqram açılmır" halında dəqiq texniki səbəb istifadəçidə görünür
 * və bir düymə ilə köçürülüb tərtibatçıya göndərilə bilər.
 */
object CrashLog {

    private const val FILE = "last_crash.txt"

    /** App.onCreate-də bir dəfə çağırılır. */
    fun install(app: Context) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                File(app.filesDir, FILE).writeText(
                    "time: ${Date()}\nthread: ${t.name}\n\n${Log.getStackTraceString(e)}")
            }
            // sistem dialoqu ("default handler") yenə də görünsün
            prev?.uncaughtException(t, e)
        }
    }

    /**
     * Əvvəlki sessiya crash edibsə hesabatı göstər (və faylı sil).
     * Activity.onCreate-da, buildUi-dən SONRA çağırılmalı.
     */
    fun maybeShow(a: Activity) {
        val f = File(a.filesDir, FILE)
        val content = runCatching { f.readText() }.getOrNull() ?: return
        runCatching { f.delete() }
        if (content.isBlank()) return

        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(a, 20f), Ui.dp(a, 10f), Ui.dp(a, 20f), 0)
        }
        box.addView(Ui.text(a,
            "The app closed unexpectedly last time. Technical reason:", 13f, P.TEXT2))
        val txt = TextView(a).apply {
            text = content.take(4000)
            textSize = 11f
            setTextColor(P.TEXT2)
            typeface = Typeface.MONOSPACE
        }
        val scroll = ScrollView(a).apply { addView(txt) }
        box.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 280f)).apply {
            topMargin = Ui.dp(a, 10f)
        })

        AlertDialog.Builder(a)
            .setTitle("Crash report")
            .setView(box)
            .setPositiveButton("Copy") { _, _ ->
                val cm = a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("crash", content))
                Toast.makeText(a, "Copied — send this to the developer",
                    Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }
}
