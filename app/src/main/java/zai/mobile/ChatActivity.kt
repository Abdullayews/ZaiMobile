package zai.mobile

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Gözləmədə olan əlavə (hələ serverə yüklənməyib). */
data class PendingFile(val name: String, val mime: String, val bytes: ByteArray)

/**
 * Əsas chat ekranı — 100% native UI (WebView YOXDUR):
 * nav (48dp) + mesaj siyahısı + welcome kartları + kompozitor + footer,
 * açılan sidebar (söhbətlər), model/effort seçimi, immersive fullscreen.
 */
class ChatActivity : Activity() {

    private lateinit var session: Session
    private lateinit var api: ZaiApi
    private lateinit var engine: ChatEngine
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val state = ChatState()

    // Data
    private val messages = mutableListOf<ChatMsg>()
    private var conversations = listOf<ConvSummary>()
    private var models = listOf<ModelInfo>()
    private var streaming = false
    private var streamJob: kotlinx.coroutines.Job? = null
    private var pendingFile: PendingFile? = null
    private var uploadedFile: UploadedFile? = null
    private var autoScroll = true

    // Görünüşlər
    private lateinit var kök: FrameLayout
    private lateinit var anaSütun: LinearLayout
    private lateinit var navBar: LinearLayout
    private lateinit var navBaşlıq: TextView
    private lateinit var navPill: TextView
    private lateinit var mesajListesi: ListView
    private lateinit var welcome: LinearLayout
    private lateinit var statusLine: TextView
    private lateinit var daxiletmə: EditText
    private lateinit var göndərBtn: TextView
    private lateinit var modelChip: TextView
    private lateinit var attachChip: TextView
    private lateinit var footer: LinearLayout
    private lateinit var sidebar: LinearLayout
    private lateinit var sidebarAlt: TextView
    private lateinit var scrim: View
    private lateinit var convListesi: ListView
    private lateinit var mesajAdapter: MesajAdapter
    private lateinit var convAdapter: ConvAdapter

    private val renderHandler = Handler(Looper.getMainLooper())
    private var lastRender = 0L
    private val forceRender = Runnable {
        lastRender = SystemClock.uptimeMillis()
        mesajAdapter.notifyDataSetChanged()
        avtoSürüşdür()
    }

    // ------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = Session(this)
        api = ZaiApi(session.deviceId)
        engine = ChatEngine(api, session)

        if (!session.hasAuth) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        buildUi()
        Fullscreen.apply(this)
        Fullscreen.applyInsets(kök, navBar, footer)
        Fullscreen.applyInsets(sidebar, sidebar, sidebar)
        CrashLog.maybeShow(this)

        mesajAdapter = MesajAdapter()
        mesajListesi.adapter = mesajAdapter
        convAdapter = ConvAdapter()
        convListesi.adapter = convAdapter

        updateEmptyState()
        navPill.text = if (session.isGuest) "Sign in" else session.userName
        sidebarAlt.text = if (session.isGuest) "Guest" else session.userName
        navBaşlıq.text = state.chatTitle

        // token yoxlaması + modellər arxa planda
        scope.launch {
            try {
                val auth = withContext(Dispatchers.IO) { api.verifyToken(session.token) }
                session.saveAuth(auth)
                navPill.text = if (session.isGuest) "Sign in" else session.userName
                sidebarAlt.text = if (session.isGuest) "Guest" else session.userName
            } catch (e: ApiException) {
                if (e.kind == ApiException.Kind.UNAUTHORIZED) çıxışEt("Signed out — session expired")
            } catch (_: Exception) {
            }
        }
        scope.launch { modelləriYüklə() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        Fullscreen.onFocus(this, hasFocus)
    }

    override fun onDestroy() {
        super.onDestroy()
        renderHandler.removeCallbacksAndMessages(null)
        scope.cancel()
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (sidebar.translationX == 0f) sidebarBağla() else super.onBackPressed()
    }

    private fun d(v: Float): Int = Ui.dp(this, v)

    // ------------------------------------------------------------ UI qurulması

    @SuppressLint("ClickableViewAccessibility")
    private fun buildUi() {
        kök = FrameLayout(this)
        anaSütun = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(P.PAGE)
        }

        // ---------- Nav (48dp) ----------
        navBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(d(12f), 0, d(12f), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, d(48f))
        }
        val menyuBtn = Ui.text(this, "☰", 18f, P.TEXT2).apply {
            setPadding(d(10f), d(8f), d(10f), d(8f))
            setOnClickListener { sidebarAç() }
        }
        navBaşlıq = Ui.text(this, "New Chat", 16f, P.TEXT).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        navPill = TextView(this).apply {
            textSize = 14f
            setTextColor(P.TEXT)
            background = Ui.roundBg(P.BG, d(6f))
            setPadding(d(14f), d(7f), d(14f), d(7f))
            setOnClickListener {
                if (session.isGuest) {
                    // qonaq → login ekranına (qayıdanda təzə token götürər)
                    session.clearAuth()
                    startActivity(Intent(this@ChatActivity, LoginActivity::class.java))
                    finish()
                } else {
                    hesabDialoqu()
                }
            }
        }
        navBar.addView(menyuBtn)
        navBar.addView(navBaşlıq, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { marginStart = d(4f); marginEnd = d(8f) })
        navBar.addView(navPill)

        // ---------- Məzmun ----------
        val məzmun = FrameLayout(this)

        mesajListesi = ListView(this).apply {
            divider = null
            dividerHeight = 0
            setPadding(d(20f), d(8f), d(20f), d(12f))
            clipToPadding = false
            setStackFromBottom(true)
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScroll(v: AbsListView?, first: Int, visible: Int, total: Int) {
                    if (total == 0) return
                    autoScroll = first + visible >= total - 1
                }
                override fun onScrollStateChanged(v: AbsListView?, s: Int) {}
            })
        }

        welcome = buildWelcome()
        məzmun.addView(mesajListesi, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        məzmun.addView(welcome, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // ---------- Status sətri (faza) ----------
        statusLine = Ui.text(this, "", 12f, P.TEXT2).apply {
            setPadding(d(24f), d(6f), d(24f), d(2f))
            visibility = View.GONE
        }

        // ---------- Kompozitor ----------
        val kompozitor = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(P.BG)
            setPadding(d(12f), d(12f), d(12f), d(12f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        attachChip = TextView(this).apply {
            textSize = 12f
            setTextColor(P.TEXT2)
            background = Ui.roundBg(P.PAGE, d(6f), P.BORDER, 1)
            setPadding(d(10f), d(6f), d(10f), d(6f))
            visibility = View.GONE
            setOnClickListener { əlavəniSil() }
        }

        daxiletmə = EditText(this).apply {
            hint = "Send a Message"
            setHintTextColor(P.PLACEHOLDER)
            setTextColor(P.TEXT)
            textSize = 16f
            setBackgroundColor(0x00000000)
            minLines = 1
            maxLines = 6
            setPadding(d(6f), d(14f), d(6f), d(6f))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { göndərGörünüşünüYenilə() }
            })
        }
        val mətnQutu = FrameLayout(this).apply {
            background = Ui.roundBg(P.PAGE, d(24f))
            addView(daxiletmə, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            minimumHeight = d(52f)
        }

        val altSıra = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, d(6f), 0, 0)
        }
        val plusBtn = Ui.text(this, "＋", 22f, P.TEXT2).apply {
            setPadding(d(6f), 0, d(10f), d(2f))
            setOnClickListener { faylSeç() }
        }
        modelChip = Ui.text(this, "GLM-5.3-Flash ▾", 16f, P.TEXT2).apply {
            setPadding(d(4f), d(6f), d(4f), d(6f))
            setOnClickListener { modelDialoqu() }
        }
        göndərBtn = TextView(this).apply {
            text = "↑"
            textSize = 16f
            gravity = Gravity.CENTER
            background = Ui.roundBg(P.DISABLED, d(15f))
            setTextColor(P.TEXT2)
            setOnClickListener { göndər() }
            layoutParams = LinearLayout.LayoutParams(d(30f), d(30f)).apply {
                marginStart = d(8f)
            }
        }
        altSıra.addView(plusBtn)
        altSıra.addView(modelChip)
        altSıra.addView(View(this), LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        altSıra.addView(göndərBtn)

        kompozitor.addView(attachChip, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = d(6f)
        })
        kompozitor.addView(mətnQutu)
        kompozitor.addView(altSıra)

        // ---------- Footer ----------
        footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, d(4f), 0, d(4f))
        }
        footer.addView(Ui.text(this,
            "By using Z.ai you agree to our Terms of Service and Privacy Policy",
            10f, P.TEXT3).apply {
            gravity = Gravity.CENTER
        })

        anaSütun.addView(navBar)
        anaSütun.addView(məzmun, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        anaSütun.addView(statusLine)
        anaSütun.addView(kompozitor)
        anaSütun.addView(footer)
        kök.addView(anaSütun, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // ---------- Sidebar ----------
        scrim = View(this).apply {
            setBackgroundColor(0x99000000.toInt())
            visibility = View.GONE
            setOnClickListener { sidebarBağla() }
        }
        sidebar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(P.PAGE)
            setPadding(0, d(8f), 0, d(12f))
            layoutParams = FrameLayout.LayoutParams(d(300f),
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START)
        }
        val sbBaşlıqSırası = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(d(16f), d(8f), d(12f), d(8f))
        }
        sbBaşlıqSırası.addView(Ui.text(this, "Chats", 16f, P.TEXT, bold = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val yeniBtn = Ui.text(this, "＋  New", 14f, P.TEXT2).apply {
            setPadding(d(10f), d(6f), d(10f), d(6f))
            setOnClickListener { yeniSöhbət() }
        }
        sbBaşlıqSırası.addView(yeniBtn)
        convListesi = ListView(this).apply {
            divider = null
            dividerHeight = 0
            setPadding(d(8f), d(4f), d(8f), d(4f))
        }
        val sbAlt = Ui.text(this, "", 13f, P.TEXT2).apply {
            setPadding(d(16f), d(10f), d(16f), d(6f))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        sidebarAlt = sbAlt
        // qeyd: sidebar footer-i — istifadəçi adı
        sidebar.addView(sbBaşlıqSırası)
        sidebar.addView(convListesi, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        sidebar.addView(sbAlt)
        sidebar.translationX = -d(320f).toFloat()

        kök.addView(scrim, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        kök.addView(sidebar)

        setContentView(kök)
    }

    /** Welcome ekranı: "More than coding..." + 5 təklif kartı (2 sütun, veb 1:1). */
    private fun buildWelcome(): LinearLayout {
        val w = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(d(28f), d(24f), d(28f), d(8f))
        }
        // Rəsmi Z.ai app-i kimi: blok yuxarıdan ~10% məsafədən başlayır.
        // Hündürlük yalnız layoutdan sonra məlumdur; hər layout dəyişikliyində
        // (məs. klaviatura açılanda) yenidən hesablanır.
        w.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (w.height > 0) {
                val hedef = (w.height * 0.10f).toInt()
                if (w.paddingTop != hedef) w.setPadding(d(28f), hedef, d(28f), d(8f))
            }
        }
        w.addView(Ui.text(this, "More than coding...", 24f, P.TEXT, bold = true, serif = true).apply {
            gravity = Gravity.CENTER
        })
        w.addView(Ui.text(this, "Use prompt suggestions or start typing your own.", 13f, P.TEXT2).apply {
            gravity = Gravity.CENTER
            setPadding(0, d(4f), 0, d(16f))
        })

        data class Kart(val res: Int, val etiket: String)
        val kartlar = listOf(
            Kart(R.drawable.card_fullstack, "Full-Stack"),
            Kart(R.drawable.card_writing, "Writing"),
            Kart(R.drawable.card_data, "Data Insight"),
            Kart(R.drawable.card_slides, "PPT"),
            Kart(R.drawable.card_web, "Web Site"),
        )
        kartlar.chunked(2).forEach { cüt ->
            val sıra = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            cüt.forEach { kart ->
                val kartView = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setOnClickListener {
                        daxiletmə.setText("Help me with ${kart.etiket}")
                        daxiletmə.setSelection(daxiletmə.text.length)
                        göndərGörünüşünüYenilə()
                    }
                }
                kartView.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = d(16f)
                    bottomMargin = d(16f)
                }
                val qutu = FrameLayout(this).apply {
                    background = Ui.roundBg(P.BG, d(16f))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, d(140f))
                }
                val şəkil = ImageView(this).apply {
                    setImageResource(kart.res)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, d(115f), Gravity.CENTER_HORIZONTAL).apply {
                        topMargin = d(25f)
                        leftMargin = d(13f)
                        rightMargin = d(13f)
                    }
                }
                qutu.addView(şəkil)
                kartView.addView(qutu)
                kartView.addView(Ui.text(this, kart.etiket, 13f, P.TEXT2).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, d(6f), 0, 0)
                })
                sıra.addView(kartView)
            }
            // tək kart qalıbsa boş xana (veb kimi)
            if (cüt.size == 1) sıra.addView(View(this),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            w.addView(sıra)
        }
        return w
    }

    // ------------------------------------------------------------ adapter-lər

    /** Mesaj sıraları: user = sağda bubble, assistant = tam en, markdown. */
    private inner class MesajAdapter : BaseAdapter() {
        override fun getCount() = messages.size
        override fun getItem(p: Int) = messages[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(p: Int) =
            if (messages[p].role == "user") 0 else 1

        override fun getView(p: Int, convertView: View?, parent: ViewGroup): View {
            val m = messages[p]
            if (m.role == "user") return userSırası(m, convertView, parent)
            return assistantSırası(m, convertView, parent)
        }

        private fun userSırası(m: ChatMsg, convertView: View?, parent: ViewGroup): View {
            val sıra = (convertView as? LinearLayout) ?: LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
                layoutParams = AbsListView.LayoutParams(
                    AbsListView.LayoutParams.MATCH_PARENT, AbsListView.LayoutParams.WRAP_CONTENT)
                setPadding(0, d(6f), 0, d(6f))
            }
            // təkrar istifadədə köhnə bubble-ları təmizlə
            sıra.removeAllViews()
            if (m.attachments.isNotEmpty()) {
                val etiket = m.attachments.joinToString(", ") { it.name.ifEmpty { it.media } }
                sıra.addView(Ui.text(parent.context, "📎 $etiket", 12f, P.TEXT2).apply {
                    setPadding(0, 0, d(4f), d(4f))
                    gravity = Gravity.END
                })
            }
            val bubble = TextView(parent.context).apply {
                text = m.content
                textSize = 15f
                setTextColor(P.TEXT)
                background = Ui.roundBg(P.BG, d(12f))
                setPadding(d(14f), d(10f), d(14f), d(10f))
                maxWidth = (parent.width.takeIf { it > 0 } ?: d(320f)) * 4 / 5
                setOnLongClickListener { mesajıKopyala(m); true }
            }
            sıra.addView(bubble)
            return sıra
        }

        @SuppressLint("ClickableViewAccessibility")
        private fun assistantSırası(m: ChatMsg, convertView: View?, parent: ViewGroup): View {
            val konteyner = (convertView as? LinearLayout) ?: LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = AbsListView.LayoutParams(
                    AbsListView.LayoutParams.MATCH_PARENT, AbsListView.LayoutParams.WRAP_CONTENT)
                setPadding(0, d(6f), 0, d(6f))
            }
            konteyner.removeAllViews()
            val ctx = parent.context
            if (m.error) {
                val xətaView = TextView(ctx).apply {
                    text = m.content
                    textSize = 14f
                    setTextColor(P.DANGER)
                    background = Ui.roundBg(P.DANGER_BG, d(10f))
                    setPadding(d(12f), d(10f), d(12f), d(10f))
                    setOnClickListener { təkrarGöndər() }
                }
                konteyner.addView(xətaView)
                konteyner.addView(Ui.text(ctx, "Tap to retry", 12f, P.TEXT3).apply {
                    setPadding(d(12f), d(4f), 0, 0)
                })
            } else if (m.content.isEmpty() && m.streaming) {
                konteyner.addView(Ui.text(ctx, "…", 15f, P.TEXT2).apply {
                    setPadding(d(2f), d(8f), 0, d(8f))
                })
            } else {
                Markdown.render(ctx, MarkdownParse.parse(m.content), konteyner)
                konteyner.setOnLongClickListener { mesajıKopyala(m); true }
            }
            return konteyner
        }
    }

    /** Söhbət siyahısı sətirləri. */
    private inner class ConvAdapter : BaseAdapter() {
        override fun getCount() = conversations.size
        override fun getItem(p: Int) = conversations[p]
        override fun getItemId(p: Int) = p.toLong()

        override fun getView(p: Int, convertView: View?, parent: ViewGroup): View {
            val c = conversations[p]
            val tv = (convertView as? TextView) ?: TextView(parent.context).apply {
                textSize = 14f
                setTextColor(P.TEXT2)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(d(12f), d(12f), d(12f), d(12f))
                background = Ui.roundBg(0x00000000, d(8f))
                layoutParams = AbsListView.LayoutParams(
                    AbsListView.LayoutParams.MATCH_PARENT, AbsListView.LayoutParams.WRAP_CONTENT)
            }
            tv.text = c.title
            tv.setTextColor(if (c.id == state.chatId) P.TEXT else P.TEXT2)
            tv.setOnClickListener { söhbətiAç(c.id) }
            tv.setOnLongClickListener { söhbətiSil(c); true }
            return tv
        }
    }

    // ------------------------------------------------------------ göndərmə axını

    private fun göndərGörünüşünüYenilə() {
        if (streaming) {
            göndərBtn.text = "■"
            göndərBtn.background = Ui.roundBg(P.TEXT, d(15f))
            göndərBtn.setTextColor(P.INK)
        } else if (daxiletmə.text.isNotBlank()) {
            göndərBtn.text = "↑"
            göndərBtn.background = Ui.roundBg(P.TEXT, d(15f))
            göndərBtn.setTextColor(P.INK)
        } else {
            göndərBtn.text = "↑"
            göndərBtn.background = Ui.roundBg(P.DISABLED, d(15f))
            göndərBtn.setTextColor(P.TEXT2)
        }
    }

    private fun klaviaturanıGizlət() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(daxiletmə.windowToken, 0)
    }

    private fun göndər() {
        if (streaming) { axınıDayandır(); return }
        val mətn = daxiletmə.text.toString().trim()
        if (mətn.isEmpty()) return

        val istifadəçiMesajı = ChatMsg(Signature.genUuid(), "user", mətn)
        val köməkçiMesajı = ChatMsg(Signature.genUuid(), "assistant", "", streaming = true)

        daxiletmə.setText("")
        klaviaturanıGizlət()
        attachChip.visibility = View.GONE

        val bekleyen = pendingFile
        val yüklənmiş = uploadedFile
        pendingFile = null
        uploadedFile = null

        streaming = true
        göndərGörünüşünüYenilə()
        statusLine.text = "Sending…"
        statusLine.visibility = View.VISIBLE

        if (bekleyen != null && yüklənmiş == null) {
            istifadəçiMesajı.attachments = emptyList()
            messages += istifadəçiMesajı
            messages += köməkçiMesajı
            updateEmptyState()
            mesajAdapter.notifyDataSetChanged()
            avtoSürüşdür(true)
            scope.launch {
                statusLine.text = "Uploading ${bekleyen.name}…"
                try {
                    val up = withContext(Dispatchers.IO) {
                        api.uploadFile(session.token, bekleyen.bytes, bekleyen.name, bekleyen.mime)
                    }
                    istifadəçiMesajı.attachments = listOf(up)
                    axınıBaşlat(istifadəçiMesajı, köməkçiMesajı)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    axınXətası(köməkçiMesajı, "Upload failed: ${e.message}")
                }
            }
        } else {
            if (yüklənmiş != null) istifadəçiMesajı.attachments = listOf(yüklənmiş)
            messages += istifadəçiMesajı
            messages += köməkçiMesajı
            updateEmptyState()
            mesajAdapter.notifyDataSetChanged()
            avtoSürüşdür(true)
            axınıBaşlat(istifadəçiMesajı, köməkçiMesajı)
        }
    }

    private fun axınıBaşlat(istifadəçiMesajı: ChatMsg, köməkçiMesajı: ChatMsg) {
        val history = messages
            .filter { it !== köməkçiMesajı && it !== istifadəçiMesajı }
            .map { it.role to it.content }
        val modelId = currentModel().id
        val effort = currentEffort()
        val isFirst = state.chatId.isEmpty()
        streamJob = engine.startSend(
            scope, state, history, istifadəçiMesajı.content,
            istifadəçiMesajı.attachments, modelId, effort, isFirst,
        ) { ev -> onStreamEvent(köməkçiMesajı, ev) }
    }

    private fun onStreamEvent(köməkçi: ChatMsg, ev: StreamEvent) {
        when (ev) {
            is StreamEvent.Delta -> {
                köməkçi.content += ev.text
                renderThrottled()
            }
            is StreamEvent.Phase -> {
                statusLine.visibility = View.VISIBLE
                statusLine.text = ev.label
            }
            StreamEvent.Reset -> {
                köməkçi.content = ""
                mesajAdapter.notifyDataSetChanged()
            }
            is StreamEvent.ChatCreated -> state.chatId = ev.chatId
            StreamEvent.Done -> {
                köməkçi.streaming = false
                axınBitdi()
                mesajAdapter.notifyDataSetChanged()
                avtoSürüşdür(true)
                söhbətləriYenilə(vəBaşlığıSinxronlaşdır = true)
            }
            is StreamEvent.Error -> axınXətası(köməkçi, ev.message)
            StreamEvent.CaptchaRequired -> {
                köməkçi.streaming = false
                axınBitdi()
                captchaDialoqu()
            }
            StreamEvent.Unauthorized -> çıxışEt("Signed out — session expired")
        }
    }

    private fun axınXətası(köməkçi: ChatMsg, msg: String) {
        köməkçi.streaming = false
        köməkçi.error = true
        köməkçi.content = msg
        axınBitdi()
        mesajAdapter.notifyDataSetChanged()
    }

    private fun axınBitdi() {
        streaming = false
        streamJob = null
        statusLine.visibility = View.GONE
        göndərGörünüşünüYenilə()
    }

    private fun axınıDayandır() {
        streamJob?.cancel()
        messages.lastOrNull { it.role == "assistant" }?.let {
            it.streaming = false
            if (it.content.isEmpty()) it.content = "_(stopped)_"
        }
        axınBitdi()
        mesajAdapter.notifyDataSetChanged()
    }

    /** Xəta sətrinə toxunanda: xətalı cavabı sil, son istifadəçi mesajını təkrar göndər. */
    private fun təkrarGöndər() {
        if (streaming) return
        // son assistant xəta sətrini sil
        val lastAssistant = messages.lastOrNull { it.role == "assistant" }
        if (lastAssistant != null && lastAssistant.error) {
            messages.remove(lastAssistant)
            val sonİstifadəçi = messages.lastOrNull { it.role == "user" } ?: return
            val yeniKöməkçi = ChatMsg(Signature.genUuid(), "assistant", "", streaming = true)
            messages += yeniKöməkçi
            streaming = true
            göndərGörünüşünüYenilə()
            statusLine.text = "Sending…"
            statusLine.visibility = View.VISIBLE
            mesajAdapter.notifyDataSetChanged()
            avtoSürüşdür(true)
            axınıBaşlat(sonİstifadəçi, yeniKöməkçi)
        }
    }

    private fun renderThrottled() {
        val now = SystemClock.uptimeMillis()
        if (now - lastRender >= 120) {
            lastRender = now
            mesajAdapter.notifyDataSetChanged()
        } else {
            renderHandler.removeCallbacks(forceRender)
            renderHandler.postDelayed(forceRender, 150)
        }
        avtoSürüşdür()
    }

    private fun avtoSürüşdür(force: Boolean = false) {
        if (force || autoScroll) mesajListesi.setSelection(messages.size)
    }

    private fun mesajıKopyala(m: ChatMsg) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("message", m.content))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun updateEmptyState() {
        if (messages.isEmpty()) {
            welcome.visibility = View.VISIBLE
            mesajListesi.visibility = View.GONE
        } else {
            welcome.visibility = View.GONE
            mesajListesi.visibility = View.VISIBLE
        }
    }

    // ------------------------------------------------------------ modellər

    private fun currentModel(): ModelInfo =
        models.getOrNull(session.modelIndex.coerceIn(0, (models.size - 1).coerceAtLeast(0)))
            ?: ModelInfo("x-preview-l", "GLM-5.3-Flash")

    private fun currentEffort(): String {
        val m = currentModel()
        val idx = session.effortIndex
        return m.effortLevels.getOrNull(idx) ?: m.effortLevels.firstOrNull() ?: "high"
    }

    private suspend fun modelləriYüklə() {
        try {
            val siyahı = withContext(Dispatchers.IO) { api.models(session.token, session.isGuest) }
            models = siyahı
            // saxlanmış indeks keçərli olsun
            if (session.modelIndex >= models.size) session.modelIndex = 0
            modelChip.text = "${currentModel().name} ▾"
        } catch (_: Exception) {
            models = listOf(ModelInfo("x-preview-l", "GLM-5.3-Flash", true, listOf("low", "high", "max")))
            modelChip.text = "${currentModel().name} ▾"
        }
    }

    private fun modelDialoqu() {
        if (models.isEmpty()) {
            models = listOf(ModelInfo("x-preview-l", "GLM-5.3-Flash", true, listOf("low", "high", "max")))
        }
        val adlar = models.map { it.name + if (it.enabled) "" else " (unavailable)" }.toTypedArray()
        val seçilmiş = session.modelIndex.coerceIn(0, models.size - 1)
        AlertDialog.Builder(this)
            .setTitle("Model")
            .setSingleChoiceItems(adlar, seçilmiş) { dialoq, which ->
                val m = models[which]
                if (!m.enabled) {
                    Toast.makeText(this, "This model needs a full account", Toast.LENGTH_SHORT).show()
                    return@setSingleChoiceItems
                }
                session.modelIndex = which
                modelChip.text = "${m.name} ▾"
                dialoq.dismiss()
                if (m.effortLevels.size > 1) effortDialoqu(m) else session.effortIndex = 0
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun effortDialoqu(m: ModelInfo) {
        val səviyyələr = m.effortLevels.toTypedArray()
        val hazırkı = session.effortIndex.coerceIn(0, səviyyələr.size - 1)
        AlertDialog.Builder(this)
            .setTitle("Thinking effort — ${m.name}")
            .setSingleChoiceItems(səviyyələr, hazırkı) { dialoq, which ->
                session.effortIndex = which
                dialoq.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------ söhbətlər (sidebar)

    private fun sidebarAç() {
        scrim.visibility = View.VISIBLE
        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(180).start()
        sidebar.translationX = -d(320f).toFloat()
        sidebar.visibility = View.VISIBLE
        sidebar.animate().translationX(0f).setDuration(180).start()
        söhbətləriYenilə(vəBaşlığıSinxronlaşdır = false)
    }

    private fun sidebarBağla() {
        sidebar.animate().translationX(-d(320f).toFloat()).setDuration(180).withEndAction {
            sidebar.visibility = View.GONE
        }.start()
        scrim.animate().alpha(0f).setDuration(180).withEndAction {
            scrim.visibility = View.GONE
        }.start()
    }

    private fun söhbətləriYenilə(vəBaşlığıSinxronlaşdır: Boolean) {
        scope.launch {
            try {
                val siyahı = withContext(Dispatchers.IO) { api.conversations(session.token) }
                conversations = siyahı
                convAdapter.notifyDataSetChanged()
                if (vəBaşlığıSinxronlaşdır) {
                    siyahı.firstOrNull { it.id == state.chatId && it.title.isNotEmpty() }?.let {
                        state.chatTitle = it.title
                        navBaşlıq.text = it.title
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun söhbətiAç(id: String) {
        if (streaming) axınıDayandır()
        scope.launch {
            try {
                val detal = withContext(Dispatchers.IO) { api.chatDetail(session.token, id) }
                val msgs = ChatParse.messages(detal)
                state.chatId = id
                state.chatTitle = ChatParse.title(detal)
                messages.clear()
                messages += msgs
                navBaşlıq.text = state.chatTitle
                updateEmptyState()
                mesajAdapter.notifyDataSetChanged()
                avtoSürüşdür(true)
                sidebarBağla()
            } catch (e: Exception) {
                Toast.makeText(this@ChatActivity, "Could not open chat: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun söhbətiSil(c: ConvSummary) {
        AlertDialog.Builder(this)
            .setTitle("Delete chat")
            .setMessage("\"${c.title}\" silinsin?")
            .setPositiveButton("Delete") { _, _ ->
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { api.chatDelete(session.token, c.id) }
                        if (c.id == state.chatId) yeniSöhbət()
                        söhbətləriYenilə(false)
                    } catch (e: Exception) {
                        Toast.makeText(this@ChatActivity, "Delete failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun yeniSöhbət() {
        if (streaming) axınıDayandır()
        messages.clear()
        state.chatId = ""
        state.chatTitle = "New Chat"
        navBaşlıq.text = state.chatTitle
        updateEmptyState()
        mesajAdapter.notifyDataSetChanged()
        sidebarBağla()
    }

    // ------------------------------------------------------------ əlavələr

    @Suppress("DEPRECATION")
    private fun faylSeç() {
        val niyyət = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(Intent.createChooser(niyyət, "Select file"), 1001)
    }

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 1001) return
        if (resultCode != RESULT_OK || data?.data == null) return
        val uri: Uri = data.data!!
        scope.launch {
            try {
                val (ad, ölçü, mime) = withContext(Dispatchers.IO) { faylMəlumatı(uri) }
                if (ölçü > 60L * 1024 * 1024) {
                    Toast.makeText(this@ChatActivity,
                        "File too large (max 60 MB): ${ZaiApi.humanSize(ölçü)}", Toast.LENGTH_LONG).show()
                    return@launch
                }
                statusLine.text = "Reading $ad…"
                statusLine.visibility = View.VISIBLE
                val baytlar = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalStateException("cannot read file")
                }
                statusLine.visibility = View.GONE
                pendingFile = PendingFile(ad, mime, baytlar)
                uploadedFile = null
                attachChip.text = "📎 $ad (${ZaiApi.humanSize(baytlar.size.toLong())})  ✕"
                attachChip.visibility = View.VISIBLE
            } catch (e: Exception) {
                statusLine.visibility = View.GONE
                Toast.makeText(this@ChatActivity, "Could not read file: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun faylMəlumatı(uri: Uri): Triple<String, Long, String> {
        var ad = "file"
        var ölçü = -1L
        runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { k ->
                if (k.moveToFirst()) {
                    val adIdx = k.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val ölçüIdx = k.getColumnIndex(OpenableColumns.SIZE)
                    if (adIdx >= 0) k.getString(adIdx)?.let { ad = it }
                    if (ölçüIdx >= 0 && !k.isNull(ölçüIdx)) ölçü = k.getLong(ölçüIdx)
                }
            }
        }
        val mime = contentResolver.getType(uri) ?: "application/octet-stream"
        return Triple(ad, ölçü, mime)
    }

    private fun əlavəniSil() {
        pendingFile = null
        uploadedFile = null
        attachChip.visibility = View.GONE
    }

    // ------------------------------------------------------------ hesab

    private fun hesabDialoqu() {
        AlertDialog.Builder(this)
            .setTitle(session.userName.ifEmpty { "Account" })
            .setMessage(if (session.isGuest) "Guest mode" else "Signed in with email")
            .setPositiveButton("Sign out") { _, _ -> çıxışEt(null) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun çıxışEt(toast: String?) {
        if (streaming) axınıDayandır()
        session.clearAuth()
        val i = Intent(this, LoginActivity::class.java)
        if (toast != null) i.putExtra("exit_toast", toast)
        startActivity(i)
        finish()
    }

    private fun captchaDialoqu() {
        val tokenGiriş = EditText(this).apply {
            hint = "Paste your token here (eyJ…)"
            setHintTextColor(P.PLACEHOLDER)
            setTextColor(P.TEXT)
            textSize = 13f
            minLines = 3
            maxLines = 6
            setPadding(d(12f), d(10f), d(12f), d(10f))
        }
        val qutu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(20f), d(6f), d(20f), 0)
            addView(Ui.text(context,
                "Z.ai requested a captcha check.\nOpen chat.z.ai in your browser, sign in once, copy your token and paste it here.",
                13f, P.TEXT2))
            addView(tokenGiriş, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = d(12f) })
        }
        AlertDialog.Builder(this)
            .setTitle("Captcha required")
            .setView(qutu)
            .setPositiveButton("Sign in with token") { _, _ ->
                val token = tokenGiriş.text.toString().trim()
                scope.launch {
                    if (token.length >= 20) {
                        try {
                            val auth = withContext(Dispatchers.IO) { api.verifyToken(token) }
                            session.saveAuth(auth)
                            navPill.text = if (session.isGuest) "Sign in" else session.userName
                            Toast.makeText(this@ChatActivity, "Signed in", Toast.LENGTH_SHORT).show()
                            təkrarGöndər()
                        } catch (e: Exception) {
                            Toast.makeText(this@ChatActivity, "Token invalid", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNeutralButton("Open browser") { _, _ ->
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://chat.z.ai/")))
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
