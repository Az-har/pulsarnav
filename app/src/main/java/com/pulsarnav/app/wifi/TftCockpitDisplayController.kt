package com.pulsarnav.app.wifi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import com.pulsarnav.app.ble.BajajBleManager
import com.pulsarnav.app.data.AppSettings
import com.pulsarnav.app.media.MediaControlManager
import com.pulsarnav.app.model.Maneuver
import com.pulsarnav.app.parser.ParsedNavigationStep
import com.pulsarnav.app.protocol.AlertType
import com.pulsarnav.app.protocol.CallState
import com.pulsarnav.app.service.GoogleMapsNotificationListener
import com.pulsarnav.app.telemetry.PhoneTelemetryManager
import com.pulsarnav.app.telephony.CallManager
import com.pulsarnav.app.tracking.RideTracker
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Manages the 800x480 framebuffer pipeline for Bajaj TFT Wi-Fi mirroring:
 *  - Mode 1 (LIVE_MAP): Headless offscreen Canvas renderer on a background HandlerThread that
 *    streams an 800x480 Maps-only landscape view (slippy tiles + OSRM route + TBT corner card +
 *    telemetry strip). Does not use MediaProjection or Presentation, so it keeps streaming
 *    continuously even when the phone screen is locked.
 *  - Mode 2 (COCKPIT_HUD): Headless offscreen Canvas cockpit dashboard renderer (works locked).
 *  - Mode 3 (LIVE_SCREEN_CAST): Mirrors Google Maps / single app via Android MediaProjection with
 *    dynamic orientation detection and aspect/crop normalization to eliminate portrait black bars.
 */
class TftCockpitDisplayController(
    private val context: Context,
    private val onEncodedFrameReady: (ByteArray, Int, Int) -> Unit,
    private val onProjectionStoppedUnexpectedly: () -> Unit
) {

    enum class ClusterTouchZone { NONE, MEDIA_PLAY_PAUSE, DAYLIGHT_TOGGLE }
    enum class LiveMapTouchZone { NONE, ZOOM_IN, ZOOM_OUT, TOGGLE_HEADING, TOGGLE_DAYLIGHT }

    companion object {
        private const val TAG = "TftCockpitDisplay"
        private const val REDRAW_INTERVAL_MS = 65L
        private const val LOCAL_PREVIEW_IDLE_REDRAW_MS = 140L

        // Unified 800x480 interactive hitboxes placed strictly inside NS400Z unobstructed safe zones
        const val FOOTER_THEME_LEFT = TftWifiProtocol.NS400Z_POCKET_LEFT
        const val FOOTER_THEME_TOP = TftWifiProtocol.NS400Z_POCKET_TOP
        const val FOOTER_THEME_RIGHT = 286
        const val FOOTER_THEME_BOTTOM = TftWifiProtocol.NS400Z_POCKET_BOTTOM

        const val MEDIA_CARD_LEFT = 524
        const val MEDIA_CARD_TOP = 220
        const val MEDIA_CARD_RIGHT = TftWifiProtocol.NS400Z_SAFE_RIGHT
        const val MEDIA_CARD_BOTTOM = 316

        fun resolveTouchZone(touchType: Int, x: Int, y: Int): ClusterTouchZone {
            if (touchType != 5 && touchType != 9) return ClusterTouchZone.NONE
            return when {
                x in MEDIA_CARD_LEFT..MEDIA_CARD_RIGHT && y in MEDIA_CARD_TOP..MEDIA_CARD_BOTTOM ->
                    ClusterTouchZone.MEDIA_PLAY_PAUSE
                x in FOOTER_THEME_LEFT..FOOTER_THEME_RIGHT && y in FOOTER_THEME_TOP..FOOTER_THEME_BOTTOM ->
                    ClusterTouchZone.DAYLIGHT_TOGGLE
                else -> ClusterTouchZone.NONE
            }
        }

        fun resolveLiveMapTouchZone(touchType: Int, x: Int, y: Int): LiveMapTouchZone {
            if (touchType != 5 && touchType != 9) return LiveMapTouchZone.NONE
            return when {
                x >= TftWifiProtocol.NS400Z_SAFE_RIGHT - 56 && y in 120..168 -> LiveMapTouchZone.ZOOM_IN
                x >= TftWifiProtocol.NS400Z_SAFE_RIGHT - 56 && y in 174..222 -> LiveMapTouchZone.ZOOM_OUT
                x >= TftWifiProtocol.NS400Z_SAFE_RIGHT - 56 && y in 228..278 -> LiveMapTouchZone.TOGGLE_HEADING
                y in TftWifiProtocol.NS400Z_POCKET_TOP..TftWifiProtocol.NS400Z_POCKET_BOTTOM &&
                    x in TftWifiProtocol.NS400Z_POCKET_LEFT..TftWifiProtocol.NS400Z_POCKET_RIGHT -> LiveMapTouchZone.TOGGLE_DAYLIGHT
                else -> LiveMapTouchZone.NONE
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val appSettings = AppSettings.getInstance(context)
    private val mapEngine = TftNavigationMapEngine.getInstance(context)
    private val hudRenderer = CockpitHudCanvasRenderer(context)

    private val pipelineTransitionLock = Any()
    private val lifecycleLock = Any()

    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

    private var activeProjection: MediaProjection? = null
    private var activeProjectionCallback: MediaProjection.Callback? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    @Volatile
    private var stoppedByProjectionCallback = false
    @Volatile
    private var displayGeneration = 0L

    private data class TouchMarker(val x: Int, val y: Int, val timestampMs: Long)

    private var frameBitmap: Bitmap? = null
    private var frameCanvas: Canvas? = null
    private var castRawBitmap: Bitmap? = null
    private var castPaddedBitmap: Bitmap? = null
    private var cachedBoundsRect: IntRect? = null
    private var cachedBoundsSrcW = 0
    private var cachedBoundsSrcH = 0
    private var cachedBoundsAgeFrames = 0
    private val castMatrix = Matrix()
    private val castFilterPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val copySrcRect = Rect()
    private val copyDstRect = Rect()
    private val jpegBuffer = ByteArrayOutputStream(32_768)

    @Volatile
    private var running = false
    @Volatile
    private var currentQuality = TftWifiProtocol.UDP_DEFAULT_JPEG_QUALITY
    @Volatile
    private var lastCaptureTimestampMs = 0L
    @Volatile
    private var clusterStationary = true
    @Volatile
    private var lastTouchMarker: TouchMarker? = null
    @Volatile
    private var recenterToastUntilMs = 0L
    @Volatile
    private var daylightHighContrast = false
    @Volatile
    private var activeSource = TftMirrorSource.LIVE_MAP
    @Volatile
    private var previewPaused = false
    @Volatile
    private var debugOverlayEnabled = false
    @Volatile
    private var currentCaptureSize = IntSize(TftWifiProtocol.TFT_WIDTH, TftWifiProtocol.TFT_HEIGHT)

    private val immediateRefreshRunnable = Runnable {
        renderAndEmitHeadlessFrame()
    }

    private val headlessTickRunnable = object : Runnable {
        override fun run() {
            if (!running || activeSource == TftMirrorSource.LIVE_SCREEN_CAST || previewPaused) return
            renderAndEmitHeadlessFrame()
            val delayMs = if (clusterStationary) LOCAL_PREVIEW_IDLE_REDRAW_MS else REDRAW_INTERVAL_MS
            workerHandler?.postDelayed(this, delayMs)
        }
    }

    fun setPreviewPaused(paused: Boolean) {
        previewPaused = paused
        workerHandler?.removeCallbacks(headlessTickRunnable)
        if (!paused && running && activeSource != TftMirrorSource.LIVE_SCREEN_CAST) {
            workerHandler?.post(headlessTickRunnable)
        }
    }

    fun startCockpitHud() {
        startHeadless(TftMirrorSource.COCKPIT_HUD)
    }

    fun startHeadless(source: TftMirrorSource) {
        val normalizedSource = if (source == TftMirrorSource.COCKPIT_HUD) {
            TftMirrorSource.COCKPIT_HUD
        } else {
            TftMirrorSource.LIVE_MAP
        }
        synchronized(pipelineTransitionLock) {
            stop()
            synchronized(lifecycleLock) {
                displayGeneration++
                activeSource = normalizedSource
                running = true
                stoppedByProjectionCallback = false
                currentQuality = TftWifiProtocol.UDP_DEFAULT_JPEG_QUALITY
                lastCaptureTimestampMs = 0L

                val thread = HandlerThread("TftHeadlessRenderThread").apply { start() }
                val handler = Handler(thread.looper)
                workerThread = thread
                workerHandler = handler
                handler.post(headlessTickRunnable)
                Log.i(TAG, "Started lock-proof headless 800x480 TFT pipeline (mode=$normalizedSource)")
            }
        }
    }

    fun startLiveScreenCast(mediaProjection: MediaProjection): Boolean {
        synchronized(pipelineTransitionLock) {
            stop()
            var created = false
            synchronized(lifecycleLock) {
                displayGeneration++
                activeSource = TftMirrorSource.LIVE_SCREEN_CAST
                running = true
                stoppedByProjectionCallback = false
                currentQuality = TftWifiProtocol.UDP_DEFAULT_JPEG_QUALITY
                lastCaptureTimestampMs = 0L

                val thread = HandlerThread("TftImageReader").apply { start() }
                val handler = Handler(thread.looper)
                workerThread = thread
                workerHandler = handler

                val initialSize = queryCurrentCastCaptureSize()
                currentCaptureSize = initialSize
                val reader = createCastImageReader(initialSize, handler)
                imageReader = reader

                val callback = object : MediaProjection.Callback() {
                    override fun onStop() {
                        Log.w(TAG, "MediaProjection stopped by user or system")
                        stoppedByProjectionCallback = true
                        onProjectionStoppedUnexpectedly()
                    }
                }
                activeProjection = mediaProjection
                activeProjectionCallback = callback
                mediaProjection.registerCallback(callback, mainHandler)

                virtualDisplay = try {
                    mediaProjection.createVirtualDisplay(
                        "PulsarNav_TFT_Cast",
                        initialSize.width,
                        initialSize.height,
                        TftWifiProtocol.TFT_DPI,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        reader.surface,
                        null,
                        null
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to create MediaProjection VirtualDisplay: ${e.message}", e)
                    null
                }
                created = virtualDisplay != null
                if (created) {
                    val dListener = object : DisplayManager.DisplayListener {
                        override fun onDisplayAdded(displayId: Int) {}
                        override fun onDisplayRemoved(displayId: Int) {}
                        override fun onDisplayChanged(displayId: Int) {
                            if (displayId == Display.DEFAULT_DISPLAY) {
                                maybeResizeCastSurface()
                            }
                        }
                    }
                    displayListener = dListener
                    displayManager.registerDisplayListener(dListener, handler)
                }
                Log.i(TAG, "Started Live MediaProjection ${initialSize.width}x${initialSize.height} -> 800x480 (created=$created)")
            }
            if (!created) {
                stop()
            }
            return created
        }
    }

    @Suppress("DEPRECATION")
    private fun queryCurrentCastCaptureSize(): IntSize {
        val disp = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        val pt = Point()
        if (disp != null) {
            disp.getRealSize(pt)
        } else {
            val dm = context.resources.displayMetrics
            pt.set(dm.widthPixels, dm.heightPixels)
        }
        return TftScreenCastTransform.computeCaptureDimensions(pt.x, pt.y, maxEdge = 800)
    }

    private fun createCastImageReader(size: IntSize, handler: Handler): ImageReader {
        return ImageReader.newInstance(size.width, size.height, PixelFormat.RGBA_8888, 2).also { ir ->
            ir.setOnImageAvailableListener({ reader ->
                processCastImage(reader)
            }, handler)
        }
    }

    private fun maybeResizeCastSurface() {
        val next = queryCurrentCastCaptureSize()
        synchronized(lifecycleLock) {
            if (!running || activeSource != TftMirrorSource.LIVE_SCREEN_CAST) return
            if (next == currentCaptureSize) return
            val vd = virtualDisplay ?: return
            val handler = workerHandler ?: return
            val oldReader = imageReader
            val newReader = createCastImageReader(next, handler)
            currentCaptureSize = next
            imageReader = newReader
            try {
                oldReader?.setOnImageAvailableListener(null, null)
            } catch (_: Exception) {}
            try {
                vd.resize(next.width, next.height, TftWifiProtocol.TFT_DPI)
                vd.surface = newReader.surface
            } catch (e: Exception) {
                Log.w(TAG, "Failed to resize VirtualDisplay to ${next.width}x${next.height}: ${e.message}")
            }
            try {
                oldReader?.close()
            } catch (_: Exception) {}
        }
    }

    fun updateBikeStationary(stationary: Boolean) {
        clusterStationary = stationary
        refreshFrameNow()
    }

    fun onHandlebarRecenter() {
        recenterToastUntilMs = SystemClock.elapsedRealtime() + 2500L
        daylightHighContrast = false
        refreshFrameNow()
    }

    fun setDebugOverlayEnabled(enabled: Boolean) {
        debugOverlayEnabled = enabled
        refreshFrameNow()
    }

    fun isDebugOverlayEnabled(): Boolean = debugOverlayEnabled

    fun isDaylightHighContrast(): Boolean = daylightHighContrast

    fun isBikeStationaryMode(): Boolean = clusterStationary

    fun toggleDaylightHighContrast(): Boolean {
        daylightHighContrast = !daylightHighContrast
        refreshFrameNow()
        return daylightHighContrast
    }

    fun refreshFrameNow() {
        if (!running || activeSource == TftMirrorSource.LIVE_SCREEN_CAST || previewPaused) return
        val handler = workerHandler ?: return
        handler.removeCallbacks(immediateRefreshRunnable)
        handler.post(immediateRefreshRunnable)
    }

    fun onClusterTouch(touchType: Int, x: Int, y: Int) {
        val clampedX = x.coerceIn(0, TftWifiProtocol.TFT_WIDTH)
        val clampedY = y.coerceIn(0, TftWifiProtocol.TFT_HEIGHT)
        lastTouchMarker = TouchMarker(clampedX, clampedY, SystemClock.elapsedRealtime())

        if (activeSource == TftMirrorSource.LIVE_MAP) {
            when (resolveLiveMapTouchZone(touchType, clampedX, clampedY)) {
                LiveMapTouchZone.ZOOM_IN -> mapEngine.adjustZoom(+1)
                LiveMapTouchZone.ZOOM_OUT -> mapEngine.adjustZoom(-1)
                LiveMapTouchZone.TOGGLE_HEADING -> mapEngine.toggleHeadingUp()
                LiveMapTouchZone.TOGGLE_DAYLIGHT -> daylightHighContrast = !daylightHighContrast
                LiveMapTouchZone.NONE -> {}
            }
        } else {
            when (resolveTouchZone(touchType, clampedX, clampedY)) {
                ClusterTouchZone.MEDIA_PLAY_PAUSE -> {
                    val mm = MediaControlManager.getInstance(context)
                    if (mm.getActiveMediaSnapshot().playStatus == 2) {
                        mm.dispatchPause()
                    } else {
                        mm.dispatchPlay()
                    }
                }
                ClusterTouchZone.DAYLIGHT_TOGGLE -> {
                    daylightHighContrast = !daylightHighContrast
                }
                ClusterTouchZone.NONE -> {}
            }
        }
        refreshFrameNow()
    }

    fun stop() {
        synchronized(pipelineTransitionLock) {
            displayGeneration++
            running = false

            var oldThread: HandlerThread?
            var oldReader: ImageReader?
            synchronized(lifecycleLock) {
                workerHandler?.removeCallbacks(headlessTickRunnable)
                workerHandler?.removeCallbacks(immediateRefreshRunnable)
                workerHandler = null

                displayListener?.let { dl ->
                    try {
                        displayManager.unregisterDisplayListener(dl)
                    } catch (_: Exception) {}
                }
                displayListener = null

                val proj = activeProjection
                val cb = activeProjectionCallback
                val alreadyStoppedByCb = stoppedByProjectionCallback
                activeProjection = null
                activeProjectionCallback = null
                stoppedByProjectionCallback = false
                if (proj != null) {
                    if (cb != null) {
                        try {
                            proj.unregisterCallback(cb)
                        } catch (_: Exception) {}
                    }
                    if (!alreadyStoppedByCb) {
                        try {
                            proj.stop()
                        } catch (_: Exception) {}
                    }
                }

                try {
                    virtualDisplay?.release()
                } catch (_: Exception) {}
                virtualDisplay = null

                oldReader = imageReader
                imageReader = null
                try {
                    oldReader?.setOnImageAvailableListener(null, null)
                } catch (_: Exception) {}

                oldThread = workerThread
                workerThread = null
                try {
                    oldThread?.quitSafely()
                } catch (_: Exception) {}
            }

            if (oldThread != null && Thread.currentThread() !== oldThread) {
                try {
                    oldThread?.join(500L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }

            try {
                oldReader?.close()
            } catch (_: Exception) {}

            synchronized(lifecycleLock) {
                frameCanvas = null
                frameBitmap?.recycle()
                frameBitmap = null
                castRawBitmap?.recycle()
                castRawBitmap = null
                castPaddedBitmap?.recycle()
                castPaddedBitmap = null
                cachedBoundsRect = null
                cachedBoundsSrcW = 0
                cachedBoundsSrcH = 0
                cachedBoundsAgeFrames = 0
            }
        }
    }

    private fun renderAndEmitHeadlessFrame() {
        synchronized(lifecycleLock) {
            if (!running || activeSource == TftMirrorSource.LIVE_SCREEN_CAST) return
            val w = TftWifiProtocol.TFT_WIDTH
            val h = TftWifiProtocol.TFT_HEIGHT
            val bmp = ensureTargetBitmap(w, h)
            val canvas = frameCanvas ?: Canvas(bmp).also { frameCanvas = it }

            if (activeSource == TftMirrorSource.LIVE_MAP) {
                hudRenderer.drawLiveMapFrame(canvas, w, h)
            } else {
                hudRenderer.drawCockpitHudFrame(canvas, w.toFloat(), h.toFloat())
            }

            val encoded = encodeAdaptiveJpeg(bmp) ?: return
            lastCaptureTimestampMs = SystemClock.elapsedRealtime()
            onEncodedFrameReady(encoded, currentQuality, encoded.size)
        }
    }

    private fun processCastImage(reader: ImageReader) {
        if (!running) return
        synchronized(lifecycleLock) {
            if (!running || reader !== imageReader) return
            val image = try {
                reader.acquireLatestImage()
            } catch (_: Exception) {
                null
            } ?: return

            try {
                val now = SystemClock.elapsedRealtime()
                if (now - lastCaptureTimestampMs < TftWifiProtocol.UDP_MIN_CAPTURE_INTERVAL_MS) {
                    return
                }
                val plane = image.planes.firstOrNull() ?: return
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val srcW = image.width
                val srcH = image.height
                if (pixelStride <= 0 || srcW <= 0 || srcH <= 0) return

                val rowPadding = rowStride - pixelStride * srcW
                val srcBitmapForTransform: Bitmap
                if (rowPadding == 0) {
                    val rawBmp = castRawBitmap?.takeIf { !it.isRecycled && it.width == srcW && it.height == srcH }
                        ?: Bitmap.createBitmap(srcW, srcH, Bitmap.Config.ARGB_8888).also {
                            castRawBitmap?.recycle()
                            castRawBitmap = it
                        }
                    rawBmp.copyPixelsFromBuffer(buffer)
                    srcBitmapForTransform = rawBmp
                } else {
                    val paddedW = srcW + (rowPadding / pixelStride)
                    val padded = castPaddedBitmap?.takeIf {
                        !it.isRecycled && it.width == paddedW && it.height == srcH
                    } ?: Bitmap.createBitmap(paddedW, srcH, Bitmap.Config.ARGB_8888).also {
                        castPaddedBitmap?.recycle()
                        castPaddedBitmap = it
                    }
                    padded.copyPixelsFromBuffer(buffer)
                    srcBitmapForTransform = padded
                }

                val boundsNeedRefresh = cachedBoundsRect == null ||
                    cachedBoundsSrcW != srcW ||
                    cachedBoundsSrcH != srcH ||
                    cachedBoundsAgeFrames >= 15
                val contentBounds = if (boundsNeedRefresh) {
                    cachedBoundsSrcW = srcW
                    cachedBoundsSrcH = srcH
                    cachedBoundsAgeFrames = 0
                    TftScreenCastTransform.detectNonBlackBounds(srcBitmapForTransform).also {
                        cachedBoundsRect = it
                    }
                } else {
                    cachedBoundsAgeFrames++
                    cachedBoundsRect
                }

                val dstBmp = ensureTargetBitmap(TftWifiProtocol.TFT_WIDTH, TftWifiProtocol.TFT_HEIGHT)
                val dstCanvas = frameCanvas ?: Canvas(dstBmp).also { frameCanvas = it }
                TftScreenCastTransform.renderIntoTarget(
                    srcBitmap = srcBitmapForTransform,
                    dstBitmap = dstBmp,
                    dstCanvas = dstCanvas,
                    layoutMode = appSettings.tftCastLayoutMode,
                    drawMatrix = castMatrix,
                    filterPaint = castFilterPaint,
                    srcRectTmp = copySrcRect,
                    dstRectTmp = copyDstRect,
                    srcClipRect = if (rowPadding > 0) IntRect(0, 0, srcW, srcH) else null,
                    cachedContentBounds = contentBounds
                )

                val encoded = encodeAdaptiveJpeg(dstBmp) ?: return
                lastCaptureTimestampMs = now
                onEncodedFrameReady(encoded, currentQuality, encoded.size)
            } catch (e: Exception) {
                Log.w(TAG, "Frame capture/encode skipped: ${e.message}")
            } finally {
                image.close()
            }
        }
    }

    private fun ensureTargetBitmap(w: Int, h: Int): Bitmap {
        return frameBitmap?.takeIf { !it.isRecycled && it.width == w && it.height == h }
            ?: Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565).also {
                frameBitmap?.recycle()
                frameBitmap = it
                frameCanvas = Canvas(it)
            }
    }

    private fun encodeAdaptiveJpeg(bitmap: Bitmap): ByteArray? {
        var q = currentQuality.coerceIn(TftWifiProtocol.UDP_MIN_JPEG_QUALITY, 100)
        var size = compressJpegSize(bitmap, q)

        var attempts = 0
        while (size >= TftWifiProtocol.UDP_MAX_JPEG_BYTES &&
            attempts < TftWifiProtocol.UDP_MAX_REENCODE_ATTEMPTS
        ) {
            val retryQ = TftUdpFrameEncoder.computeRetryQuality(q, size)
            if (retryQ == q) break
            q = retryQ
            size = compressJpegSize(bitmap, q)
            attempts++
        }

        if (size >= TftWifiProtocol.UDP_MAX_JPEG_BYTES) {
            size = compressJpegSize(bitmap, 16)
            if (size >= TftWifiProtocol.UDP_MAX_JPEG_BYTES) {
                currentQuality = TftWifiProtocol.UDP_MIN_JPEG_QUALITY
                return null
            }
            currentQuality = TftWifiProtocol.UDP_MIN_JPEG_QUALITY
            return jpegBuffer.toByteArray()
        }

        currentQuality = TftUdpFrameEncoder.selectNextQuality(q, size)
        return jpegBuffer.toByteArray()
    }

    private fun compressJpegSize(bitmap: Bitmap, quality: Int): Int {
        jpegBuffer.reset()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, jpegBuffer)
        return jpegBuffer.size()
    }

    private inner class CockpitHudCanvasRenderer(context: Context) {

        private val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())
        private val bleManager = BajajBleManager.getInstance(context)
        private val mediaManager = MediaControlManager.getInstance(context)
        private val callManager = CallManager.getInstance(context)
        private val telemetryManager = PhoneTelemetryManager.getInstance(context)
        private val rideTracker = RideTracker.getInstance(context)

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val arrowStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val normalTypeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        private val boldTypeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        private val monoBoldTypeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        private val reusableDate = Date()
        private var cachedTimeMinute = -1L
        private var cachedTimeStr = ""
        private val line1Buf = StringBuilder(48)
        private val line2Buf = StringBuilder(48)
        private val arrowPath = Path()
        private val cardRect = RectF()
        private val radarRect = RectF()
        private val tmpRect = RectF()

        private fun currentClockString(): String {
            val wallNowMs = System.currentTimeMillis()
            val minuteBucket = wallNowMs / 60_000L
            if (minuteBucket != cachedTimeMinute) {
                cachedTimeMinute = minuteBucket
                reusableDate.time = wallNowMs
                cachedTimeStr = timeFormat.format(reusableDate)
            }
            return cachedTimeStr
        }

        fun drawLiveMapFrame(canvas: Canvas, width: Int, height: Int) {
            val telemetry = telemetryManager.telemetryState.value
            val callInfo = callManager.callInfo.value
            val waAlert = GoogleMapsNotificationListener.lastWhatsAppAlert.value
            val simAlert = bleManager.simulatedAlert.value?.takeIf {
                System.currentTimeMillis() - it.timestampMs < 6000L
            }
            val resolvedAlertLabel = when {
                waAlert != null -> {
                    val prefix = if (waAlert.alertType == AlertType.SMS) "SMS" else "WA"
                    "$prefix: ${waAlert.sender}"
                }
                simAlert != null -> {
                    val prefix = if (simAlert.alertType == AlertType.SMS) "SMS" else "WA"
                    "$prefix: ${simAlert.sender}"
                }
                else -> null
            }
            val caller = if (callInfo.state != CallState.NO_CALL) {
                callInfo.callerName?.ifBlank { callInfo.callerNumber } ?: "Incoming Call"
            } else null

            val navStep = mapEngine.getCurrentStepForHud() ?: bleManager.simulatedNavStep.value
            val toast = if (SystemClock.elapsedRealtime() < recenterToastUntilMs) "RECENTERED" else null

            mapEngine.renderMapFrame(
                canvas = canvas,
                width = width,
                height = height,
                daylightMode = daylightHighContrast,
                isStationary = clusterStationary,
                batteryPct = telemetry.batteryPercent,
                clockText = currentClockString(),
                activeNavStep = navStep,
                incomingCaller = caller,
                whatsAppSender = resolvedAlertLabel,
                toastBanner = toast
            )
        }

        fun drawCockpitHudFrame(canvas: Canvas, w: Float, h: Float) {
            val highContrast = daylightHighContrast

            val bgColor = if (highContrast) Color.rgb(245, 247, 250) else Color.rgb(10, 12, 16)
            val cardColor = if (highContrast) Color.rgb(255, 255, 255) else Color.rgb(22, 26, 34)
            val borderColor = if (highContrast) Color.rgb(200, 208, 220) else Color.rgb(44, 52, 68)
            val primaryText = if (highContrast) Color.rgb(15, 20, 28) else Color.rgb(246, 248, 252)
            val secondaryText = if (highContrast) Color.rgb(75, 86, 104) else Color.rgb(160, 172, 194)
            val accentColor = Color.rgb(230, 57, 70)
            val cyanAccent = Color.rgb(56, 189, 248)
            val greenAccent = Color.rgb(34, 197, 94)

            bgPaint.color = bgColor
            canvas.drawRect(0f, 0f, w, h, bgPaint)

            val navStep: ParsedNavigationStep? = mapEngine.getCurrentStepForHud()
            val waAlert = GoogleMapsNotificationListener.lastWhatsAppAlert.value
            val callInfo = callManager.callInfo.value
            val telemetry = telemetryManager.telemetryState.value
            val media = mediaManager.getActiveMediaSnapshot()
            val stats = rideTracker.getLiveRideSnapshot()

            val safeL = TftWifiProtocol.NS400Z_SAFE_LEFT.toFloat()
            val safeR = TftWifiProtocol.NS400Z_SAFE_RIGHT.toFloat()

            // Top Header strip placed cleanly below OEM status bar + BLE TBT line (y >= 66)
            cardPaint.color = cardColor
            borderPaint.color = borderColor
            cardRect.set(safeL, 66f, safeR, 102f)
            canvas.drawRoundRect(cardRect, 10f, 10f, cardPaint)
            canvas.drawRoundRect(cardRect, 10f, 10f, borderPaint)

            val bleConnected = bleManager.isLinkReady
            accentPaint.color = if (bleConnected) greenAccent else Color.rgb(245, 158, 11)
            canvas.drawCircle(safeL + 18f, 84f, 5f, accentPaint)

            textPaint.typeface = boldTypeface
            textPaint.color = primaryText
            textPaint.textSize = 17f
            textPaint.textAlign = Paint.Align.LEFT
            val headerTitle = bleManager.getConnectedClusterName().ifBlank { "PULSAR TFT" }
            canvas.drawText(headerTitle, safeL + 30f, 90f, textPaint)

            textPaint.textAlign = Paint.Align.RIGHT
            textPaint.typeface = normalTypeface
            textPaint.color = secondaryText
            textPaint.textSize = 16f
            val timeStr = currentClockString()
            val stateBadge = if (clusterStationary) "PARK" else "RIDE"
            canvas.drawText(
                "$stateBadge  |  BAT ${telemetry.batteryPercent}%  |  $timeStr",
                safeR - 14f,
                90f,
                textPaint
            )

            // Main Left TBT / Ready Card (above y=322 speedometer line)
            cardRect.set(safeL, 108f, 514f, 316f)
            canvas.drawRoundRect(cardRect, 16f, 16f, cardPaint)
            canvas.drawRoundRect(cardRect, 16f, 16f, borderPaint)

            val resolvedNavStep = navStep ?: bleManager.simulatedNavStep.value
            if (resolvedNavStep != null) {
                radarRect.set(safeL + 14f, 120f, safeL + 132f, 238f)
                cardPaint.color = if (highContrast) Color.rgb(232, 238, 246) else Color.rgb(30, 36, 48)
                canvas.drawRoundRect(radarRect, 16f, 16f, cardPaint)
                drawManeuverVector(canvas, resolvedNavStep.maneuver, radarRect, accentColor, cyanAccent)

                textPaint.textAlign = Paint.Align.LEFT
                textPaint.typeface = boldTypeface
                textPaint.color = primaryText
                textPaint.textSize = 44f
                val distLabel = formatDistanceShort(resolvedNavStep.distanceMeters, resolvedNavStep.rawDistanceText)
                canvas.drawText(distLabel, safeL + 148f, 162f, textPaint)

                textPaint.textSize = 19f
                textPaint.color = cyanAccent
                canvas.drawText(resolvedNavStep.maneuver.description.uppercase(Locale.US), safeL + 148f, 192f, textPaint)

                textPaint.textSize = 18f
                textPaint.color = secondaryText
                textPaint.typeface = normalTypeface
                val etaStr = if (resolvedNavStep.etaHour in 1..12) {
                    val amPm = if (resolvedNavStep.etaIsAm) "AM" else "PM"
                    val min = resolvedNavStep.etaMinute.coerceIn(0, 59)
                    if (min < 10) "ETA ${resolvedNavStep.etaHour}:0$min $amPm"
                    else "ETA ${resolvedNavStep.etaHour}:$min $amPm"
                } else {
                    "ACTIVE ROUTE"
                }
                val remKm = if (resolvedNavStep.totalDistanceMeters > 0) {
                    val tenths = resolvedNavStep.totalDistanceMeters / 100
                    "  •  ${tenths / 10}.${tenths % 10} km left"
                } else ""
                canvas.drawText("$etaStr$remKm", safeL + 148f, 224f, textPaint)

                textPaint.typeface = boldTypeface
                textPaint.color = primaryText
                textPaint.textSize = 24f
                val street = resolvedNavStep.streetName.ifBlank { "Follow Route" }
                drawWrappedTwoLines(canvas, street, safeL + 14f, 272f, 452f, 30f, textPaint)
            } else {
                radarRect.set(safeL + 14f, 122f, safeL + 124f, 232f)
                borderPaint.color = cyanAccent
                borderPaint.strokeWidth = 3f
                canvas.drawOval(radarRect, borderPaint)
                borderPaint.strokeWidth = 2f
                borderPaint.color = borderColor

                drawManeuverVector(canvas, Maneuver.STRAIGHT, radarRect, greenAccent, cyanAccent)

                textPaint.textAlign = Paint.Align.LEFT
                textPaint.typeface = boldTypeface
                textPaint.color = primaryText
                textPaint.textSize = 30f
                canvas.drawText("TFT Cockpit Ready", safeL + 142f, 160f, textPaint)

                textPaint.typeface = normalTypeface
                textPaint.color = secondaryText
                textPaint.textSize = 18f
                canvas.drawText("Waiting for Google Maps TBT or Live Cast", safeL + 142f, 192f, textPaint)
                canvas.drawText("Or select Live Map (OSM) in TFT Config", safeL + 142f, 218f, textPaint)

                textPaint.typeface = boldTypeface
                textPaint.color = primaryText
                textPaint.textSize = 22f
                val speedText = if (stats.isTracking) "${stats.currentSpeedKmh.toInt()} km/h" else "IDLE"
                val tripText = if (stats.isTracking) {
                    val hundredths = (stats.distanceKm * 100.0).toInt().coerceAtLeast(0)
                    val frac = hundredths % 100
                    if (frac < 10) "${hundredths / 100}.0$frac km" else "${hundredths / 100}.$frac km"
                } else {
                    "${stats.lifetimeTrips} trips"
                }
                canvas.drawText("SPEED: $speedText    •    TRIP: $tripText", safeL + 16f, 272f, textPaint)

                textPaint.typeface = normalTypeface
                textPaint.color = secondaryText
                textPaint.textSize = 15f
                canvas.drawText(
                    "Handlebar button recenter • Tap pill below for Day/Night",
                    safeL + 16f,
                    300f,
                    textPaint
                )
            }

            // Top-Right Card: Call / SMS / WhatsApp / Live Speed (y=108..212)
            cardPaint.color = cardColor
            cardRect.set(524f, 108f, safeR, 212f)
            canvas.drawRoundRect(cardRect, 16f, 16f, cardPaint)
            canvas.drawRoundRect(cardRect, 16f, 16f, borderPaint)

            val simAlertInfo = bleManager.simulatedAlert.value?.takeIf {
                System.currentTimeMillis() - it.timestampMs < 6000L
            }
            val resolvedAlertSender = waAlert?.sender ?: simAlertInfo?.sender
            val resolvedAlertType = when {
                waAlert != null -> waAlert.alertType
                simAlertInfo != null -> simAlertInfo.alertType
                else -> AlertType.WHATSAPP
            }

            if (callInfo.state != CallState.NO_CALL) {
                accentPaint.color = accentColor
                cardRect.set(534f, 118f, safeR - 10f, 146f)
                canvas.drawRoundRect(cardRect, 8f, 8f, accentPaint)
                textPaint.color = Color.WHITE
                textPaint.textSize = 15f
                textPaint.typeface = boldTypeface
                canvas.drawText(callInfo.state.name.replace('_', ' '), 544f, 138f, textPaint)

                textPaint.color = primaryText
                textPaint.textSize = 20f
                val caller = callInfo.callerName?.ifBlank { callInfo.callerNumber } ?: "Incoming Call"
                canvas.drawText(ellipsizeText(caller, 230f, textPaint), 538f, 176f, textPaint)
                textPaint.color = secondaryText
                textPaint.textSize = 15f
                textPaint.typeface = normalTypeface
                canvas.drawText("Missed: ${callInfo.missedCallCount}", 538f, 200f, textPaint)
            } else if (!resolvedAlertSender.isNullOrBlank()) {
                val isSms = resolvedAlertType == AlertType.SMS
                textPaint.color = if (isSms) cyanAccent else greenAccent
                textPaint.textSize = 15f
                textPaint.typeface = boldTypeface
                canvas.drawText(if (isSms) "SMS ALERT" else "WHATSAPP ALERT", 538f, 136f, textPaint)
                textPaint.color = primaryText
                textPaint.textSize = 22f
                canvas.drawText(ellipsizeText(resolvedAlertSender, 232f, textPaint), 538f, 174f, textPaint)
            } else {
                textPaint.color = secondaryText
                textPaint.textSize = 14f
                textPaint.typeface = boldTypeface
                canvas.drawText("LIVE SPEED", 538f, 132f, textPaint)
                textPaint.color = primaryText
                textPaint.textSize = 42f
                val spd = if (stats.isTracking) stats.currentSpeedKmh.toInt().toString() else "0"
                canvas.drawText(spd, 538f, 180f, textPaint)
                textPaint.color = secondaryText
                textPaint.textSize = 18f
                canvas.drawText("km/h", 616f, 180f, textPaint)
                textPaint.typeface = normalTypeface
                textPaint.textSize = 14f
                val tenths = (stats.distanceKm * 10.0).toInt().coerceAtLeast(0)
                canvas.drawText("Trip ${tenths / 10}.${tenths % 10} km", 538f, 202f, textPaint)
            }

            // Bottom-Right Media Card (strictly y=220..316, clear of y=322 speedometer!)
            cardPaint.color = cardColor
            cardRect.set(
                MEDIA_CARD_LEFT.toFloat(),
                MEDIA_CARD_TOP.toFloat(),
                MEDIA_CARD_RIGHT.toFloat(),
                MEDIA_CARD_BOTTOM.toFloat()
            )
            canvas.drawRoundRect(cardRect, 14f, 14f, cardPaint)
            canvas.drawRoundRect(cardRect, 14f, 14f, borderPaint)

            textPaint.typeface = boldTypeface
            textPaint.color = cyanAccent
            textPaint.textSize = 13f
            val playLabel = when (media.playStatus) {
                2 -> "PLAYING  •  TAP"
                1 -> "PAUSED  •  TAP"
                else -> "MEDIA IDLE"
            }
            canvas.drawText(playLabel, MEDIA_CARD_LEFT + 14f, MEDIA_CARD_TOP + 22f, textPaint)

            textPaint.color = primaryText
            textPaint.textSize = 18f
            val trackTitle = media.title.ifBlank { "No Active Track" }
            canvas.drawText(ellipsizeText(trackTitle, 232f, textPaint), MEDIA_CARD_LEFT + 14f, MEDIA_CARD_TOP + 48f, textPaint)

            textPaint.typeface = normalTypeface
            textPaint.color = secondaryText
            textPaint.textSize = 15f
            val trackArtist = media.artist.ifBlank { "Audio synced via PulsarNav" }
            canvas.drawText(ellipsizeText(trackArtist, 232f, textPaint), MEDIA_CARD_LEFT + 14f, MEDIA_CARD_TOP + 70f, textPaint)

            if (media.durationSeconds > 0) {
                val barLeft = MEDIA_CARD_LEFT + 14f
                val barRight = MEDIA_CARD_RIGHT - 14f
                val barTop = MEDIA_CARD_BOTTOM - 14f
                val progress = (media.elapsedSeconds.toFloat() / media.durationSeconds.toFloat()).coerceIn(0f, 1f)
                cardPaint.color = borderColor
                tmpRect.set(barLeft, barTop, barRight, barTop + 6f)
                canvas.drawRoundRect(tmpRect, 3f, 3f, cardPaint)
                accentPaint.color = accentColor
                tmpRect.set(barLeft, barTop, barLeft + (barRight - barLeft) * progress, barTop + 6f)
                canvas.drawRoundRect(tmpRect, 3f, 3f, accentPaint)
            }

            // Unobstructed Lower-Left Pocket Pill (x=34..412, y=326..376 — above ETD, left of Speedo)
            cardPaint.color = cardColor
            cardRect.set(
                TftWifiProtocol.NS400Z_POCKET_LEFT.toFloat(),
                TftWifiProtocol.NS400Z_POCKET_TOP.toFloat(),
                TftWifiProtocol.NS400Z_POCKET_RIGHT.toFloat(),
                TftWifiProtocol.NS400Z_POCKET_BOTTOM.toFloat()
            )
            canvas.drawRoundRect(cardRect, 12f, 12f, cardPaint)
            canvas.drawRoundRect(cardRect, 12f, 12f, borderPaint)

            textPaint.color = secondaryText
            textPaint.textSize = 15f
            textPaint.textAlign = Paint.Align.LEFT
            val nowMs = SystemClock.elapsedRealtime()
            val pocketLabel = when {
                nowMs < recenterToastUntilMs -> "RECENTER TRIGGERED"
                highContrast -> "DAYLIGHT MODE (TAP)"
                else -> "NIGHT COCKPIT (TAP)"
            }
            if (nowMs < recenterToastUntilMs) textPaint.color = greenAccent
            canvas.drawText(pocketLabel, TftWifiProtocol.NS400Z_POCKET_LEFT + 14f, TftWifiProtocol.NS400Z_POCKET_TOP + 30f, textPaint)
            textPaint.color = secondaryText
            textPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText("SAFE HUD", TftWifiProtocol.NS400Z_POCKET_RIGHT - 14f, TftWifiProtocol.NS400Z_POCKET_TOP + 30f, textPaint)

            if (debugOverlayEnabled) {
                borderPaint.color = Color.argb(140, 245, 158, 11)
                borderPaint.strokeWidth = 2f
                cardRect.set(
                    FOOTER_THEME_LEFT.toFloat(),
                    FOOTER_THEME_TOP.toFloat(),
                    FOOTER_THEME_RIGHT.toFloat(),
                    FOOTER_THEME_BOTTOM.toFloat()
                )
                canvas.drawRoundRect(cardRect, 8f, 8f, borderPaint)
                cardRect.set(
                    MEDIA_CARD_LEFT.toFloat(),
                    MEDIA_CARD_TOP.toFloat(),
                    MEDIA_CARD_RIGHT.toFloat(),
                    MEDIA_CARD_BOTTOM.toFloat()
                )
                canvas.drawRoundRect(cardRect, 14f, 14f, borderPaint)
                textPaint.textAlign = Paint.Align.RIGHT
                textPaint.textSize = 12f
                textPaint.typeface = monoBoldTypeface
                textPaint.color = Color.rgb(245, 158, 11)
                canvas.drawText("DBG HITBOXES 800x480", w - 26f, 256f, textPaint)
                borderPaint.color = borderColor
            }

            val marker = lastTouchMarker
            val touchVisibleMs = if (debugOverlayEnabled) 2500L else 900L
            if (marker != null && nowMs - marker.timestampMs < touchVisibleMs) {
                borderPaint.color = cyanAccent
                borderPaint.strokeWidth = 3f
                canvas.drawCircle(marker.x.toFloat(), marker.y.toFloat(), 20f, borderPaint)
                if (debugOverlayEnabled) {
                    canvas.drawLine((marker.x - 28).toFloat(), marker.y.toFloat(), (marker.x + 28).toFloat(), marker.y.toFloat(), borderPaint)
                    canvas.drawLine(marker.x.toFloat(), (marker.y - 28).toFloat(), marker.x.toFloat(), (marker.y + 28).toFloat(), borderPaint)
                    textPaint.textAlign = Paint.Align.LEFT
                    textPaint.textSize = 14f
                    textPaint.typeface = monoBoldTypeface
                    textPaint.color = cyanAccent
                    val tx = (marker.x + 24).coerceAtMost(w.toInt() - 130).toFloat()
                    val ty = (marker.y - 10).coerceAtLeast(24).toFloat()
                    canvas.drawText("(${marker.x},${marker.y})", tx, ty, textPaint)
                }
                borderPaint.strokeWidth = 2f
            }
        }

        private fun formatDistanceShort(meters: Int, rawText: String): String {
            if (meters <= 0) return rawText.ifBlank { "--" }
            return if (meters >= 1000) {
                val tenths = meters / 100
                "${tenths / 10}.${tenths % 10} km"
            } else {
                "$meters m"
            }
        }

        private fun ellipsizeText(text: String, maxWidth: Float, paint: Paint): String {
            if (text.isEmpty() || paint.measureText(text) <= maxWidth) return text
            val ellipsisWidth = paint.measureText("...")
            val avail = (maxWidth - ellipsisWidth).coerceAtLeast(0f)
            val count = paint.breakText(text, true, avail, null).coerceAtLeast(0)
            return if (count > 0) text.substring(0, count) + "..." else ""
        }

        private fun drawWrappedTwoLines(
            canvas: Canvas,
            text: String,
            x: Float,
            y: Float,
            maxWidth: Float,
            lineHeight: Float,
            paint: Paint
        ) {
            line1Buf.setLength(0)
            line2Buf.setLength(0)
            var onFirstLine = true
            var i = 0
            val len = text.length
            while (i < len) {
                while (i < len && text[i] == ' ') i++
                if (i >= len) break
                val start = i
                while (i < len && text[i] != ' ') i++
                val end = i
                if (onFirstLine) {
                    val prevLen = line1Buf.length
                    if (prevLen > 0) line1Buf.append(' ')
                    line1Buf.append(text, start, end)
                    if (paint.measureText(line1Buf, 0, line1Buf.length) > maxWidth) {
                        if (prevLen == 0) {
                            val clipped = ellipsizeText(text.substring(start, end), maxWidth, paint)
                            line1Buf.setLength(0)
                            line1Buf.append(clipped)
                            onFirstLine = false
                        } else {
                            line1Buf.setLength(prevLen)
                            line2Buf.append(text, start, end)
                            onFirstLine = false
                        }
                    }
                } else {
                    if (line2Buf.isNotEmpty()) line2Buf.append(' ')
                    line2Buf.append(text, start, end)
                }
            }
            if (line1Buf.isEmpty() && line2Buf.isEmpty()) return
            canvas.drawText(line1Buf, 0, line1Buf.length, x, y, paint)
            if (line2Buf.isNotEmpty()) {
                canvas.drawText(ellipsizeText(line2Buf.toString(), maxWidth, paint), x, y + lineHeight, paint)
            }
        }

        private fun drawManeuverVector(
            canvas: Canvas,
            maneuver: Maneuver,
            box: RectF,
            primary: Int,
            secondary: Int
        ) {
            arrowStrokePaint.strokeWidth = 11f
            TftManeuverVectorDrawer.draw(
                canvas = canvas,
                maneuver = maneuver,
                cx = box.centerX(),
                cy = box.centerY(),
                s = minOf(box.width(), box.height()) * 0.34f,
                strokePaint = arrowStrokePaint,
                fillPaint = accentPaint,
                path = arrowPath,
                arcRect = tmpRect,
                primaryColor = primary,
                secondaryColor = secondary
            )
        }
    }
}
