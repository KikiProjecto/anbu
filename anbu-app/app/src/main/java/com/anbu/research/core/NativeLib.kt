package com.anbu.research.core

/**
 * JNI declarations. Method names encode the fully-qualified class in the C++
 * symbol, so this object's package/class/name must stay `com.anbu.research.core.NativeLib`
 * and the function names must stay identical — the native side matches them by
 * mangled name in native-lib.cpp.
 */
object NativeLib {
    init {
        load()
    }

    private var loaded = false

    @Synchronized
    fun load() {
        if (loaded) return
        System.loadLibrary("anbu")
        loaded = true
    }

    /** Loads MoE model (mmap=true, mlock=false), SQLite FTS5 DB, and USearch index. */
    external fun initEngine(
        modelPath: String,
        dbPath: String,
        indexPath: String,
        nCtx: Int = 4096,
        nThreads: Int = 0,
    ): Boolean

    /** Last error string from a failed init/search/generate, for the UI. */
    external fun lastError(): String

    /** "model=... n_params=... size_mb=... n_ctx=... index_vectors=..." */
    external fun systemInfo(): String

    /** Hybrid RAG search; returns a JSON array of {id,title,source,snippet,score}. */
    external fun searchContext(query: String, topK: Int): String

    /**
     * Wraps system + user turns in the GGUF's own chat template. Falls back to
     * plain concatenation when the model has no template, so callers always get
     * a usable prompt back.
     */
    external fun formatChat(systemPrompt: String, userPrompt: String): String

    /** Streams LLM output token-by-token into [callback] (call on Dispatchers.IO). */
    external fun streamInference(prompt: String, callback: TokenCallback)

    /** Requests the running generation to stop at the next token boundary. */
    external fun stopInference()

    /** Unloads model + closes DB/index. Call when the app is going away. */
    external fun shutdown()
}
