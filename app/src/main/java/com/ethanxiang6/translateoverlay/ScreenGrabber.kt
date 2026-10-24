package com.ethanxiang6.translateoverlay

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import java.nio.ByteBuffer

/**
 * Mirrors the screen into an ImageReader at a reduced size. Every new frame is reduced to a
 * luma grid (every 4th pixel horizontally, every row) for cheap motion tracking, and the raw
 * pixels are kept so a full bitmap can be made on demand for OCR.
 */
class ScreenGrabber(
    private val projection: MediaProjection,
    private val densityDpi: Int,
    private val maxLongSide: Int = 1600,
) {
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null

    var width = 0
        private set
    var height = 0
        private set

    /** Luma columns per row, one per 4 pixels. */
    var cols = 0
        private set

    var lumaCur = ByteArray(0)
        private set
    var lumaPrev = ByteArray(0)
        private set

    @Volatile var frameCounter = 0L
        private set

    private var framesSinceResize = 0

    /** True when both [lumaPrev] and [lumaCur] hold real consecutive frames. */
    val hasPrev: Boolean get() = framesSinceResize >= 2

    private var raw = ByteArray(0)
    private var rawRowStride = 0
    private var rawPixelStride = 4

    private val stopCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            release()
        }
    }

    @Synchronized
    fun ensureSize(screenW: Int, screenH: Int) {
        val scale = minOf(1f, maxLongSide.toFloat() / maxOf(screenW, screenH))
        val w = (screenW * scale).toInt().coerceAtLeast(2)
        val h = (screenH * scale).toInt().coerceAtLeast(2)
        if (w == width && h == height && display != null) return

        val newReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        val dpi = (densityDpi * scale).toInt().coerceAtLeast(72)
        if (display == null) {
            projection.registerCallback(stopCallback, Handler(Looper.getMainLooper()))
            display = projection.createVirtualDisplay(
                "translate-overlay", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                newReader.surface, null, null,
            )
        } else {
            display?.resize(w, h, dpi)
            display?.surface = newReader.surface
        }
        reader?.close()
        reader = newReader
        width = w
        height = h
        cols = (w + 3) / 4
        lumaCur = ByteArray(h * cols)
        lumaPrev = ByteArray(h * cols)
        framesSinceResize = 0
        raw = ByteArray(0)
        rawRowStride = 0
    }

    /** Take the newest frame if there is one. Returns false when nothing new arrived. */
    @Synchronized
    fun grabLuma(): Boolean {
        val r = reader ?: return false
        val image = try {
            r.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return false
        try {
            val plane = image.planes[0]
            val buf = plane.buffer
            val rs = plane.rowStride
            val ps = plane.pixelStride
            val h = image.height

            val t = lumaPrev
            lumaPrev = lumaCur
            lumaCur = t
            val step = 4 * ps
            for (y in 0 until h) {
                val base = y * rs
                val out = y * cols
                for (c in 0 until cols) {
                    val i = base + c * step
                    val rr = buf.get(i).toInt() and 0xFF
                    val gg = buf.get(i + 1).toInt() and 0xFF
                    val bb = buf.get(i + 2).toInt() and 0xFF
                    lumaCur[out + c] = ((rr + 2 * gg + bb) shr 2).toByte()
                }
            }

            val need = rs * h
            if (raw.size < need) raw = ByteArray(need)
            buf.rewind()
            buf.get(raw, 0, minOf(buf.remaining(), need))
            rawRowStride = rs
            rawPixelStride = ps

            framesSinceResize++
            frameCounter++
            return true
        } finally {
            image.close()
        }
    }

    /** A fresh bitmap of the latest frame for OCR. The caller recycles it. */
    @Synchronized
    fun snapshot(): Bitmap? {
        if (rawRowStride == 0 || width == 0) return null
        val rowPixels = rawRowStride / rawPixelStride
        val full = Bitmap.createBitmap(rowPixels, height, Bitmap.Config.ARGB_8888)
        full.copyPixelsFromBuffer(ByteBuffer.wrap(raw, 0, rawRowStride * height))
        if (rowPixels == width) return full
        val cropped = Bitmap.createBitmap(full, 0, 0, width, height)
        if (cropped !== full) full.recycle()
        return cropped
    }

    @Synchronized
    fun release() {
        try {
            projection.unregisterCallback(stopCallback)
        } catch (_: Exception) {
        }
        display?.release()
        display = null
        reader?.close()
        reader = null
        width = 0
        height = 0
    }
}
