package com.ethanxiang6.translateoverlay

import android.content.Context
import android.content.SharedPreferences
import com.google.mlkit.nl.translate.TranslateLanguage

/** All settings live here and are edited only from the app screen. */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** ML Kit language tag, or "auto" to detect per text block. */
    var sourceLang: String
        get() = sp.getString("source", "auto") ?: "auto"
        set(v) = sp.edit().putString("source", v).apply()

    var targetLang: String
        get() = sp.getString("target", TranslateLanguage.ENGLISH) ?: TranslateLanguage.ENGLISH
        set(v) = sp.edit().putString("target", v).apply()

    /** Which OCR script to run: auto runs Latin plus the CJK recognizers. */
    var ocrScript: String
        get() = sp.getString("ocr", "auto") ?: "auto"
        set(v) = sp.edit().putString("ocr", v).apply()

    /** Overlay box opacity, 0.3 to 1.0. */
    var boxOpacity: Float
        get() = sp.getFloat("opacity", 0.85f)
        set(v) = sp.edit().putFloat("opacity", v).apply()

    /** Multiplier on the automatically fitted text size. */
    var textScale: Float
        get() = sp.getFloat("textScale", 1.0f)
        set(v) = sp.edit().putFloat("textScale", v).apply()

    /** Target scans per second. */
    var scansPerSecond: Int
        get() = sp.getInt("sps", 6)
        set(v) = sp.edit().putInt("sps", v).apply()

    /** Dark boxes with light text when true, light boxes with dark text when false. */
    var darkBoxes: Boolean
        get() = sp.getBoolean("dark", true)
        set(v) = sp.edit().putBoolean("dark", v).apply()

    /** Only translate text taller than this many dp, filters tiny UI noise. */
    var minTextDp: Int
        get() = sp.getInt("minDp", 8)
        set(v) = sp.edit().putInt("minDp", v).apply()
}

object Languages {
    /** Display name to ML Kit language tag. */
    val all: List<Pair<String, String>> = listOf(
        "English" to TranslateLanguage.ENGLISH,
        "Chinese" to TranslateLanguage.CHINESE,
        "Japanese" to TranslateLanguage.JAPANESE,
        "Korean" to TranslateLanguage.KOREAN,
        "Spanish" to TranslateLanguage.SPANISH,
        "French" to TranslateLanguage.FRENCH,
        "German" to TranslateLanguage.GERMAN,
        "Italian" to TranslateLanguage.ITALIAN,
        "Portuguese" to TranslateLanguage.PORTUGUESE,
        "Russian" to TranslateLanguage.RUSSIAN,
        "Arabic" to TranslateLanguage.ARABIC,
        "Hindi" to TranslateLanguage.HINDI,
        "Indonesian" to TranslateLanguage.INDONESIAN,
        "Thai" to TranslateLanguage.THAI,
        "Vietnamese" to TranslateLanguage.VIETNAMESE,
        "Turkish" to TranslateLanguage.TURKISH,
        "Polish" to TranslateLanguage.POLISH,
        "Dutch" to TranslateLanguage.DUTCH,
        "Ukrainian" to TranslateLanguage.UKRAINIAN,
    )

    fun nameOf(tag: String): String =
        if (tag == "auto") "Auto detect" else all.firstOrNull { it.second == tag }?.first ?: tag
}
