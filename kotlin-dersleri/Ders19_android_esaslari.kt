// ============================================================================
// DƏRS 19 — ANDROID ƏSASLARI: Activity, View, Adapter, SharedPreferences
// ----------------------------------------------------------------------------
// Bu dərs JVM-də İŞƏ SALINMIR (Android runtime lazımdır) — o, android.jar-ə
// qarşı KOMPİLİYASIYA OLUNARAQ yoxlanılır:
//   kotlinc Ders19_android_esaslari.kt -cp $ANDROID_HOME/platforms/android-34/android.jar
// ZaiMobile məhz bu anlayışlar üzərində qurulacaq.
// WebView YOXDUR: UI tamamilə native View-lardır.
// ============================================================================

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

// ---------- 1. ACTIVITY HƏYAT DÖVRÜ (lifecycle) ----------
// onCreate → onStart → onResume → [istifadə] → onPause → onStop → onDestroy
// onCreate: bir dəfə — UI qurulur (setContentView)
// onResume: istifadəçi qarşısında — axınları BAŞLAT
// onPause: qismən örtülüb — həssas işləri dayandır
// onDestroy: məhv — korutin scope-u LƏĞV ET
class Dərs19Activity : Activity() {

    // lateinit — Android-də lifecycle-ə görə onCreate-də doldurulacaq (Dərs 04!)
    lateinit var kök: LinearLayout
    lateinit var mesajSiyahısı: ListView
    lateinit var daxiletmə: EditText

    // ---------- 2. STATE — onCreate-da Bundle ilə qaytarılır ----------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("Ders19", "onCreate: savedInstanceState=${savedInstanceState != null}")

        // setContentView(R.layout.activity_main) — XML inflate alternativi.
        // Biz PROQRAMATİK qururuq (dərs üçün); ZaiMobile-da XML layout olacaq:
        kök = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#161616"))
            setPadding(28, 28, 28, 28)                       // dp hesablama: Ders20-də
        }

        val başlıq = TextView(this).apply {
            text = "Native Kotlin UI — WebView YOX"
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        }

        // ---------- 3. DAXİLETMƏ + DÜYMƏ + HADİSƏ ----------
        daxiletmə = EditText(this).apply {
            hint = "Mesaj yaz..."
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            imeOptions = EditorInfo.IME_ACTION_SEND          // klaviaturada "göndər"
        }
        val göndər = Button(this).apply {
            text = "Göndər"
            setOnClickListener { v ->                        // lambda = OnClickListener SAM!
                Toast.makeText(context, "Toxundun: ${(v as Button).text}", Toast.LENGTH_SHORT).show()
            }
        }

        // ---------- 4. LISTVIEW + BASEADAPTER (mesaj siyahısı) ----------
        mesajSiyahısı = ListView(this).apply {
            adapter = MesajAdapter(listOf("Salam", "Necəsən?", "Yaxşı"))
        }

        kök.addView(başlıq)
        kök.addView(daxiletmə, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        kök.addView(göndər, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.END                              // SAĞA yasla
        })
        kök.addView(mesajSiyahısı, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))     // weight=1: qalan yeri tut
        setContentView(kök)
    }

    override fun onStart() { super.onStart(); Log.d("Ders19", "onStart") }
    override fun onResume() { super.onResume(); Log.d("Ders19", "onResume — UI aktivdir") }
    override fun onPause() { super.onPause(); Log.d("Ders19", "onPause — axınları dayandır!") }
    override fun onStop() { super.onStop(); Log.d("Ders19", "onStop") }
    override fun onDestroy() { super.onDestroy(); Log.d("Ders19", "onDestroy — resursları burax") }
}

// ---------- 5. ADAPTER — data → sətir View ----------
class MesajAdapter(private val mesajlar: List<String>) : BaseAdapter() {
    override fun getCount() = mesajlar.size
    override fun getItem(position: Int) = mesajlar[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        // convertView-in yenidən istifadəsi — performansın əsas qaydası!
        val tv = (convertView as? TextView) ?: TextView(parent.context).apply {
            textSize = 16f
            setPadding(16, 24, 16, 24)
        }
        tv.text = mesajlar[position]
        tv.setTextColor(if (position % 2 == 0) Color.WHITE else Color.LTGRAY)
        return tv
    }
}

// ---------- 6. SHARED PREFERENCES — kiçik daimi state (token, ayarlar) ----------
// ZaiMobile burada token/user_id/model seçimini saxlayacaq.
fun saxla(ctx: Context, token: String, istifadəçi: String) {
    val prefs: SharedPreferences = ctx.getSharedPreferences("zai_state", Context.MODE_PRIVATE)
    prefs.edit()                            // Editor — dəyişikliklər toplanır
        .putString("token", token)
        .putString("user", istifadəçi)
        .putBoolean("dark", true)
        .putInt("model_index", 0)
        .apply()                            // apply = asinxron (commit = sinxron)
}

fun oxu(ctx: Context): String? =
    ctx.getSharedPreferences("zai_state", Context.MODE_PRIVATE).getString("token", null)

// ---------- 7. UI THREAD — Android-də TƏK qayda ----------
// Şəbəkə = ASİNXRON thread; UI = YALNIZ main thread-dən!
fun şəbəkədənGələnNəticəniGöstər(activity: Activity, nəticə: String) {
    // variant A (ən sadə):
    activity.runOnUiThread { Toast.makeText(activity, nəticə, Toast.LENGTH_SHORT).show() }
    // variant B (post):
    android.os.Handler(android.os.Looper.getMainLooper()).post {
        Log.d("Ders19", "main thread-də: $nəticə")
    }
}

// ---------- 8. DİALOQ ----------
fun təsdiqSoruş(activity: Activity, başlıq: String, onBəli: () -> Unit) {
    AlertDialog.Builder(activity)
        .setTitle(başlıq)
        .setMessage("Əminsiniz?")
        .setPositiveButton("Bəli") { _, _ -> onBəli() }       // SAM-lambda + destructuring!
        .setNegativeButton("Xeyr", null)                     // null = heç nə etmə
        .show()
}

// main YOXDUR — bu fayl Android-də işləyir; JVM-də yalnız kompilyasiya olunur.
