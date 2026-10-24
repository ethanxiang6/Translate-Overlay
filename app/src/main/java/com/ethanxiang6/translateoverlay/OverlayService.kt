package com.ethanxiang6.translateoverlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.RectF
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OverlayService : Service() {

    companion object {
        const val ACTION_START = "com.ethanxiang6.translateoverlay.START"
        const val ACTION_STOP = "com.ethanxiang6.translateoverlay.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL_ID = "overlay"
        private const val NOTIF_ID = 1
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    private var overlay: OverlayView? = null
    private var projection: MediaProjection? = null
    private var grabber: ScreenGrabber? = null

    private val translator = TextTranslator()
    private val ocr = OcrEngine()

    @Volatile private var originX = 0
    @Volatile private var originY = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START || job != null) return START_NOT_STICKY

        startAsForeground()

        val code = intent.getIntExtra(EXTRA_CODE, 0)
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = Prefs(this)

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = try {
            mpm.getMediaProjection(code, data)
        } catch (e: Exception) {
            null
        }
        if (mp == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        projection = mp
        grabber = ScreenGrabber(mp, resources.displayMetrics.densityDpi)

        addOverlay()
        RunState.set(true)
        job = scope.launch { runLoop() }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, OverlayService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.notif_stop), stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun addOverlay() {
        val view = OverlayView(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        wm.addView(view, lp)
        overlay = view
        view.post {
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            originX = loc[0]
            originY = loc[1]
        }
    }

    @Suppress("DEPRECATION")
    private fun realSize(): Pair<Int, Int> {
        val dm = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)
        return dm.widthPixels to dm.heightPixels
    }

    // ---- scan loop ----

    private class Shown(val boxes: List<TextBox>, val capRects: List<RectF>)

    private suspend fun runLoop() {
        val g = grabber ?: return
        var baseline: IntArray? = null
        var shown = Shown(emptyList(), emptyList())
        var settle = false
        var lastSettings = ""
        var lastScreen = 0 to 0
        var lastRetry = 0L

        while (scope.isActive) {
            val t0 = SystemClock.uptimeMillis()
            val sps = prefs.scansPerSecond.coerceIn(1, 15)
            val interval = 1000L / sps

            val (sw, sh) = realSize()
            val screenChanged = (sw to sh) != lastScreen
            lastScreen = sw to sh
            g.ensureSize(sw, sh)

            val settings = "${prefs.sourceLang}|${prefs.targetLang}|${prefs.ocrScript}|${prefs.minTextDp}"
            val settingsChanged = settings != lastSettings
            lastSettings = settings

            if (screenChanged || settingsChanged) {
                baseline = null
                settle = false
                shown = clearBoxes()
            }

            // A language pack that was downloading may be ready now, rescan the same frame.
            var retry = false
            if (shown.boxes.isEmpty() && translator.pending() > 0) {
                val now = SystemClock.uptimeMillis()
                if (now - lastRetry > 1000) {
                    lastRetry = now
                    retry = true
                    baseline = null
                }
            }

            val frame = g.grab() ?: if (retry || baseline == null) g.peek() else null
            if (frame != null) {
                val sig = signature(frame)
                val base = baseline
                val changed = base == null ||
                    changeFraction(base, sig, frame.width, frame.height, shown.capRects) > 0.015f

                if (shown.boxes.isNotEmpty()) {
                    if (changed) {
                        // Content moved under the boxes, hide them and rescan on a clean frame.
                        shown = clearBoxes()
                        settle = true
                    }
                } else if (settle) {
                    settle = false
                    delay(70)
                    val fresh = g.grab() ?: frame
                    val freshSig = signature(fresh)
                    shown = scan(fresh, sw, sh, freshSig)
                    baseline = freshSig
                } else if (changed) {
                    shown = scan(frame, sw, sh, sig)
                    baseline = sig
                }
            }

            val spent = SystemClock.uptimeMillis() - t0
            delay((interval - spent).coerceAtLeast(1))
        }
    }

    private suspend fun clearBoxes(): Shown {
        withContext(Dispatchers.Main) { overlay?.setBoxes(emptyList()) }
        return Shown(emptyList(), emptyList())
    }

    private suspend fun scan(frame: android.graphics.Bitmap, sw: Int, sh: Int, sig: IntArray): Shown {
        val g = grabber ?: return Shown(emptyList(), emptyList())
        val src = prefs.sourceLang
        val dst = prefs.targetLang
        val capScale = g.width.toFloat() / sw
        val minPx = prefs.minTextDp * resources.displayMetrics.density * capScale

        val blocks = ocr.scan(frame, src, prefs.ocrScript).filter { b ->
            b.rect.height() >= minPx && b.text.any { it.isLetterOrDigit() } && b.text.length >= 2
        }
        if (blocks.isEmpty()) return Shown(emptyList(), emptyList())

        val translated = coroutineScope {
            blocks.map { b -> async { translator.translate(b.text, src, dst) } }.map { it.await() }
        }

        val inv = 1f / capScale
        val boxes = ArrayList<TextBox>()
        val caps = ArrayList<RectF>()
        for (i in blocks.indices) {
            val out = translated[i] ?: continue
            if (out.isBlank() || out.equals(blocks[i].text, ignoreCase = true)) continue
            val r = blocks[i].rect
            val screenRect = RectF(
                r.left * inv - originX, r.top * inv - originY,
                r.right * inv - originX, r.bottom * inv - originY,
            )
            boxes.add(TextBox(screenRect, blocks[i].text, out))
            caps.add(RectF(r))
        }

        withContext(Dispatchers.Main) {
            overlay?.applyStyle(prefs.boxOpacity, prefs.textScale, prefs.darkBoxes)
            overlay?.setBoxes(boxes)
        }
        return Shown(boxes, caps)
    }

    // ---- cheap change detection ----

    private val gridX = 48
    private val gridY = 86

    private fun signature(bmp: android.graphics.Bitmap): IntArray {
        val out = IntArray(gridX * gridY)
        for (y in 0 until gridY) {
            val py = ((y + 0.5f) / gridY * bmp.height).toInt().coerceIn(0, bmp.height - 1)
            for (x in 0 until gridX) {
                val px = ((x + 0.5f) / gridX * bmp.width).toInt().coerceIn(0, bmp.width - 1)
                val c = bmp.getPixel(px, py)
                val r = (c shr 16) and 0xFF
                val gr = (c shr 8) and 0xFF
                val b = c and 0xFF
                out[y * gridX + x] = (r * 3 + gr * 6 + b) / 10
            }
        }
        return out
    }

    /** Fraction of grid cells outside the overlay boxes whose brightness changed noticeably. */
    private fun changeFraction(a: IntArray, b: IntArray, w: Int, h: Int, masks: List<RectF>): Float {
        var considered = 0
        var changed = 0
        val pad = 16f
        for (y in 0 until gridY) {
            val cy = (y + 0.5f) / gridY * h
            for (x in 0 until gridX) {
                val cx = (x + 0.5f) / gridX * w
                if (masks.any { cx >= it.left - pad && cx <= it.right + pad && cy >= it.top - pad && cy <= it.bottom + pad }) continue
                considered++
                if (kotlin.math.abs(a[y * gridX + x] - b[y * gridX + x]) > 24) changed++
            }
        }
        return if (considered == 0) 0f else changed.toFloat() / considered
    }

    override fun onDestroy() {
        RunState.set(false)
        job?.cancel()
        job = null
        scope.cancel()
        overlay?.let {
            try {
                wm.removeView(it)
            } catch (_: Exception) {
            }
        }
        overlay = null
        grabber?.release()
        grabber = null
        try {
            projection?.stop()
        } catch (_: Exception) {
        }
        projection = null
        ocr.close()
        translator.close()
        super.onDestroy()
    }
}
