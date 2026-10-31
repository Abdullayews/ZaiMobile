package zai.mobile.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import zai.mobile.App
import zai.mobile.R
import zai.mobile.engine.ChatEngine
import zai.mobile.engine.ChatState
import zai.mobile.engine.TurnEvent
import zai.mobile.protocol.Attachments
import zai.mobile.protocol.AuthApi
import zai.mobile.protocol.ChatSummary
import zai.mobile.protocol.ChatsApi
import zai.mobile.protocol.CompletionApi
import zai.mobile.protocol.ModelsApi
import zai.mobile.protocol.UploadApi
import zai.mobile.protocol.UploadResult
import zai.mobile.protocol.ZaiException
import zai.mobile.protocol.ZaiModel
import zai.mobile.state.Session

/**
 * Əsas ekran — 100% canlı data: modellər /api/models-dən, söhbətlər və
 * tarixçə serverdən, axın /api/v2 SSE. Statik fallback YOXDUR.
 */
class ChatActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val uiHandler = Handler(Looper.getMainLooper())

    private lateinit var session: Session
    private lateinit var authApi: AuthApi
    private lateinit var modelsApi: ModelsApi
    private lateinit var chatsApi: ChatsApi
    private lateinit var uploadApi: UploadApi
    private lateinit var completionApi: CompletionApi
    private lateinit var attachments: Attachments
    private lateinit var engine: ChatEngine

    private val chat = ChatState()
    private var models: List<ZaiModel> = emptyList()
    private var currentModel: ZaiModel? = null
    private var currentEffort: String = ""
    private var chatRows: List<ChatSummary> = emptyList()

    // ---- görüntülər ----
    private lateinit var sidebar: LinearLayout
    private lateinit var scrim: View
    private lateinit var sidebarList: LinearLayout
    private lateinit var sidebarStatus: TextView
    private lateinit var userPill: TextView
    private lateinit var toolbarTitle: TextView
    private lateinit var msgScroll: ScrollView
    private lateinit var msgList: LinearLayout
    private var welcome: LinearLayout? = null
    private lateinit var modelChip: TextView
    private lateinit var phaseChip: TextView
    private lateinit var chipsRow: LinearLayout
    private lateinit var input: EditText
    private lateinit var sendBtn: ImageButton

    // ---- axın vəziyyəti ----
    private var sendJob: Job? = null
    private var streamContainer: LinearLayout? = null
    private val streamBuf = StringBuilder()
    private var streamRenderScheduled = false

    private class ChipState(val name: String) {
        var status = 0 // 0=yüklənir 1=hazır 2=xəta
        var result: UploadResult? = null
    }

    private val pendingUploads = ArrayList<ChipState>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 31) runCatching { window.setHideOverlayWindows(true) }
        setContentView(buildUi())
        bindServices()
        loadModels()
        newChat()
        applyAccountUi()
    }

    private fun bindServices() {
        val http = App.http(this)
        session = Session(this)
        authApi = AuthApi(http)
        modelsApi = ModelsApi(http)
        chatsApi = ChatsApi(http)
        uploadApi = UploadApi(http)
        completionApi = CompletionApi(http)
        attachments = Attachments(contentResolver)
        engine = ChatEngine(authApi, chatsApi, completionApi, session)
    }

    // ================= UI quruluşu =================

    private fun buildUi(): View {
        val root = FrameLayout(this)

        // ---- məzmun sütunu ----
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setBackgroundColor(color(R.color.page))

        // toolbar
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(0, dp(4f), dp(8f), 0)
        bar.addView(iconButton(R.drawable.ic_menu, getString(R.string.cd_menu)) { toggleSidebar() })
        toolbarTitle = TextView(this)
        toolbarTitle.text = getString(R.string.app_name)
        toolbarTitle.textSize = sp(16.5f)
        toolbarTitle.setTextColor(color(R.color.text))
        toolbarTitle.ellipsize = TextUtils.TruncateAt.END
        toolbarTitle.maxLines = 1
        bar.addView(
            toolbarTitle,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(2f)
            },
        )
        userPill = TextView(this)
        userPill.text = getString(R.string.sign_in)
        userPill.textSize = sp(13f)
        userPill.setTextColor(color(R.color.page))
        userPill.setTypeface(userPill.typeface, Typeface.BOLD)
        userPill.background = roundRect(color(R.color.accent), 20f)
        userPill.setPadding(dp(14f), dp(7f), dp(14f), dp(7f))
        userPill.setOnClickListener { openLogin() }
        bar.addView(userPill)
        col.addView(
            bar,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52f)),
        )

        // mesajlar
        msgScroll = ScrollView(this)
        msgScroll.isVerticalScrollBarEnabled = false
        msgScroll.setFillViewport(true)
        msgList = LinearLayout(this)
        msgList.orientation = LinearLayout.VERTICAL
        msgList.setPadding(dp(16f), dp(6f), dp(16f), dp(8f))
        msgScroll.addView(
            msgList,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        col.addView(
            msgScroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )

        // model + faza sətri
        val meta = LinearLayout(this)
        meta.orientation = LinearLayout.HORIZONTAL
        meta.gravity = Gravity.CENTER_VERTICAL
        meta.setPadding(dp(16f), 0, dp(16f), 0)
        modelChip = TextView(this)
        modelChip.text = "…"
        modelChip.textSize = sp(13f)
        modelChip.setTextColor(color(R.color.text2))
        modelChip.background = roundRect(color(R.color.surface), 20f, stroke = color(R.color.border))
        modelChip.setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
        modelChip.setOnClickListener { showModelPopup() }
        modelChip.contentDescription = getString(R.string.cd_model)
        meta.addView(modelChip)
        phaseChip = TextView(this)
        phaseChip.textSize = sp(12.5f)
        phaseChip.setTextColor(color(R.color.text3))
        phaseChip.visibility = View.GONE
        phaseChip.setPadding(dp(10f), 0, 0, 0)
        meta.addView(
            phaseChip,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        val metaLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        metaLp.topMargin = dp(6f)
        meta.layoutParams = metaLp
        col.addView(meta)

        // fayl çipləri
        val chipsScroll = HorizontalScrollView(this)
        chipsScroll.isHorizontalScrollBarEnabled = false
        chipsRow = LinearLayout(this)
        chipsRow.orientation = LinearLayout.HORIZONTAL
        chipsScroll.addView(
            chipsRow,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        col.addView(
            chipsScroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        // giriş sətri
        val composer = LinearLayout(this)
        composer.orientation = LinearLayout.HORIZONTAL
        composer.gravity = Gravity.CENTER_VERTICAL
        composer.setPadding(dp(8f), dp(4f), dp(8f), dp(8f))
        val plus = iconButton(R.drawable.ic_plus, getString(R.string.cd_attach)) { pickFile() }
        composer.addView(plus)
        input = field(getString(R.string.hint_composer), singleLine = false)
        input.minLines = 1
        input.maxLines = 6
        input.background = roundRect(color(R.color.surface), 22f, stroke = color(R.color.border))
        composer.addView(
            input,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(4f)
                marginEnd = dp(4f)
            },
        )
        sendBtn = ImageButton(this)
        sendBtn.setImageResource(R.drawable.ic_send)
        sendBtn.background = circle(color(R.color.accent))
        sendBtn.imageTintList = android.content.res.ColorStateList.valueOf(color(R.color.page))
        sendBtn.setPadding(dp(12f), dp(12f), dp(10f), dp(12f))
        sendBtn.contentDescription = getString(R.string.cd_send)
        sendBtn.setOnClickListener { onSendClicked() }
        composer.addView(
            sendBtn,
            LinearLayout.LayoutParams(dp(48f), dp(48f)),
        )
        col.addView(
            composer,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        root.addView(
            col,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )

        // ---- yan panel (overlay) ----
        scrim = View(this)
        scrim.setBackgroundColor(0x99000000.toInt())
        scrim.visibility = View.GONE
        scrim.setOnClickListener { closeSidebar() }
        root.addView(
            scrim,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )

        sidebar = LinearLayout(this)
        sidebar.orientation = LinearLayout.VERTICAL
        sidebar.setBackgroundColor(color(R.color.surface))
        sidebar.setPadding(dp(14f), dp(14f), dp(14f), dp(10f))
        val sidebarLp = FrameLayout.LayoutParams(dp(300f), ViewGroup.LayoutParams.MATCH_PARENT)
        sidebar.layoutParams = sidebarLp

        val brand = TextView(this)
        brand.text = getString(R.string.app_name)
        brand.textSize = sp(20f)
        brand.setTextColor(color(R.color.text))
        brand.typeface = Typeface.create(serif(), Typeface.BOLD)
        brand.setPadding(0, 0, 0, dp(6f))
        sidebar.addView(brand)

        val newChat = textButton(getString(R.string.new_chat), filled = true) { newChat(); closeSidebar() }
        sidebar.addView(
            newChat,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(10f) },
        )

        val chatsTitle = label(getString(R.string.chats_title), 12f, R.color.text3)
        chatsTitle.setPadding(0, dp(8f), 0, dp(4f))
        sidebar.addView(chatsTitle)

        val sv = ScrollView(this)
        sidebarList = LinearLayout(this)
        sidebarList.orientation = LinearLayout.VERTICAL
        sv.addView(
            sidebarList,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        sidebar.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        sidebarStatus = label("", 13f, R.color.text2)
        sidebarStatus.setPadding(0, dp(6f), 0, dp(6f))
        sidebar.addView(sidebarStatus)

        root.addView(sidebar)
        sidebar.translationX = -dp(320f).toFloat()
        return root
    }

    // ================= Yan panel =================

    private fun toggleSidebar() {
        if (scrim.visibility == View.VISIBLE) closeSidebar() else openSidebar()
    }

    private fun openSidebar() {
        scrim.visibility = View.VISIBLE
        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(180).start()
        sidebar.visibility = View.VISIBLE
        sidebar.animate().translationX(0f).setDuration(200).start()
        applyAccountUi()
        refreshChats()
    }

    private fun closeSidebar() {
        scrim.animate().alpha(0f).setDuration(180).withEndAction { scrim.visibility = View.GONE }.start()
        sidebar.animate().translationX(-dp(320f).toFloat()).setDuration(200).start()
    }

    private fun applyAccountUi() {
        if (session.isGuest) {
            userPill.text = getString(R.string.sign_in)
            sidebarStatus.text = getString(R.string.sign_in_sync)
            sidebarStatus.setTextColor(color(R.color.text3))
        } else {
            userPill.text = session.userName.ifBlank { getString(R.string.guest_badge) }
            sidebarStatus.text = getString(R.string.sign_out)
            sidebarStatus.setTextColor(color(R.color.danger))
            sidebarStatus.setOnClickListener {
                sidebarStatus.setOnClickListener(null)
                doSignOut()
            }
        }
    }

    private fun refreshChats() {
        if (session.isGuest) {
            sidebarList.removeAllViews()
            return
        }
        val status = label("…", 13f, R.color.text3)
        sidebarList.removeAllViews()
        sidebarList.addView(status)
        scope.launch {
            try {
                val rows = withContext(App.io()) { chatsApi.list(session.token) }
                if (isFinishing || isDestroyed) return@launch
                chatRows = rows
                sidebarList.removeAllViews()
                if (rows.isEmpty()) {
                    sidebarList.addView(label(getString(R.string.no_chats), 13f, R.color.text3))
                    return@launch
                }
                for (s in rows) sidebarList.addView(chatRowView(s))
            } catch (e: Exception) {
                if (isFinishing || isDestroyed) return@launch
                sidebarList.removeAllViews()
                val err = label(getString(R.string.chats_failed), 13f, R.color.danger)
                err.setOnClickListener { refreshChats() }
                sidebarList.addView(err)
            }
        }
    }

    private fun chatRowView(s: ChatSummary): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.VERTICAL
        row.background = rippleRect()
        row.setPadding(dp(10f), dp(10f), dp(4f), dp(10f))
        val title = TextView(this)
        title.text = s.title
        title.textSize = sp(14.5f)
        title.setTextColor(color(R.color.text))
        title.ellipsize = TextUtils.TruncateAt.END
        title.maxLines = 1
        row.addView(
            title,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        val date = TextView(this)
        date.text = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
            .format(java.util.Date(s.updatedAt))
        date.textSize = sp(11.5f)
        date.setTextColor(color(R.color.text3))
        row.addView(date)
        val del = iconButton(R.drawable.ic_trash, getString(R.string.cd_delete), sizeDp = 40, iconDp = 18) {
            deleteChat(s)
        }
        val head = FrameLayout(this)
        val headInner = LinearLayout(this)
        headInner.orientation = LinearLayout.HORIZONTAL
        headInner.addView(
            row,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        headInner.addView(del)
        head.addView(
            headInner,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        head.setOnClickListener { closeSidebar(); openChat(s) }
        return head
    }

    private fun deleteChat(s: ChatSummary) {
        scope.launch {
            runCatching { withContext(App.io()) { chatsApi.delete(session.token, s.id) } }
            if (isFinishing || isDestroyed) return@launch
            if (s.id == chat.chatId) {
                newChat()
            }
            refreshChats()
        }
    }

    // ================= Mesajlar =================

    private fun showWelcome() {
        if (welcome != null && welcome!!.parent != null) return
        val w = LinearLayout(this)
        w.orientation = LinearLayout.VERTICAL
        w.gravity = Gravity.CENTER_HORIZONTAL
        w.setPadding(0, dp(64f), 0, dp(64f))
        val word = TextView(this)
        word.text = getString(R.string.app_name)
        word.textSize = sp(30f)
        word.typeface = Typeface.create(serif(), Typeface.BOLD)
        word.setTextColor(color(R.color.text))
        word.gravity = Gravity.CENTER
        val hint = label(getString(R.string.welcome_hint), 15f, R.color.text3)
        hint.gravity = Gravity.CENTER
        hint.setPadding(0, dp(10f), 0, 0)
        w.addView(word)
        w.addView(hint)
        welcome = w
        msgList.addView(w, 0)
    }

    private fun hideWelcome() {
        welcome?.let { (it.parent as? ViewGroup)?.removeView(it) }
    }

    private fun addUserRow(text: String, names: List<String>) {
        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.VERTICAL
        wrap.gravity = Gravity.END
        val bubble = TextView(this)
        bubble.text = text
        bubble.textSize = sp(15.5f)
        bubble.setTextColor(color(R.color.text))
        bubble.background = roundRect(color(R.color.bg), 14f)
        bubble.setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
        bubble.maxWidth = dp(300f)
        wrap.addView(
            bubble,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        for (n in names) {
            val chip = label(n, 11.5f, R.color.text3)
            chip.gravity = Gravity.END
            chip.setPadding(0, dp(3f), dp(4f), 0)
            wrap.addView(chip)
        }
        msgList.addView(
            wrap,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(10f); bottomMargin = dp(10f) },
        )
        scrollBottom()
    }

    private fun addAssistantContainer(): LinearLayout {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(0, dp(4f), 0, dp(10f))
        msgList.addView(
            box,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        scrollBottom()
        return box
    }

    private fun addErrorRow(message: String, canRetry: Boolean) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(0, dp(4f), 0, dp(10f))
        if (canRetry) {
            box.background = rippleRect()
            box.setOnClickListener {
                msgList.removeView(box)
                retrySend()
            }
        }
        val t = TextView(this)
        t.text = message
        t.textSize = sp(14f)
        t.setTextColor(color(R.color.danger))
        box.addView(t)
        if (canRetry) {
            val h = label(getString(R.string.tap_retry), 12f, R.color.text3)
            box.addView(h)
        }
        msgList.addView(
            box,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        scrollBottom()
    }

    private fun addFooterOnce() {
        val f = label(getString(R.string.footer), 11.5f, R.color.text3)
        f.gravity = Gravity.CENTER
        f.setPadding(0, dp(18f), 0, dp(6f))
        msgList.addView(
            f,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
    }

    private fun scrollBottom() {
        val inner = msgScroll.getChildAt(0)
        val need = inner.height - msgScroll.height
        if (need - msgScroll.scrollY > dp(400f)) return // istifadəçi yuxarıda oxuyur
        msgScroll.post { msgScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    // ================= Modellər =================

    private fun loadModels() {
        scope.launch {
            try {
                val list = withContext(App.io()) { modelsApi.list(session.token, session.isGuest) }
                if (isFinishing || isDestroyed) return@launch
                models = list
                currentModel = list.firstOrNull { it.id == session.modelId }
                    ?: list.firstOrNull()
                currentModel?.let { m ->
                    currentEffort = session.effort.takeIf { m.effortLevels.contains(it) }
                        ?: m.effortLevels.lastOrNull().orEmpty()
                }
                if (list.isEmpty()) {
                    // statik fallback YOXDUR — düz xəta göstərilir
                    modelChip.text = getString(R.string.models_failed)
                    modelChip.setTextColor(color(R.color.danger))
                } else {
                    modelChip.text = currentModel?.name
                    modelChip.setTextColor(color(R.color.text2))
                }
            } catch (e: Exception) {
                if (isFinishing || isDestroyed) return@launch
                modelChip.text = getString(R.string.models_failed)
                modelChip.setTextColor(color(R.color.danger))
            }
        }
    }

    private fun showModelPopup() {
        if (models.isEmpty()) {
            Toast.makeText(this, R.string.models_failed, Toast.LENGTH_SHORT).show()
            loadModels() // boş siyahı = yüklənməyib → təkrar
            return
        }
        var pw: PopupWindow? = null
        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        list.setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
        for (m in models) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.VERTICAL
            row.background = rippleRect()
            row.setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            val name = TextView(this)
            name.text = m.name
            name.textSize = sp(15f)
            name.setTextColor(color(if (m === currentModel) R.color.accent else R.color.text))
            name.setTypeface(name.typeface, if (m === currentModel) Typeface.BOLD else Typeface.NORMAL)
            row.addView(name)
            if (m.effortLevels.isNotEmpty()) {
                val lv = label(m.effortLevels.joinToString(" · "), 11.5f, R.color.text3)
                row.addView(lv)
            }
            row.setOnClickListener {
                pickModel(m)
                pw?.dismiss()
            }
            list.addView(row)
        }
        pw = PopupWindow(list, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        pw.isFocusable = true
        pw.isOutsideTouchable = true
        pw.setBackgroundDrawable(roundRect(color(R.color.surface), 12f, stroke = color(R.color.border)))
        pw.showAsDropDown(modelChip, 0, -dp(8f))
    }

    private fun pickModel(m: ZaiModel) {
        currentModel = m
        if (m.effortLevels.size > 1) {
            currentEffort = session.effort.takeIf { m.effortLevels.contains(it) } ?: m.effortLevels.last()
        } else {
            currentEffort = ""
        }
        modelChip.text = m.name
        session.saveModelSelection(m.id, currentEffort)
    }

    // ================= Söhbət açma / yeni =================

    private fun openChat(s: ChatSummary) {
        engine.stop()
        sendJob?.cancel()
        chat.reset()
        clearMessages()
        toolbarTitle.text = s.title
        addLoadingRow()
        scope.launch {
            try {
                val d = withContext(App.io()) { chatsApi.detail(session.token, s.id) }
                if (isFinishing || isDestroyed) return@launch
                chat.chatId = d.id
                chat.title = d.title
                toolbarTitle.text = d.title
                clearMessages()
                if (d.messages.isEmpty()) {
                    showWelcome()
                } else {
                    for (m in d.messages) {
                        if (m.role == "user") {
                            addUserRow(m.text, m.attachments.map { it.kind })
                        } else {
                            val box = addAssistantContainer()
                            Md.render(this@ChatActivity, box, Md.parse(m.text)) { copyCode(it) }
                        }
                    }
                    hideWelcome()
                }
                addFooterOnce()
            } catch (e: Exception) {
                if (isFinishing || isDestroyed) return@launch
                clearMessages()
                showWelcome()
                Toast.makeText(this@ChatActivity, R.string.open_chat_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun newChat() {
        engine.stop()
        sendJob?.cancel()
        chat.reset()
        clearMessages()
        showWelcome()
        addFooterOnce()
        toolbarTitle.text = getString(R.string.app_name)
    }

    private fun clearMessages() {
        msgList.removeAllViews()
        streamContainer = null
        streamBuf.setLength(0)
    }

    private fun addLoadingRow() {
        val t = label("…", 14f, R.color.text3)
        t.setPadding(0, dp(12f), 0, dp(12f))
        msgList.addView(
            t,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
    }

    // ================= Göndərmə =================

    private fun onSendClicked() {
        if (sendJob?.isActive == true) {
            engine.stop()
            return
        }
        send()
    }

    private fun send() {
        val prompt = input.text.toString().trim()
        val m = currentModel
        if (prompt.isEmpty()) return
        if (m == null) {
            Toast.makeText(this, R.string.models_failed, Toast.LENGTH_SHORT).show()
            return
        }
        if (pendingUploads.any { it.status == 0 }) {
            Toast.makeText(this, R.string.attach_uploading, Toast.LENGTH_SHORT).show()
            return
        }
        if (pendingUploads.any { it.status == 2 }) {
            Toast.makeText(this, R.string.attach_failed, Toast.LENGTH_SHORT).show()
            return
        }
        if (session.isGuest && pendingUploads.isNotEmpty()) {
            Toast.makeText(this, R.string.attach_sign_in, Toast.LENGTH_SHORT).show()
            return
        }

        hideWelcome()
        addUserRow(prompt, pendingUploads.map { it.name })
        val uploads = pendingUploads.mapNotNull { it.result }
        pendingUploads.clear()
        renderChips()
        input.setText("")
        phaseChip.visibility = View.VISIBLE
        phaseChip.text = "…"
        setStreaming(true)

        streamBuf.setLength(0)
        streamContainer = addAssistantContainer()
        scrollBottom()

        sendJob = scope.launch(App.io()) {
            try {
                engine.sendTurn(
                    chat, prompt, uploads, m, currentEffort, liveFingerprint(this@ChatActivity),
                ) { ev -> uiHandler.post { if (!isFinishing && !isDestroyed) handleEvent(ev) } }
            } catch (e: ZaiException) {
                uiHandler.post {
                    if (!isFinishing && !isDestroyed) {
                        finishStream()
                        addErrorRow(e.message ?: "error", e.isRetryable)
                    }
                }
            } catch (e: Exception) {
                uiHandler.post {
                    if (!isFinishing && !isDestroyed) {
                        finishStream()
                        addErrorRow(e.message ?: "Unexpected error", false)
                    }
                }
            }
        }
    }

    private fun retrySend() {
        if (sendJob?.isActive == true) return
        val m = currentModel ?: return
        setStreaming(true)
        phaseChip.visibility = View.VISIBLE
        phaseChip.text = "…"
        streamBuf.setLength(0)
        streamContainer = addAssistantContainer()
        sendJob = scope.launch(App.io()) {
            try {
                engine.retryLast(chat) { ev -> uiHandler.post { if (!isFinishing && !isDestroyed) handleEvent(ev) } }
            } catch (e: ZaiException) {
                uiHandler.post {
                    if (!isFinishing && !isDestroyed) {
                        finishStream()
                        addErrorRow(e.message ?: "error", e.isRetryable)
                    }
                }
            }
        }
    }

    private fun handleEvent(ev: TurnEvent) {
        when (ev) {
            is TurnEvent.ChatCreated -> chat.chatId = ev.chatId
            is TurnEvent.Phase -> {
                phaseChip.visibility = View.VISIBLE
                phaseChip.text = ev.label
            }
            is TurnEvent.Delta -> {
                streamBuf.append(ev.text)
                scheduleStreamRender()
                scrollBottom()
            }
            TurnEvent.Reset -> {
                streamBuf.setLength(0)
                streamContainer?.removeAllViews()
            }
            is TurnEvent.Completed -> {
                streamBuf.setLength(0)
                streamBuf.append(ev.fullText)
                renderStreamNow()
                finishStream()
                refreshTitleFromServer()
            }
            is TurnEvent.Stopped -> {
                if (ev.partialText.isNotBlank()) {
                    streamBuf.setLength(0)
                    streamBuf.append(ev.partialText)
                    renderStreamNow()
                }
                finishStream()
            }
            is TurnEvent.Failed -> {
                streamContainer?.let { msgList.removeView(it) }
                streamContainer = null
                finishStream()
                addErrorRow(ev.message, ev.canRetry)
            }
        }
    }

    private fun finishStream() {
        phaseChip.visibility = View.GONE
        setStreaming(false)
        streamContainer = null
    }

    private fun setStreaming(on: Boolean) {
        sendBtn.setImageResource(if (on) R.drawable.ic_stop else R.drawable.ic_send)
        sendBtn.contentDescription = getString(if (on) R.string.cd_stop else R.string.cd_send)
        sendBtn.background = circle(color(if (on) R.color.surface else R.color.accent))
        sendBtn.imageTintList = android.content.res.ColorStateList.valueOf(
            color(if (on) R.color.text else R.color.page),
        )
        if (!on) input.requestFocus()
    }

    private fun scheduleStreamRender() {
        if (streamRenderScheduled) return
        streamRenderScheduled = true
        uiHandler.postDelayed(
            {
                streamRenderScheduled = false
                renderStreamNow()
            },
            90,
        )
    }

    private fun renderStreamNow() {
        val c = streamContainer ?: return
        val text = streamBuf.toString()
        val body = if (text.length > 60_000) "…\n" + text.substring(text.length - 30_000) else text
        Md.render(this, c, Md.parse(body)) { copyCode(it) }
    }

    private fun refreshTitleFromServer() {
        val id = chat.chatId ?: return
        scope.launch {
            runCatching {
                val d = withContext(App.io()) { chatsApi.detail(session.token, id) }
                if (isFinishing || isDestroyed) return@launch
                chat.title = d.title
                toolbarTitle.text = d.title
            }
        }
    }

    private fun copyCode(code: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("code", code))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    // ================= Fayllar =================

    private fun pickFile() {
        if (session.isGuest) {
            Toast.makeText(this, R.string.attach_sign_in, Toast.LENGTH_SHORT).show()
            return
        }
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        runCatching { startActivityForResult(Intent.createChooser(i, getString(R.string.attach)), REQ_PICK) }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK && resultCode == RESULT_OK) {
            handlePicked(data?.data)
        }
        if (requestCode == REQ_LOGIN && resultCode == LoginActivity.RESULT_SIGNED_IN) {
            onSignedIn()
        }
    }

    private fun handlePicked(uri: Uri?) {
        if (uri == null) return
        scope.launch {
            try {
                val picked = withContext(App.io()) { attachments.probe(uri) }
                if (picked == null) {
                    toastMain(R.string.attach_failed)
                    return@launch
                }
                if (picked.size > Attachments.MAX_BYTES) {
                    pendingUploads.add(ChipState(picked.name).apply { status = 2 })
                    renderChips()
                    return@launch
                }
                val chip = ChipState(picked.name)
                pendingUploads.add(chip)
                renderChips()
                val prepared = withContext(App.io()) { attachments.prepare(picked) }
                if (prepared == null) {
                    chip.status = 2
                    renderChips()
                    return@launch
                }
                val res = withContext(App.io()) { uploadApi.upload(session.token, prepared) }
                if (isFinishing || isDestroyed) return@launch
                chip.result = res
                chip.status = 1
                renderChips()
            } catch (e: Exception) {
                if (isFinishing || isDestroyed) return@launch
                pendingUploads.filter { it.status == 0 }.forEach { it.status = 2 }
                renderChips()
                toastMain(R.string.attach_failed)
            }
        }
    }

    private fun renderChips() {
        uiHandler.post {
            chipsRow.removeAllViews()
            for (c in pendingUploads) {
                val t = TextView(this)
                t.text = when (c.status) {
                    0 -> "Uploading — ${c.name}"
                    1 -> c.name
                    else -> "Failed — ${c.name}"
                }
                t.textSize = sp(12f)
                t.setTextColor(color(if (c.status == 2) R.color.danger else R.color.text2))
                t.background = roundRect(color(R.color.surface), 16f, stroke = color(R.color.border))
                t.setPadding(dp(10f), dp(5f), dp(10f), dp(5f))
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.marginEnd = dp(8f)
                t.layoutParams = lp
                t.setOnClickListener {
                    pendingUploads.remove(c)
                    renderChips()
                }
                chipsRow.addView(t)
            }
        }
    }

    private fun toastMain(resId: Int) {
        uiHandler.post {
            if (!isFinishing && !isDestroyed) Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
        }
    }

    // ================= Hesab =================

    private fun openLogin() {
        startActivityForResult(Intent(this, LoginActivity::class.java), REQ_LOGIN)
    }

    private fun onSignedIn() {
        bindServices()
        loadModels()
        newChat()
        applyAccountUi()
        Toast.makeText(this, session.userName.ifBlank { "z.ai" }, Toast.LENGTH_SHORT).show()
    }

    private fun doSignOut() {
        scope.launch {
            runCatching {
                withContext(App.io()) {
                    session.clearAuth()
                    val g = authApi.guest()
                    session.saveAuth(g)
                }
            }
            if (isFinishing || isDestroyed) return@launch
            bindServices()
            loadModels()
            newChat()
            applyAccountUi()
            sidebarStatus.setOnClickListener(null)
            Toast.makeText(this@ChatActivity, R.string.sign_out, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        engine.stop()
        sendJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val REQ_PICK = 41
        const val REQ_LOGIN = 42
    }
}
