package com.ethanxiang6.translateoverlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View

data class TextBox(
    /** Screen-pixel rectangle of the original text. */
    val rect: RectF,
    val original: String,
    val translated: String,
)

/**
 * Draws translated text on top of the original text. The window holding this view is
 * not touchable and not focusable, so every tap goes straight to the app underneath.
 */
class OverlayView(context: Context) : View(context) {
    private var boxes: List<TextBox> = emptyList()
    private var layouts: List<StaticLayout?> = emptyList()

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)

    private var opacity = 0.85f
    private var scale = 1.0f
    private var dark = true

    fun applyStyle(opacity: Float, scale: Float, dark: Boolean) {
        val changed = this.opacity != opacity || this.scale != scale || this.dark != dark
        this.opacity = opacity
        this.scale = scale
        this.dark = dark
        if (changed) {
            rebuild()
            invalidate()
        }
    }

    /** Vertical offset applied when drawing, used to follow scrolling without re-fitting text. */
    private var offsetY = 0f

    fun setBoxes(list: List<TextBox>) {
        boxes = list
        offsetY = 0f
        rebuild()
        invalidate()
    }

    /** Move every box by [dy] screen pixels. Cheap, no text layout work. */
    fun shift(dy: Float) {
        offsetY += dy
        invalidate()
    }

    private fun rebuild() {
        val textColor = if (dark) Color.WHITE else Color.BLACK
        tp.color = textColor
        layouts = boxes.map { b -> fit(b) }
    }

    /** Largest text size that fits the original text's box, with a sane lower bound. */
    private fun fit(b: TextBox): StaticLayout? {
        val w = b.rect.width().toInt().coerceAtLeast(24)
        val h = b.rect.height()
        var lo = 8f
        var hi = (h * 1.1f * scale).coerceAtLeast(10f)
        var best: StaticLayout? = null
        repeat(7) {
            val mid = (lo + hi) / 2f
            tp.textSize = mid
            val l = build(b.translated, w)
            if (l.height <= h * 1.35f) {
                best = l
                lo = mid
            } else {
                hi = mid
            }
        }
        if (best == null) {
            tp.textSize = 8f * scale
            best = build(b.translated, w)
        }
        return best
    }

    private fun build(text: String, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, tp, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()

    override fun onDraw(canvas: Canvas) {
        val base = if (dark) Color.rgb(16, 20, 28) else Color.rgb(250, 250, 250)
        bg.color = base
        bg.alpha = (opacity * 255).toInt().coerceIn(0, 255)
        canvas.save()
        canvas.translate(0f, offsetY)
        for (i in boxes.indices) {
            val b = boxes[i]
            val l = layouts.getOrNull(i) ?: continue
            val pad = 3f
            val top = b.rect.top + (b.rect.height() - l.height) / 2f
            canvas.drawRoundRect(
                b.rect.left - pad, minOf(b.rect.top, top) - pad,
                b.rect.right + pad, maxOf(b.rect.bottom, top + l.height) + pad,
                6f, 6f, bg,
            )
            canvas.save()
            canvas.translate(b.rect.left, top)
            l.draw(canvas)
            canvas.restore()
        }
        canvas.restore()
    }
}
