package com.ethanxiang6.translateoverlay

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper

/**
 * Mirrors the screen into an ImageReader at a reduced size and hands out the newest frame
 * as a bitmap. The capture runs at most [maxLongSide] pixels on the long edge.
 */
class ScreenGrabber(
    private val projection: MediaProjection,
    private val densityDpi: Int,
    private val maxLongSide: Int = 1600,
) {
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var bitmap: Bitmap? = null
    private var last: Bitmap? = null

    var width = 0
        private set
    var height = 0
        private set

    private val stopCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            release()
        }
    }

    /** Create or resize the capture for the given real screen size. */
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
        bitmap?.recycle()
        bitmap = null
        width = w
        height = h
    }

    /** Newest frame as a bitmap, or null when no new frame arrived yet. The bitmap is reused. */
    @Synchronized
    fun grab(): Bitmap? {
        val r = reader ?: return null
        val image = try {
            r.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return null
        try {
            val plane = image.planes[0]
            val rowPixels = plane.rowStride / plane.pixelStride
            var bmp = bitmap
            if (bmp == null || bmp.width != rowPixels || bmp.height != image.height) {
                bmp?.recycle()
                bmp = Bitmap.createBitmap(rowPixels, image.height, Bitmap.Config.ARGB_8888)
                bitmap = bmp
            }
            plane.buffer.rewind()
            bmp!!.copyPixelsFromBuffer(plane.buffer)
            // The bitmap can be wider than the screen because of row padding, crop logically.
            val out = if (rowPixels == image.width) bmp
            else Bitmap.createBitmap(bmp, 0, 0, image.width, image.height)
            last = out
            return out
        } finally {
            image.close()
        }
    }

    /** The most recent frame returned by [grab], used to rescan a screen that hasn't changed. */
    @Synchronized
    fun peek(): Bitmap? = last?.takeIf { !it.isRecycled }

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
        bitmap?.recycle()
        bitmap = null
        width = 0
        height = 0
    }
}
