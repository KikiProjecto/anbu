package com.anbu.research.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Single source of truth for the native backend. Owns the AIEngineViewModel's
 * state and routes every native call through Dispatchers.IO so the UI thread
 * never blocks on mmap page-ins or a multi-GB token decode.
 */
class AIEngineManager {

    enum class Phase { UNINITIALIZED, LOADING, READY, FAILED }

    private val _phase = MutableStateFlow(Phase.UNINITIALIZED)
    val phase: StateFlow<Phase> = _phase

    private val _initError = MutableStateFlow<String?>(null)
    val initError: StateFlow<String?> = _initError

    val systemInfo: String
        get() = NativeLib.systemInfo()

    suspend fun initialize(modelPath: String, dbPath: String, indexPath: String): Boolean =
        withContext(Dispatchers.IO) {
            _phase.value = Phase.LOADING
            _initError.value = null
            // Retrieval opens first inside initEngine; model loads after. This
            // blocks for the full mmap+context allocation, which is the point:
            // the splash screen shows a spinner while it pages weights in.
            val ok = NativeLib.initEngine(modelPath, dbPath, indexPath)
            if (ok) {
                _phase.value = Phase.READY
            } else {
                _phase.value = Phase.FAILED
                _initError.value = NativeLib.lastError()
            }
            ok
        }

    suspend fun search(query: String, topK: Int): List<RAGHit> =
        withContext(Dispatchers.IO) {
            if (_phase.value != Phase.READY) return@withContext emptyList()
            val json = NativeLib.searchContext(query, topK)
            parseHits(json)
        }

    /**
     * Runs RAG retrieval, then streams the synthesised answer token-by-token.
     * [onSources] fires as soon as retrieval finishes — before the model has
     * produced a single token — so the UI can show what was found while the
     * long prompt eval runs. All callbacks fire on the caller's coroutine
     * context; collect them into a StateFlow on Main via `withContext`/flow.
     */
    suspend fun streamAnswer(
        query: String,
        topK: Int,
        onSources: (List<RAGHit>) -> Unit,
        onToken: (String) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit,
    ): List<RAGHit> = withContext(Dispatchers.IO) {
        val hits = search(query, topK)
        onSources(hits)

        val prompt = NativeLib.formatChat(systemPrompt(), buildUserPrompt(query, hits))

        NativeLib.streamInference(prompt, object : TokenCallback {
            override fun onToken(text: String) = onToken(text)
            override fun onComplete(stats: String) = onComplete()
            override fun onError(message: String) = onError(message)
        })
        hits
    }

    fun stop() = NativeLib.stopInference()

    fun shutdown() = NativeLib.shutdown()

    // ---- prompt engineering ------------------------------------------------

    private fun systemPrompt(): String = buildString {
        append("You are an offline research assistant. Answer using ONLY the ")
        append("retrieved passages below. If they do not contain the answer, say ")
        append("you could not find it in the local knowledge base — do not guess.")
    }

    private fun buildUserPrompt(query: String, hits: List<RAGHit>): String {
        val context = buildString {
            hits.forEachIndexed { i, h ->
                append("[").append(i + 1).append("] ")
                if (h.title.isNotBlank()) append(h.title)
                if (h.source.isNotBlank()) append(" (").append(h.source).append(")")
                append("\n").append(h.snippet).append("\n\n")
            }
        }.trim()

        return buildString {
            if (context.isNotBlank()) {
                append("## Retrieved passages\n").append(context).append("\n\n")
            }
            append("## Question\n").append(query)
        }
    }

    private fun parseHits(json: String): List<RAGHit> {
        if (json.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        RAGHit(
                            id = o.optLong("id"),
                            title = o.optString("title"),
                            source = o.optString("source"),
                            snippet = o.optString("snippet"),
                            score = o.optDouble("score"),
                        )
                    )
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
