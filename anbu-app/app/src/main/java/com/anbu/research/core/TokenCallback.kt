package com.anbu.research.core

/**
 * Receives streaming tokens from the native layer.
 *
 * The C++ side resolves [onToken]/[onComplete]/[onError] via reflection, so the
 * exact class name and these exact method names are part of the JNI contract —
 * do not rename. Keep in the proguard keep-rules (app/proguard-rules.pro).
 *
 * All three callbacks arrive on the coroutine thread that launched
 * [NativeLib.streamInference] (Dispatchers.IO), so they must hop to Main before
 * touching UI state.
 */
interface TokenCallback {
    /** A decoded, UTF-8-safe text fragment. May be a single token or a few. */
    fun onToken(text: String)

    /** Generation finished; `stats` is engine metadata like "model=... ctx=...". */
    fun onComplete(stats: String)

    /** Generation or setup failed; `message` is human-readable. */
    fun onError(message: String)
}
