package com.ethanxiang6.translateoverlay

import android.util.LruCache
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.concurrent.ConcurrentHashMap

/**
 * On-device translation with a result cache so repeated text is instant.
 * Models download once, after that nothing needs a network connection.
 */
class TextTranslator {
    private val clients = ConcurrentHashMap<String, Translator>()
    private val ready = ConcurrentHashMap.newKeySet<String>()
    private val cache = LruCache<String, String>(4000)
    private val langId = LanguageIdentification.getClient()

    private fun client(src: String, dst: String): Translator =
        clients.getOrPut("$src>$dst") {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(src)
                    .setTargetLanguage(dst)
                    .build()
            )
        }

    /** Download the model pair if needed. Returns true when ready to translate offline. */
    suspend fun prepare(src: String, dst: String): Boolean {
        if (src == dst) return true
        val key = "$src>$dst"
        if (ready.contains(key)) return true
        return try {
            client(src, dst).downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            ready.add(key)
            true
        } catch (e: Exception) {
            false
        }
    }

    private val downloading = ConcurrentHashMap.newKeySet<String>()
    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun startDownload(src: String, dst: String) {
        val key = "$src>$dst"
        if (!downloading.add(key)) return
        downloadScope.launch {
            try {
                prepare(src, dst)
            } finally {
                downloading.remove(key)
            }
        }
    }

    /** Number of model downloads still running. */
    fun pending(): Int = downloading.size

    /** Detect the ML Kit translate tag of a piece of text, null when unknown or unsupported. */
    suspend fun detect(text: String): String? {
        return try {
            val code = langId.identifyLanguage(text).await()
            if (code == "und") null else TranslateLanguage.fromLanguageTag(code)
        } catch (e: Exception) {
            null
        }
    }

    /** Cached lookup that never blocks, used to paint immediately. */
    fun cached(text: String, dst: String): String? = cache.get("$dst|$text")

    /**
     * Translate text. Returns null when the text is already in the target language,
     * the source can't be determined, or the model isn't downloaded yet.
     */
    suspend fun translate(text: String, sourcePref: String, dst: String): String? {
        val hit = cache.get("$dst|$text")
        if (hit != null) return hit.ifEmpty { null }

        val src = if (sourcePref == "auto") detect(text) else sourcePref
        if (src == null || src == dst) {
            cache.put("$dst|$text", "")
            return null
        }
        if (!ready.contains("$src>$dst")) {
            // Don't stall the scan loop on a download. Fetch in the background and
            // let the caller retry once pending() drops back to zero.
            startDownload(src, dst)
            return null
        }
        return try {
            val out = client(src, dst).translate(text).await()
            cache.put("$dst|$text", out)
            out
        } catch (e: Exception) {
            null
        }
    }

    fun close() {
        downloadScope.cancel()
        clients.values.forEach { it.close() }
        clients.clear()
        langId.close()
    }
}
