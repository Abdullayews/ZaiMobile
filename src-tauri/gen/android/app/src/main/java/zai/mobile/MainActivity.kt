package zai.mobile

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import java.io.File

/**
 * Z.ai Mobile v5 — native qabırğa.
 *
 *  • __zaiNative JavascriptInterface: xarici keçidlər (ACTION_VIEW),
 *    fayl endirmə (MediaStore / app-Downloads), toast.
 *  • Back düyməsi: veb tarixçəsində geri; tarixçə bitibsə app fonə keçir
 *    (brauzer məntiqi ilə eyni).
 */
class MainActivity : TauriActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        installBridge()
    }

    /** Görünüş ağacında WebView-i tapır (wry yaradır, bizə referans yoxdur). */
    private fun findWebView(root: View?): WebView? {
        if (root == null) return null
        if (root is WebView) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findWebView(root.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun installBridge() {
        try {
            val wv = findWebView(window.decorView) ?: return
            wv.addJavascriptInterface(ZaiNativeBridge(this), "__zaiNative")
        } catch (_: Throwable) {
        }
    }

    /** Back: sayt tarixçəsində geri, yoxsa app-i fonə salırıq (öldürmürük). */
    override fun onBackPressed() {
        val wv = try { findWebView(window.decorView) } catch (_: Throwable) { null }
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
            return
        }
        moveTaskToBack(true)
    }
}

/** Səhifəyə açılan native API (window.__zaiNative). */
class ZaiNativeBridge(private val activity: MainActivity) {

    @JavascriptInterface
    fun openExternal(url: String) {
        try {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            activity.startActivity(i)
        } catch (_: Exception) {
            activity.runOnUiThread {
                Toast.makeText(activity, "No app can open this link", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Faylı cihazın Downloads qovluğuna yazır.
     * @return yadda saxlanılan yol, yaxud xətada null.
     */
    @JavascriptInterface
    fun saveDownload(name: String, mime: String, b64: String): String? {
        return try {
            val safeName = sanitize(name.ifBlank { "zai-file" })
            val safeMime = mime.ifBlank { "application/octet-stream" }
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            if (Build.VERSION.SDK_INT >= 29) {
                val cv = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                    put(MediaStore.Downloads.MIME_TYPE, safeMime)
                }
                val resolver = activity.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                if (uri == null) {
                    toastDone(null, safeName); return null
                }
                val ok = resolver.openOutputStream(uri)?.use { it.write(bytes); true } ?: false
                if (ok) toastDone(uri.toString(), safeName) else { toastDone(null, safeName); null }
            } else {
                val dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                if (dir == null) { toastDone(null, safeName); return null }
                val f = File(dir, safeName)
                f.writeBytes(bytes)
                toastDone(f.absolutePath, safeName)
            }
        } catch (_: Exception) {
            toastDone(null, "?")
            null
        }
    }

    @JavascriptInterface
    fun toast(msg: String) {
        activity.runOnUiThread {
            Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun toastDone(path: String?, name: String): String? {
        activity.runOnUiThread {
            Toast.makeText(
                activity,
                if (path != null) "Saved to Downloads: $name" else "Could not save file",
                Toast.LENGTH_SHORT
            ).show()
        }
        return path
    }

    private fun sanitize(n: String): String =
        n.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(120).ifBlank { "zai-file" }
}
