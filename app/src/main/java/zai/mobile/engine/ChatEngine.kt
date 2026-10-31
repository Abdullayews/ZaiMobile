package zai.mobile.engine

import kotlinx.coroutines.delay
import zai.mobile.protocol.AuthApi
import zai.mobile.protocol.AuthInfo
import zai.mobile.protocol.ChatObjects
import zai.mobile.protocol.ChatsApi
import zai.mobile.protocol.CompletionApi
import zai.mobile.protocol.Signature
import zai.mobile.protocol.StreamEvent
import zai.mobile.protocol.StreamOutcome
import zai.mobile.protocol.UploadResult
import zai.mobile.protocol.ZaiException
import zai.mobile.protocol.ZaiModel
import zai.mobile.state.Session
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bir söhbətin protokol vəziyyəti: chatId + növbələr (tarixçə).
 * turns: (role, text, error) — xəta sətirləri history-dən filtrlənir (spec §12).
 */
class ChatState {
    var chatId: String? = null
    var title: String? = null
    val turns = ArrayList<Turn>(16)

    class Turn(val role: String, val text: String, val error: Boolean)

    fun historyTurns(): List<Pair<String, String>> =
        turns.filter { !it.error }.map { it.role to it.text }

    fun reset() {
        chatId = null
        title = null
        turns.clear()
    }
}

/** UI-yə gedən hadisələr. */
sealed class TurnEvent {
    data class ChatCreated(val chatId: String) : TurnEvent()
    data class Phase(val label: String) : TurnEvent()
    data class Delta(val text: String) : TurnEvent()
    object Reset : TurnEvent()
    data class Completed(val fullText: String) : TurnEvent()
    data class Stopped(val partialText: String) : TurnEvent()
    data class Failed(val message: String, val canRetry: Boolean) : TurnEvent()
}

/**
 * Göndərmə orkestratoru — spec §5/§12.
 * Axın: 4 daxili cəhd (hər təkrarda Reset) + 3 avto-yenidəngöndərmə
 * (2.5s → ×2). Qonaq 401: bir dəfə qonaq tokeni yenilə + eyni id-lərlə
 * idempotent təkrar. İstifadəçi stop: qismən mətn saxlanılır və saxlanılır.
 */
class ChatEngine(
    private val authApi: AuthApi,
    private val chatsApi: ChatsApi,
    private val completionApi: CompletionApi,
    private val session: Session,
) {
    private val stopped = AtomicBoolean(false)
    @Volatile
    private var liveCancel: (() -> Unit)? = null

    // son göndərmə parametrləri (retry üçün)
    private var lastPrompt: String? = null
    private var lastUploads: List<UploadResult> = emptyList()
    private var lastModel: ZaiModel? = null
    private var lastEffort: String? = null
    private var lastFingerprint: Signature.Fingerprint? = null

    fun stop() {
        stopped.set(true)
        liveCancel?.invoke()
        liveCancel = null
    }

    fun isStopped(): Boolean = stopped.get()

    private fun clearStop() {
        stopped.set(false)
        liveCancel = null
    }

    /**
     * Yeni göndərmə: user növbəsini turns-a əlavə edir, protokolu işə salır.
     * UI bu funksiyanı lifecycleScopeLaunch(IO-a yaxın) içində çağırır.
     */
    suspend fun sendTurn(
        state: ChatState,
        prompt: String,
        uploads: List<UploadResult>,
        model: ZaiModel,
        effort: String,
        fingerprint: Signature.Fingerprint,
        onEvent: (TurnEvent) -> Unit,
    ) {
        clearStop()
        lastPrompt = prompt
        lastUploads = uploads
        lastModel = model
        lastEffort = effort
        lastFingerprint = fingerprint

        state.turns.add(ChatState.Turn("user", prompt, false))
        runTurn(state, prompt, uploads, model, effort, fingerprint, onEvent)
    }

    /**
     * Xəta sətirinə toxunma — eyni parametrlərlə idempotent təkrar
     * (error-marker turns-dan düşürülür; user növbəsi yerində qalır).
     */
    suspend fun retryLast(state: ChatState, onEvent: (TurnEvent) -> Unit): Boolean {
        val prompt = lastPrompt ?: return false
        val model = lastModel ?: return false
        val effort = lastEffort ?: return false
        val fp = lastFingerprint ?: return false
        if (state.turns.isNotEmpty() && state.turns.last().error) {
            state.turns.removeAt(state.turns.size - 1)
        }
        if (state.turns.isEmpty() || state.turns.last().role != "user") return false
        clearStop()
        runTurn(state, prompt, lastUploads, model, effort, fp, onEvent)
        return true
    }

    private suspend fun runTurn(
        state: ChatState,
        prompt: String,
        uploads: List<UploadResult>,
        model: ZaiModel,
        effort: String,
        fingerprint: Signature.Fingerprint,
        onEvent: (TurnEvent) -> Unit,
    ) {
        var token = session.token
        if (token.isBlank()) {
            // nadir hal: proses öldürüldükdən sonra ChatActivity birbaşa bərpa olunub
            // və state.json-da token yoxdur → özünü qonaqla sağaldırıq (crash YOX)
            val g = authApi.guest()
            session.saveAuth(g)
            token = g.token
        }

        val parts = uploads.map { it.toPart() }
        val historyBefore = state.historyTurns().dropLast(1) // user növbəsi cari mesajdır
        val isFirstTurn = state.turns.none { it.role == "assistant" }

        // ---- chat yaratma (ilk dönüş) ----
        if (state.chatId == null) {
            val userMsgId = newId()
            val ts = System.currentTimeMillis()
            val chatObj = ChatObjects.newChat(model.id, prompt, userMsgId, ts)
            val chatId = chatsApi.create(token, chatObj)
            state.chatId = chatId
            onEvent(TurnEvent.ChatCreated(chatId))
        }
        val chatId = state.chatId ?: return

        // ---- completion: 4 daxili cəhd + 3 avto-təkrar ----
        val assistantMsgId = newId()
        val userMsgId = newId()
        val sessionId = newId()
        val bodyBytes = completionApi.buildBody(
            model = model.id,
            history = historyBefore,
            prompt = prompt,
            promptAttachments = parts,
            signaturePrompt = prompt,
            sessionId = sessionId,
            chatId = chatId,
            assistantMsgId = assistantMsgId,
            userMsgId = userMsgId,
            isFirst = isFirstTurn,
            effort = effort,
        )

        var text = ""
        var streamEx: ZaiException? = null
        var result: StreamOutcome? = null
        var resends = 0
        var guestReauthored = false

        outer@ while (true) {
            var attempt = 0
            while (attempt < INNER_ATTEMPTS) {
                if (stopped.get()) {
                    result = StreamOutcome.STOPPED
                    break@outer
                }
                attempt++
                if (attempt > 1) {
                    onEvent(TurnEvent.Reset)
                    text = ""
                }
                streamEx = null
                var sawError: ZaiException? = null
                val outcome = completionApi.streamOnce(
                    token = token,
                    userId = session.userId,
                    fingerprint = fingerprint,
                    prompt = prompt,
                    bodyBytes = bodyBytes,
                    onEvent = { e ->
                        when (e) {
                            is StreamEvent.Delta -> {
                                text += e.text
                                onEvent(TurnEvent.Delta(e.text))
                            }
                            is StreamEvent.Phase -> onEvent(TurnEvent.Phase(e.label))
                            is StreamEvent.Done -> Unit
                            is StreamEvent.Reset -> Unit
                            is StreamEvent.Error -> sawError = e.ex
                        }
                    },
                    isStopped = { stopped.get() },
                )
                when (outcome) {
                    StreamOutcome.COMPLETED -> {
                        result = StreamOutcome.COMPLETED
                        break@outer
                    }
                    StreamOutcome.STOPPED -> {
                        result = StreamOutcome.STOPPED
                        break@outer
                    }
                    StreamOutcome.DROPPED -> streamEx = ZaiException.Dropped()
                    StreamOutcome.FAILED -> streamEx = sawError ?: ZaiException.Dropped()
                }
                val ex = streamEx
                // qonaq 401: bir dəfə token yenilə + eyni id-lərlə təkrar
                if (ex is ZaiException.Unauthorized && session.isGuest && !guestReauthored) {
                    val fresh: AuthInfo = try {
                        authApi.guest()
                    } catch (e: Exception) {
                        break@outer
                    }
                    guestReauthored = true
                    session.saveAuth(fresh)
                    token = fresh.token
                    continue
                }
                if (ex == null || !ex.isRetryable) break@outer
                if (attempt < INNER_ATTEMPTS) delay(1200L + 600L * attempt)
            }
            // daxili cəhdlər tükəndi — retryable-dırsa avto-təkrar
            val ex = streamEx
            if (result != null) break@outer
            if (ex == null || !ex.isRetryable) break@outer
            if (stopped.get()) {
                result = StreamOutcome.STOPPED
                break@outer
            }
            if (resends >= MAX_RESENDS) break@outer
            resends++
            // təkrardan əvvəl qismən mətni sıfırla (spec: hər retry → Reset)
            onEvent(TurnEvent.Reset)
            text = ""
            delay(minOf(15_000L, 2500L shl (resends - 1)))
        }

        when (result) {
            StreamOutcome.COMPLETED -> {
                state.turns.add(ChatState.Turn("assistant", text, false))
                persist(state, model, effort, onEvent)
                onEvent(TurnEvent.Completed(text))
            }
            StreamOutcome.STOPPED -> {
                if (text.isNotBlank()) {
                    state.turns.add(ChatState.Turn("assistant", text, false))
                    persist(state, model, effort, onEvent)
                }
                onEvent(TurnEvent.Stopped(text))
            }
            else -> {
                val message = streamEx?.message ?: "The stream ended unexpectedly."
                state.turns.add(ChatState.Turn("assistant", message, true))
                onEvent(TurnEvent.Failed(message, canRetry = streamEx?.isRetryable ?: true))
            }
        }
    }

    /** Axın bitdikdən sonra tam tarixçəni serverdə saxla (spec §5 sonu). */
    private fun persist(
        state: ChatState,
        model: ZaiModel,
        effort: String,
        onEvent: (TurnEvent) -> Unit,
    ) {
        val history = state.historyTurns()
        if (history.size < 2) return
        try {
            val obj = ChatObjects.updatedChat(
                model = model.id,
                modelName = model.name,
                turns = history,
                userMsgId = newId(),
                assistantMsgId = newId(),
                reasoningEffort = effort,
                ts = System.currentTimeMillis(),
            )
            chatsApi.update(session.token, state.chatId ?: return, obj)
        } catch (e: ZaiException) {
            // update xətası ölümcül deyil: axın artıq gəlib; növbəti dönüş
            // tam tarixçəni yenidən göndərir. Sükutla keçirik.
        }
    }

    companion object {
        const val INNER_ATTEMPTS = 4
        const val MAX_RESENDS = 3
        fun newId(): String = UUID.randomUUID().toString()
    }
}
