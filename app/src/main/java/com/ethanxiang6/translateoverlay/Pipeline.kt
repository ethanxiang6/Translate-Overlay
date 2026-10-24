package com.ethanxiang6.translateoverlay

import android.content.Context
import android.graphics.RectF
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Keeps translated boxes glued to the text on screen.
 *
 * A fast tracker looks at every captured frame, works out how far the content scrolled and
 * moves the boxes by the same amount. Slower OCR scans run in the background, they only
 * add text that has just scrolled into view, so the boxes never disappear and reappear.
 *
 * All state lives on one thread, so no locking is needed.
 */
class Pipeline(
    private val context: Context,
    private val grabber: ScreenGrabber,
    private val overlay: OverlayView,
    private val prefs: Prefs,
    private val translator: TextTranslator,
    private val ocr: OcrEngine,
    private val realSize: () -> Pair<Int, Int>,
    private val origin: () -> Pair<Int, Int>,
) {
    private class Tracked(val rect: RectF, val original: String, val translated: String)

    private val executor = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())

    private var tracked = ArrayList<Tracked>()
    private val history = ArrayDeque<List<RectF>>()
    private var cumY = 0f
    private var generation = 0
    private var scanJob: Job? = null

    fun start() {
        scope.launch { trackLoop() }
    }

    fun stop() {
        scope.cancel()
        executor.shutdownNow()
    }

    // ---- tracker ----

    private suspend fun trackLoop() {
        var lastScreen = 0 to 0
        var lastSettings = ""
        var lastMotion = 0L
        var lastPartial = 0L
        var needFull = true
        var forceRefresh = false
        var driftPx = 0
        var prevD = 0
        var lastPending = 0

        while (scope.isActive) {
            val now = SystemClock.uptimeMillis()

            val (sw, sh) = realSize()
            val settings = "${prefs.sourceLang}|${prefs.targetLang}|${prefs.ocrScript}|${prefs.minTextDp}"
            if ((sw to sh) != lastScreen || settings != lastSettings) {
                lastScreen = sw to sh
                lastSettings = settings
                grabber.ensureSize(sw, sh)
                resetBoxes()
                needFull = true
                driftPx = 0
            }

            if (grabber.grabLuma() && grabber.hasPrev) {
                val rows = grabber.height
                val cols = grabber.cols
                if (tracked.isNotEmpty()) {
                    val r = Motion.estimate(
                        grabber.lumaPrev, grabber.lumaCur, rows, cols, buildMask(rows, cols), prevD,
                    )
                    when {
                        r.lost -> {
                            resetBoxes()
                            needFull = true
                            driftPx = 0
                            prevD = 0
                        }
                        r.dy != 0 -> {
                            moveBoxes(r.dy)
                            lastMotion = now
                            driftPx += abs(r.dy)
                            prevD = r.dy
                        }
                        else -> prevD = 0
                    }
                } else if (Motion.frameDiff(grabber.lumaPrev, grabber.lumaCur, rows, cols) > 3f) {
                    needFull = true
                }
            }

            val pending = translator.pending()
            if (lastPending > 0 && pending == 0) {
                forceRefresh = true
                needFull = true
            }
            lastPending = pending

            if (scanJob?.isActive != true) {
                val moving = now - lastMotion < 160
                when {
                    tracked.isEmpty() && needFull -> {
                        needFull = false
                        scanJob = scope.launch { runScan(hide = false, partial = false) }
                    }
                    tracked.isNotEmpty() && !moving && (forceRefresh || driftPx > 4000) -> {
                        forceRefresh = false
                        needFull = false
                        driftPx = 0
                        scanJob = scope.launch { runScan(hide = true, partial = false) }
                    }
                    tracked.isNotEmpty() && !moving && lastPartial < lastMotion + 160 -> {
                        // Scrolling just stopped, pick up anything the partial scans missed.
                        lastPartial = now
                        scanJob = scope.launch { runScan(hide = false, partial = true) }
                    }
                    tracked.isNotEmpty() && moving && now - lastPartial > 280 -> {
                        lastPartial = now
                        scanJob = scope.launch { runScan(hide = false, partial = true) }
                    }
                }
            }
            delay(8)
        }
    }

    private fun moveBoxes(dy: Int) {
        cumY += dy
        val h = grabber.height
        val it = tracked.iterator()
        while (it.hasNext()) {
            val t = it.next()
            t.rect.offset(0f, dy.toFloat())
            if (t.rect.bottom < -200f || t.rect.top > h + 200f) it.remove()
        }
        remember()
        val (sw, _) = realSize()
        val k = sw.toFloat() / grabber.width
        overlay.post { overlay.shift(dy * k) }
    }

    private fun remember() {
        history.addLast(tracked.map { RectF(it.rect) })
        while (history.size > 4) history.removeFirst()
    }

    private fun resetBoxes() {
        generation++
        tracked = ArrayList()
        history.clear()
        overlay.post { overlay.setBoxes(emptyList()) }
    }

    /** Cells the overlay boxes were drawn over in recent frames, ignored when matching frames. */
    private fun buildMask(rows: Int, cols: Int): BooleanArray {
        val coarseRows = (rows + 3) / 4
        val mask = BooleanArray(coarseRows * cols)
        val pad = 8f
        fun mark(r: RectF) {
            val c0 = ((r.left - pad) / 4f).toInt().coerceIn(0, cols - 1)
            val c1 = ((r.right + pad) / 4f).toInt().coerceIn(0, cols - 1)
            val y0 = (((r.top - pad) / 4f).toInt()).coerceIn(0, coarseRows - 1)
            val y1 = (((r.bottom + pad) / 4f).toInt()).coerceIn(0, coarseRows - 1)
            for (y in y0..y1) for (c in c0..c1) mask[y * cols + c] = true
        }
        for (set in history) for (r in set) mark(r)
        for (t in tracked) mark(t.rect)
        return mask
    }

    // ---- scanning ----

    private suspend fun runScan(hide: Boolean, partial: Boolean) {
        val gen = generation
        if (hide) {
            val c0 = grabber.frameCounter
            val t0 = SystemClock.uptimeMillis()
            overlay.post { overlay.alpha = 0f }
            while (true) {
                val waited = SystemClock.uptimeMillis() - t0
                if ((grabber.frameCounter - c0 >= 2 && waited >= 60) || waited >= 250) break
                delay(8)
            }
        }
        val bmp = grabber.snapshot()
        val snapY = cumY
        if (hide) overlay.post { overlay.alpha = 1f }
        if (bmp == null) return

        val src = prefs.sourceLang
        val dst = prefs.targetLang
        val (sw, _) = realSize()
        val capScale = grabber.width.toFloat() / sw
        val minPx = prefs.minTextDp * context.resources.displayMetrics.density * capScale

        var blocks = try {
            ocr.scan(bmp, src, prefs.ocrScript)
        } finally {
            bmp.recycle()
        }
        blocks = blocks.filter { b ->
            b.rect.height() >= minPx && b.text.length >= 2 && b.text.any { it.isLetterOrDigit() }
        }
        if (partial) {
            val d = cumY - snapY
            blocks = blocks.filter { !coveredByTracked(it.rect, d) }
        }

        val translated = coroutineScope {
            blocks.map { b -> async { translator.translate(b.text, src, dst) } }.map { it.await() }
        }

        // No suspension from here on, so the shift since the snapshot is exact.
        if (gen != generation) return
        val delta = cumY - snapY
        val fresh = ArrayList<Tracked>()
        for (i in blocks.indices) {
            val out = translated[i] ?: continue
            if (out.isBlank() || out.equals(blocks[i].text, ignoreCase = true)) continue
            val r = RectF(blocks[i].rect)
            r.offset(0f, delta)
            if (partial && tracked.any { overlapRatio(it.rect, r) > 0.3f }) continue
            fresh.add(Tracked(r, blocks[i].text, out))
        }

        if (partial) {
            if (fresh.isEmpty()) return
            tracked.addAll(fresh)
        } else {
            tracked = fresh
        }
        remember()
        pushBoxes()
    }

    private fun coveredByTracked(blockRect: RectF, delta: Float): Boolean {
        val r = RectF(blockRect)
        r.offset(0f, delta)
        return tracked.any { overlapRatio(it.rect, r) > 0.3f }
    }

    private fun pushBoxes() {
        val (sw, _) = realSize()
        val k = sw.toFloat() / grabber.width
        val (ox, oy) = origin()
        val list = tracked.map {
            TextBox(
                RectF(it.rect.left * k - ox, it.rect.top * k - oy, it.rect.right * k - ox, it.rect.bottom * k - oy),
                it.original, it.translated,
            )
        }
        val opacity = prefs.boxOpacity
        val scale = prefs.textScale
        val dark = prefs.darkBoxes
        overlay.post {
            overlay.applyStyle(opacity, scale, dark)
            overlay.setBoxes(list)
        }
    }
}
