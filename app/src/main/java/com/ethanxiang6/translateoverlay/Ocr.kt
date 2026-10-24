package com.ethanxiang6.translateoverlay

import android.graphics.Bitmap
import android.graphics.RectF
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await

data class OcrBlock(val rect: RectF, val text: String)

class OcrEngine {
    private val recognizers = HashMap<String, TextRecognizer>()

    private fun recognizer(script: String): TextRecognizer =
        synchronized(recognizers) {
            recognizers.getOrPut(script) {
                when (script) {
                    "chinese" -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                    "japanese" -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
                    "korean" -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
                    "devanagari" -> TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
                    else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                }
            }
        }

    /** Script to use for a fixed source language, null when the source is auto. */
    private fun scriptFor(source: String): String? = when (source) {
        "auto" -> null
        TranslateLanguage.CHINESE -> "chinese"
        TranslateLanguage.JAPANESE -> "japanese"
        TranslateLanguage.KOREAN -> "korean"
        TranslateLanguage.HINDI -> "devanagari"
        else -> "latin"
    }

    suspend fun scan(bitmap: Bitmap, source: String, ocrChoice: String): List<OcrBlock> {
        val scripts: List<String> = scriptFor(source)?.let { listOf(it) }
            ?: if (ocrChoice == "auto") listOf("latin", "chinese", "japanese", "korean") else listOf(ocrChoice)

        val image = InputImage.fromBitmap(bitmap, 0)
        val all = coroutineScope {
            scripts.map { s ->
                async {
                    try {
                        val result = recognizer(s).process(image).await()
                        result.textBlocks.mapNotNull { b ->
                            val bb = b.boundingBox ?: return@mapNotNull null
                            val joined = b.lines.joinToString(" ") { it.text }.trim()
                            if (joined.isEmpty()) null else OcrBlock(RectF(bb), joined)
                        }
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
            }.flatMap { it.await() }
        }
        return dedupe(all)
    }

    /** When several recognizers see the same area, keep the one that read the most text. */
    private fun dedupe(blocks: List<OcrBlock>): List<OcrBlock> {
        if (blocks.size < 2) return blocks
        val sorted = blocks.sortedByDescending { it.text.length }
        val kept = ArrayList<OcrBlock>()
        for (b in sorted) {
            if (kept.none { overlapRatio(it.rect, b.rect) > 0.5f }) kept.add(b)
        }
        return kept
    }

    fun close() {
        synchronized(recognizers) {
            recognizers.values.forEach { it.close() }
            recognizers.clear()
        }
    }
}

/** Intersection area divided by the smaller rectangle's area. */
fun overlapRatio(a: RectF, b: RectF): Float {
    val l = maxOf(a.left, b.left)
    val t = maxOf(a.top, b.top)
    val r = minOf(a.right, b.right)
    val bt = minOf(a.bottom, b.bottom)
    if (r <= l || bt <= t) return 0f
    val inter = (r - l) * (bt - t)
    val smaller = minOf(a.width() * a.height(), b.width() * b.height())
    return if (smaller <= 0f) 0f else inter / smaller
}
