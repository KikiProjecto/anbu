package com.anbu.research.core

import android.content.Context
import android.os.Environment
import java.io.File

/** One retrieved knowledge-base chunk, parsed from searchContext() JSON. */
data class RAGHit(
    val id: Long,
    val title: String,
    val source: String,
    val snippet: String,
    val score: Double,
)

/**
 * Where the model + corpus live on-device. getExternalFilesDir() is app-scoped
 * storage that needs no runtime permission and is cleaned on uninstall — but on
 * Android 12+ it is NOT counted against the app's quota, so a 25GB GGUF sits in
 * real user storage (the "50GB budget" in the PRD).
 *
 * Drop files here with `adb push` or a first-run file picker:
 *   model.gguf, kb.db, kb.usearch
 */
object ModelPaths {
    fun resolve(context: Context): Triple<String, String, String> {
        val dir = context.getExternalFilesDir(null)
            ?: File(context.filesDir, "anbu-data").apply { mkdirs() }
        return Triple(
            File(dir, "model.gguf").absolutePath,
            File(dir, "kb.db").absolutePath,
            File(dir, "kb.usearch").absolutePath,
        )
    }

    /** Absence check for the init screen's error message. */
    fun missing(context: Context): List<String> {
        val (m, db, idx) = resolve(context)
        val names = listOf("model.gguf", "kb.db", "kb.usearch")
        val paths = listOf(m, db, idx)
        return names.zip(paths).filter { !File(it.second).exists() }.map { it.first }
    }
}
