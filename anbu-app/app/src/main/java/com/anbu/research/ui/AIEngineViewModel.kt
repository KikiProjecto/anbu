package com.anbu.research.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anbu.research.core.AIEngineManager
import com.anbu.research.core.RAGHit
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * One ViewModel for the whole app — the engine is a process-wide singleton, so
 * sharing it across screens is simpler than scoping it per-composable.
 */
class AIEngineViewModel : ViewModel() {

    private val engine = AIEngineManager()

    // ---- init --------------------------------------------------------------
    val initPhase: StateFlow<AIEngineManager.Phase> get() = engine.phase
    val initError: StateFlow<String?> get() = engine.initError

    // ---- chat state ---------------------------------------------------------
    enum class ChatStatus { IDLE, SEARCHING, GENERATING, DONE, STOPPED, ERROR }

    private val _chatStatus = MutableStateFlow(ChatStatus.IDLE)
    val chatStatus: StateFlow<ChatStatus> = _chatStatus.asStateFlow()

    private val _sources = MutableStateFlow<List<RAGHit>>(emptyList())
    val sources: StateFlow<List<RAGHit>> = _sources.asStateFlow()

    private val _answer = MutableStateFlow("")
    val answer: StateFlow<String> = _answer.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var genJob: Job? = null
    private var currentQuery: String? = null

    /**
     * Bumped by every ask()/stop()/resetChat(). Engine::generate is a blocking JNI
     * call, so cancelling genJob does NOT stop the native callback loop — late
     * tokens keep arriving after a stop or a supersede. Each request captures its
     * epoch and drops callbacks that no longer own the screen.
     */
    @Volatile
    private var requestEpoch = 0

    private companion object {
        /** ~12 Hz. The decode loop emits far faster; one StateFlow write per token
         *  recomposes ChatScreen and re-parses the whole answer in MarkdownText.
         *  ponytail: still O(n^2) over a long answer — if that shows up in profiles,
         *  parse only settled blocks in MarkdownText instead. */
        const val FLUSH_INTERVAL_NS = 80_000_000L
    }

    fun initialize(modelPath: String, dbPath: String, indexPath: String) {
        viewModelScope.launch { engine.initialize(modelPath, dbPath, indexPath) }
    }

    fun ask(query: String) {
        // ChatScreen drives this from LaunchedEffect(query), which re-runs on any
        // fresh composition — rotation, split-screen resize, dark-mode toggle,
        // font-size change. The guard has to cover a *finished* turn, not just an
        // active one: keyed on genJob.isActive alone, rotating after the answer
        // landed replays retrieval and generation from scratch and wipes the
        // answer off the screen. Same query text is the same request either way,
        // so it is always a no-op; a different query still supersedes.
        if (query == currentQuery) return
        currentQuery = query

        // One query at a time; a second question supersedes the first. Request
        // the native stop now (lock-free, observed by the decode loop), then
        // join the old job inside the new one before touching any UI state:
        // cancel() cannot interrupt the blocking JNI call, so resetting first
        // would let the dying generation's tokens land in the new answer.
        //
        // Best-effort, not a guarantee: Engine::generate clears the flag on entry
        // (anbu_engine.cpp:176), so if the superseded job is still in retrieval
        // this stop is swallowed and the new query waits out its full generation.
        // Closing that needs an epoch in the native layer — not worth it for a
        // window that retrieval (FTS + HNSW, in-process) closes in milliseconds.
        val previous = genJob
        engine.stop()

        val epoch = ++requestEpoch
        // Per-request, so a superseded request's late callbacks write into an
        // orphaned buffer no one reads, instead of racing the live one.
        val buffer = StringBuilder()
        var lastFlush = 0L

        fun flush(force: Boolean) {
            val now = System.nanoTime()
            if (!force && now - lastFlush < FLUSH_INTERVAL_NS) return
            lastFlush = now
            _answer.value = buffer.toString()
        }

        genJob = viewModelScope.launch {
            previous?.cancelAndJoin()
            if (epoch != requestEpoch) return@launch

            buffer.clear()
            _answer.value = ""
            _error.value = null
            _sources.value = emptyList()
            _chatStatus.value = ChatStatus.SEARCHING

            engine.streamAnswer(
                query = query,
                topK = 6,
                onSources = { hits -> if (epoch == requestEpoch) _sources.value = hits },
                onToken = { token ->
                    if (epoch == requestEpoch) {
                        buffer.append(token)
                        flush(force = false)
                        if (_chatStatus.value == ChatStatus.SEARCHING) {
                            _chatStatus.value = ChatStatus.GENERATING
                        }
                    }
                },
                onComplete = {
                    if (epoch == requestEpoch) {
                        flush(force = true)
                        _chatStatus.value = ChatStatus.DONE
                    }
                },
                onError = { msg ->
                    if (epoch == requestEpoch) {
                        flush(force = true)
                        _error.value = msg
                        _chatStatus.value = ChatStatus.ERROR
                    }
                },
            )
        }
    }

    fun stop() {
        engine.stop()
        requestEpoch++  // orphans the in-flight callbacks; the JNI loop outlives cancel()
        genJob?.cancel()
        // SEARCHING too: retrieval on a cold SQLite page cache is not instant,
        // and stopping there used to leave the spinner up forever.
        if (_chatStatus.value == ChatStatus.SEARCHING ||
            _chatStatus.value == ChatStatus.GENERATING
        ) {
            _chatStatus.value = ChatStatus.STOPPED
        }
    }

    fun resetChat() {
        // Drop the query key too, or re-asking the same question in a fresh
        // session would be swallowed by the guard in ask().
        currentQuery = null
        requestEpoch++
        genJob?.cancel()
        _answer.value = ""
        _sources.value = emptyList()
        _error.value = null
        _chatStatus.value = ChatStatus.IDLE
    }

    override fun onCleared() {
        // shutdown() waits on Engine::mu_, which a running generate holds for
        // the whole turn — tens of seconds on a mmap'd MoE. Never on Main.
        engine.stop()
        Thread { engine.shutdown() }.start()
        super.onCleared()
    }
}
