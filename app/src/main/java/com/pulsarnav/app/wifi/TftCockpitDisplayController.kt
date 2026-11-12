package com.pulsarnav.app.wifi

import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.View
import android.view.WindowManager
import com.pulsarnav.app.ble.BajajBleManager
import com.pulsarnav.app.media.MediaControlManager
import com.pulsarnav.app.model.Maneuver
import com.pulsarnav.app.parser.ParsedNavigationStep
import com.pulsarnav.app.protocol.CallState
import com.pulsarnav.app.service.GoogleMapsNotificationListener
import com.pulsarnav.app.telemetry.PhoneTelemetryManager
import com.pulsarnav.app.telephony.CallManager
import com.pulsarnav.app.tracking.RideTracker
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Manages the 800x480 virtual display pipeline for Bajaj TFT mirroring:
 *  - Mode 1 (COCKPIT_HUD): Offline-first Presentation HUD driven by live TBT notifications,
 *    call state, media playback, telemetry, and cluster touch / handlebar recenter inputs.
 *    Works with zero special consent dialogs even when the phone screen is locked.
 *  - Mode 2 (LIVE_SCREEN_CAST): Mirrors live Google Maps / Waze / phone screen via an active
 *    Android MediaProjection session scaled cleanly to 800x480.
 */
class TftCockpitDisplayController(
    private val context: Context,
    private val onEncodedFrameReady: (ByteArray, Int, Int) -> Unit,
    private val onProjectionStoppedUnexpectedly: () -> Unit
) {

    enum class ClusterTouchZone { NONE, MEDIA_PLAY_PAUSE, DAYLIGHT_TOGGLE }

    companion object {
        private const val TAG = "TftCockpitDisplay"
        private const val REDRAW_INTERVAL_MS = 75L
        private const val LOCAL_PREVIEW_IDLE_REDRAW_MS = 250L

        // Unified 800x480 interactive hitboxes shared between TftCockpitView.onDraw() and onClusterTouch()
        const val FOOTER_THEME_LEFT = 14
        const val FOOTER_THEME_TOP = 416
        const val FOOTER_THEME_RIGHT = 280
        const val FOOTER_THEME_BOTTOM = 468

        const val MEDIA_CARD_LEFT = 526
        const val MEDIA_CARD_TOP = 238
        const val MEDIA_CARD_RIGHT = TftWifiProtocol.TFT_WIDTH - 14
        const val MEDIA_CARD_BOTTOM = 404

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
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val pipelineTransitionLock = Any()
    private val lifecycleLock = Any()

    private var readerThread: HandlerThread? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: TftCockpitPresentation? = null

    private var activeProjection: MediaProjection? = null
    private var activeProjectionCallback: MediaProjection.Callback? = null
    @Volatile
    private var stoppedByProjectionCallback = false
    @Volatile
    private var displayGeneration = 0L

    private var frameBitmap: Bitmap? = null
    private var paddedBitmap: Bitmap? = null
    private var copyCanvas: Canvas? = null
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
    private var lastTouchX = -1
    @Volatile
    private var lastTouchY = -1
    @Volatile
    private var lastTouchTimestampMs = 0L
    @Volatile
    private var recenterToastUntilMs = 0L
    @Volatile
    private var daylightHighContrast = false
    @Volatile
    private var activeSource = TftMirrorSource.COCKPIT_HUD

    @Volatile
    private var previewPaused = false

    fun setPreviewPaused(paused: Boolean) {
        previewPaused = paused
        mainHandler.removeCallbacks(redrawRunnable)
        if (!paused && running && activeSource == TftMirrorSource.COCKPIT_HUD) {
            mainHandler.post(redrawRunnable)
        }
    }

    private val redrawRunnable = object : Runnable {
        override fun run() {
            if (!running || activeSource != TftMirrorSource.COCKPIT_HUD || previewPaused) return
            presentation?.refreshFrame()
            val delayMs = if (virtualDisplay != null && activeProjection != null) {
                REDRAW_INTERVAL_MS
            } else if (clusterStationary) {
                LOCAL_PREVIEW_IDLE_REDRAW_MS
            } else {
                REDRAW_INTERVAL_MS
            }
            mainHandler.postDelayed(this, delayMs)
        }
    }

    fun startCockpitHud() {
        synchronized(pipelineTransitionLock) {
            stop()
            val expectedGen: Long
            synchronized(lifecycleLock) {
                displayGeneration++
                expectedGen = displayGeneration
                activeSource = TftMirrorSource.COCKPIT_HUD
                running = true
                stoppedByProjectionCallback = false
                currentQuality = TftWifiProtocol.UDP_DEFAULT_JPEG_QUALITY
                lastCaptureTimestampMs = 0L
                startReaderPipeline()
                val reader = imageReader ?: return
                virtualDisplay = displayManager.createVirtualDisplay(
                    "PulsarNav_TFT_HUD",
                    TftWifiProtocol.TFT_WIDTH,
                    TftWifiProtocol.TFT_HEIGHT,
                    TftWifiProtocol.TFT_DPI,
                    reader.surface,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
                )
            }

            mainHandler.post {
                synchronized(lifecycleLock) {
                    val vd = virtualDisplay ?: return@post
                    if (!running || activeSource != TftMirrorSource.COCKPIT_HUD || displayGeneration != expectedGen) {
                        return@post
                    }
                    try {
                        val pres = TftCockpitPresentation(context, vd.display)
                        pres.show()
                        presentation = pres
                        mainHandler.removeCallbacks(redrawRunnable)
                        mainHandler.post(redrawRunnable)
                        Log.i(TAG, "Started 800x480 TftCockpitPresentation on VirtualDisplay")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to show TftCockpitPresentation: ${e.message}", e)
                    }
                }
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
                startReaderPipeline()
                val reader = imageReader ?: run {
                    running = false
                    return false
                }

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
                        TftWifiProtocol.TFT_WIDTH,
                        TftWifiProtocol.TFT_HEIGHT,
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
                Log.i(TAG, "Started 800x480 Live MediaProjection VirtualDisplay (created=$created)")
            }
            if (!created) {
                stop()
            }
            return created
        }
    }

    fun updateBikeStationary(stationary: Boolean) {
        clusterStationary = stationary
        if (activeSource == TftMirrorSource.COCKPIT_HUD) {
            mainHandler.post { presentation?.refreshFrame() }
        }
    }

    fun onHandlebarRecenter() {
        recenterToastUntilMs = SystemClock.elapsedRealtime() + 2500L
        daylightHighContrast = false
        if (activeSource == TftMirrorSource.COCKPIT_HUD) {
            mainHandler.post { presentation?.refreshFrame() }
        }
    }

    @Volatile
    private var debugOverlayEnabled = false

    fun setDebugOverlayEnabled(enabled: Boolean) {
        debugOverlayEnabled = enabled
        if (activeSource == TftMirrorSource.COCKPIT_HUD) {
            mainHandler.post { presentation?.refreshFrame() }
        }
    }

    fun isDebugOverlayEnabled(): Boolean = debugOverlayEnabled

    fun isDaylightHighContrast(): Boolean = daylightHighContrast

    fun isBikeStationaryMode(): Boolean = clusterStationary

    fun toggleDaylightHighContrast(): Boolean {
        daylightHighContrast = !daylightHighContrast
        if (activeSource == TftMirrorSource.COCKPIT_HUD) {
            mainHandler.post { presentation?.refreshFrame() }
        }
        return daylightHighContrast
    }

    fun refreshFrameNow() {
        if (activeSource == TftMirrorSource.COCKPIT_HUD) {
            mainHandler.post { presentation?.refreshFrame() }
        }
    }

    fun onClusterTouch(touchType: Int, x: Int, y: Int) {
        val clampedX = x.coerceIn(0, TftWifiProtocol.TFT_WIDTH)
        val clampedY = y.coerceIn(0, TftWifiProtocol.TFT_HEIGHT)
        lastTouchX = clampedX
        lastTouchY = clampedY
        lastTouchTimestampMs = SystemClock.elapsedRealtime()
        // Treat touch UP (5) or DOWN_UP (9) inside the unified 800x480 hitboxes as interactive controls.
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
        if (activeSource == TftMirrorSource.COCKPIT_HUD) {
            mainHandler.post { presentation?.refreshFrame() }
        }
    }

    private fun dismissPresentationSync() {
        val pres = synchronized(lifecycleLock) {
            presentation.also { presentation = null }
        } ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            try {
                pres.dismiss()
            } catch (_: Exception) {}
            return
        }
        val done = CountDownLatch(1)
        mainHandler.post {
            try {
                pres.dismiss()
            } catch (_: Exception) {
            } finally {
                done.countDown()
            }
        }
        try {
            done.await(500L, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    fun stop() {
        synchronized(pipelineTransitionLock) {
            displayGeneration++
            running = false
            mainHandler.removeCallbacks(redrawRunnable)
            // Dismiss Presentation synchronously BEFORE releasing the VirtualDisplay it is attached to.
            dismissPresentationSync()

            var oldThread: HandlerThread?
            var oldReader: ImageReader?
            synchronized(lifecycleLock) {
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

                oldThread = readerThread
                readerThread = null
                try {
                    oldThread?.quitSafely()
                } catch (_: Exception) {}
            }

            // Drain any in-flight processImage() outside lifecycleLock before closing reader or recycling bitmaps.
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
                copyCanvas = null
                frameBitmap?.recycle()
                frameBitmap = null
                paddedBitmap?.recycle()
                paddedBitmap = null
            }
        }
    }

    private fun startReaderPipeline() {
        val thread = HandlerThread("TftImageReader").apply { start() }
        val handler = Handler(thread.looper)
        readerThread = thread

        val reader = ImageReader.newInstance(
            TftWifiProtocol.TFT_WIDTH,
            TftWifiProtocol.TFT_HEIGHT,
            PixelFormat.RGBA_8888,
            2
        )
        reader.setOnImageAvailableListener({ ir ->
            processImage(ir)
        }, handler)
        imageReader = reader
    }

    private fun processImage(reader: ImageReader) {
        if (!running) return
        val image = try {
            reader.acquireLatestImage()
        } catch (_: Exception) {
            null
        } ?: return

        try {
            synchronized(lifecycleLock) {
                if (!running || reader !== imageReader) return
                val now = SystemClock.elapsedRealtime()
                if (now - lastCaptureTimestampMs < TftWifiProtocol.UDP_MIN_CAPTURE_INTERVAL_MS) {
                    return
                }
                val plane = image.planes.firstOrNull() ?: return
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val width = image.width
                val height = image.height
                if (pixelStride <= 0 || width <= 0 || height <= 0) return

                val outBitmap = frameBitmap?.takeIf { !it.isRecycled && it.width == width && it.height == height }
                    ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                        frameBitmap?.recycle()
                        frameBitmap = it
                        copyCanvas = Canvas(it)
                    }

                val rowPadding = rowStride - pixelStride * width
                if (rowPadding == 0) {
                    outBitmap.copyPixelsFromBuffer(buffer)
                } else {
                    val paddedWidth = width + (rowPadding / pixelStride)
                    val padded = paddedBitmap?.takeIf {
                        !it.isRecycled && it.width == paddedWidth && it.height == height
                    } ?: Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888).also {
                        paddedBitmap?.recycle()
                        paddedBitmap = it
                    }
                    padded.copyPixelsFromBuffer(buffer)
                    val canvas = copyCanvas ?: Canvas(outBitmap).also { copyCanvas = it }
                    copySrcRect.set(0, 0, width, height)
                    copyDstRect.set(0, 0, width, height)
                    canvas.drawBitmap(padded, copySrcRect, copyDstRect, null)
                }

                val encoded = encodeAdaptiveJpeg(outBitmap) ?: return
                lastCaptureTimestampMs = now
                onEncodedFrameReady(encoded, currentQuality, encoded.size)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Frame capture/encode skipped: ${e.message}")
        } finally {
            image.close()
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
            currentQuality = maxOf(TftWifiProtocol.UDP_MIN_JPEG_QUALITY, q - 5)
            return null
        }

        currentQuality = TftUdpFrameEncoder.selectNextQuality(q, size)
        return jpegBuffer.toByteArray()
    }

    private fun compressJpegSize(bitmap: Bitmap, quality: Int): Int {
        jpegBuffer.reset()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, jpegBuffer)
        return jpegBuffer.size()
    }

    private inner class TftCockpitPresentation(
        outerContext: Context,
        display: Display
    ) : Presentation(outerContext, display) {

        private var cockpitView: TftCockpitView? = null

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            window?.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
            val view = TftCockpitView(context)
            cockpitView = view
            setContentView(view)
        }

        fun refreshFrame() {
            cockpitView?.invalidate()
        }
    }

    private inner class TftCockpitView(context: Context) : View(context) {

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

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat().coerceAtLeast(1f)
            val h = height.toFloat().coerceAtLeast(1f)
            val highContrast = daylightHighContrast

            val bgColor = if (highContrast) Color.rgb(245, 247, 250) else Color.rgb(10, 12, 16)
            val cardColor = if (highContrast) Color.rgb(255, 255, 255) else Color.rgb(22, 26, 34)
            val borderColor = if (highContrast) Color.rgb(200, 208, 220) else Color.rgb(44, 52, 68)
            val primaryText = if (highContrast) Color.rgb(15, 20, 28) else Color.rgb(246, 248, 252)
            val secondaryText = if (highContrast) Color.rgb(75, 86, 104) else Color.rgb(160, 172, 194)
            val accentColor = Color.rgb(230, 57, 70) // Pulsar Crimson Red
            val cyanAccent = Color.rgb(56, 189, 248)
            val greenAccent = Color.rgb(34, 197, 94)

            bgPaint.color = bgColor
            canvas.drawRect(0f, 0f, w, h, bgPaint)

            val navStep: ParsedNavigationStep? = GoogleMapsNotificationListener.currentNavStep.value
            val waAlert = GoogleMapsNotificationListener.lastWhatsAppAlert.value
            val callInfo = callManager.callInfo.value
            val telemetry = telemetryManager.telemetryState.value
            val media = mediaManager.getActiveMediaSnapshot()
            val stats = rideTracker.getLiveRideSnapshot()

            // Top Telemetry Status Bar (height 54px)
            cardPaint.color = cardColor
            borderPaint.color = borderColor
            cardRect.set(14f, 10f, w - 14f, 58f)
            canvas.drawRoundRect(cardRect, 12f, 12f, cardPaint)
            canvas.drawRoundRect(cardRect, 12f, 12f, borderPaint)

            // Cluster / BLE dot
            val bleConnected = bleManager.isLinkReady
            accentPaint.color = if (bleConnected) greenAccent else Color.rgb(245, 158, 11)
            canvas.drawCircle(34f, 34f, 6f, accentPaint)

            textPaint.typeface = boldTypeface
            textPaint.color = primaryText
            textPaint.textSize = 20f
            textPaint.textAlign = Paint.Align.LEFT
            val headerTitle = bleManager.getConnectedClusterName().ifBlank { "PULSAR TFT" }
            canvas.drawText(headerTitle, 48f, 41f, textPaint)

            // Battery & Time on right of top bar
            textPaint.textAlign = Paint.Align.RIGHT
            textPaint.typeface = normalTypeface
            textPaint.color = secondaryText
            textPaint.textSize = 19f
            val wallNowMs = System.currentTimeMillis()
            val minuteBucket = wallNowMs / 60_000L
            if (minuteBucket != cachedTimeMinute) {
                cachedTimeMinute = minuteBucket
                reusableDate.time = wallNowMs
                cachedTimeStr = timeFormat.format(reusableDate)
            }
            val timeStr = cachedTimeStr
            val stateBadge = if (clusterStationary) "PARK" else "RIDE"
            canvas.drawText(
                "$stateBadge  |  BAT ${telemetry.batteryPercent}%  |  $timeStr",
                w - 28f,
                41f,
                textPaint
            )

            // Main Left Panel: Turn-by-Turn Navigation Card (x: 14..510, y: 70..404)
            cardRect.set(14f, 70f, 512f, 404f)
            canvas.drawRoundRect(cardRect, 18f, 18f, cardPaint)
            canvas.drawRoundRect(cardRect, 18f, 18f, borderPaint)

            val resolvedNavStep = navStep ?: bleManager.simulatedNavStep.value
            if (resolvedNavStep != null) {
                // Maneuver Vector Stage
                radarRect.set(30f, 88f, 186f, 244f)
                cardPaint.color = if (highContrast) Color.rgb(232, 238, 246) else Color.rgb(30, 36, 48)
                canvas.drawRoundRect(radarRect, 20f, 20f, cardPaint)
                drawManeuverVector(canvas, resolvedNavStep.maneuver, radarRect, accentColor, cyanAccent)

                // Distance to next turn
                textPaint.textAlign = Paint.Align.LEFT
                textPaint.typeface = boldTypeface
                textPaint.color = primaryText
                textPaint.textSize = 54f
                val distLabel = formatDistanceShort(resolvedNavStep.distanceMeters, resolvedNavStep.rawDistanceText)
                canvas.drawText(distLabel, 204f, 152f, textPaint)

                // Maneuver description subtitle
                textPaint.textSize = 22f
                textPaint.color = cyanAccent
                canvas.drawText(resolvedNavStep.maneuver.description.uppercase(Locale.US), 204f, 188f, textPaint)

                // ETA / Total Dist Pill
                textPaint.textSize = 21f
                textPaint.color = secondaryText
                textPaint.typeface = normalTypeface
                val etaStr = if (resolvedNavStep.etaHour in 1..12) {
                    val amPm = if (resolvedNavStep.etaIsAm) "AM" else "PM"
                    String.format(Locale.US, "ETA %d:%02d %s", resolvedNavStep.etaHour, resolvedNavStep.etaMinute, amPm)
                } else {
                    "ACTIVE ROUTE"
                }
                val remKm = if (resolvedNavStep.totalDistanceMeters > 0) {
                    String.format(Locale.US, "  •  %.1f km left", resolvedNavStep.totalDistanceMeters / 1000f)
                } else ""
                canvas.drawText("$etaStr$remKm", 204f, 224f, textPaint)

                // Street / Road Name
                textPaint.typeface = boldTypeface
                textPaint.color = primaryText
                textPaint.textSize = 30f
                val street = resolvedNavStep.streetName.ifBlank { "Follow Route" }
                drawWrappedTwoLines(canvas, street, 32f, 294f, 464f, 38f, textPaint)
            } else {
                // Standby Compass / Navigation Ready View
                radarRect.set(34f, 94f, 174f, 234f)
                borderPaint.color = cyanAccent
                borderPaint.strokeWidth = 3f
                canvas.drawOval(radarRect, borderPaint)
                borderPaint.strokeWidth = 2f
                borderPaint.color = borderColor

                // Stylized forward compass chevron
                drawManeuverVector(canvas, Maneuver.STRAIGHT, radarRect, greenAccent, cyanAccent)

                textPaint.textAlign = Paint.Align.LEFT
                textPaint.typeface = boldTypeface
                textPaint.color = primaryText
                textPaint.textSize = 36f
                canvas.drawText("TFT Cockpit Ready", 196f, 152f, textPaint)

                textPaint.typeface = normalTypeface
                textPaint.color = secondaryText
                textPaint.textSize = 21f
                canvas.drawText("Start route in Google Maps or Waze", 196f, 192f, textPaint)
                canvas.drawText("Or use Live Screen Cast for full map view", 196f, 224f, textPaint)

                // Quick Stats Grid inside standby box
                textPaint.typeface = boldTypeface
                textPaint.color = primaryText
                textPaint.textSize = 26f
                val speedText = if (stats.isTracking) "${stats.currentSpeedKmh.toInt()} km/h" else "IDLE"
                val tripText = if (stats.isTracking) {
                    String.format(Locale.US, "%.2f km", stats.distanceKm)
                } else {
                    String.format(Locale.US, "%d trips", stats.lifetimeTrips)
                }
                canvas.drawText("SPEED: $speedText    •    TRIP: $tripText", 34f, 322f, textPaint)

                textPaint.typeface = normalTypeface
                textPaint.color = secondaryText
                textPaint.textSize = 18f
                canvas.drawText(
                    "Handlebar button recenter • Tap bottom-left for Day/Night",
                    34f,
                    368f,
                    textPaint
                )
            }

            // Right Panel Top: Speed / Call / WhatsApp Priority Card (x: 526..786, y: 70..226)
            cardPaint.color = cardColor
            cardRect.set(526f, 70f, w - 14f, 226f)
            canvas.drawRoundRect(cardRect, 18f, 18f, cardPaint)
            canvas.drawRoundRect(cardRect, 18f, 18f, borderPaint)

            val simAlertSender = bleManager.simulatedAlert.value?.takeIf {
                System.currentTimeMillis() - it.timestampMs < 6000L
            }?.sender
            val resolvedAlertSender = waAlert?.sender ?: simAlertSender

            if (callInfo.state != CallState.NO_CALL) {
                accentPaint.color = accentColor
                cardRect.set(538f, 82f, w - 26f, 118f)
                canvas.drawRoundRect(cardRect, 10f, 10f, accentPaint)
                textPaint.color = Color.WHITE
                textPaint.textSize = 18f
                textPaint.typeface = boldTypeface
                canvas.drawText(callInfo.state.name.replace('_', ' '), 550f, 106f, textPaint)

                textPaint.color = primaryText
                textPaint.textSize = 23f
                val caller = callInfo.callerName?.ifBlank { callInfo.callerNumber } ?: "Incoming Call"
                canvas.drawText(ellipsizeText(caller, 210f, textPaint), 542f, 164f, textPaint)
                textPaint.color = secondaryText
                textPaint.textSize = 17f
                textPaint.typeface = normalTypeface
                canvas.drawText("Missed: ${callInfo.missedCallCount}", 542f, 200f, textPaint)
            } else if (!resolvedAlertSender.isNullOrBlank()) {
                textPaint.color = greenAccent
                textPaint.textSize = 17f
                textPaint.typeface = boldTypeface
                canvas.drawText("WHATSAPP ALERT", 544f, 104f, textPaint)
                textPaint.color = primaryText
                textPaint.textSize = 23f
                canvas.drawText(ellipsizeText(resolvedAlertSender, 218f, textPaint), 544f, 146f, textPaint)
            } else {
                textPaint.color = secondaryText
                textPaint.textSize = 16f
                textPaint.typeface = boldTypeface
                canvas.drawText("LIVE SPEED", 544f, 102f, textPaint)
                textPaint.color = primaryText
                textPaint.textSize = 54f
                val spd = if (stats.isTracking) stats.currentSpeedKmh.toInt().toString() else "0"
                canvas.drawText(spd, 544f, 166f, textPaint)
                textPaint.color = secondaryText
                textPaint.textSize = 20f
                canvas.drawText("km/h", 632f, 166f, textPaint)
                textPaint.typeface = normalTypeface
                textPaint.textSize = 17f
                canvas.drawText(
                    String.format(Locale.US, "Trip %.1f km", stats.distanceKm),
                    544f,
                    204f,
                    textPaint
                )
            }

            // Right Panel Bottom: Media Dock Card (x: 526..786, y: 238..404)
            cardPaint.color = cardColor
            cardRect.set(526f, 238f, w - 14f, 404f)
            canvas.drawRoundRect(cardRect, 18f, 18f, cardPaint)
            canvas.drawRoundRect(cardRect, 18f, 18f, borderPaint)

            textPaint.typeface = boldTypeface
            textPaint.color = cyanAccent
            textPaint.textSize = 16f
            val playLabel = when (media.playStatus) {
                2 -> "PLAYING  •  TAP TO PAUSE"
                1 -> "PAUSED  •  TAP TO PLAY"
                else -> "MEDIA IDLE"
            }
            canvas.drawText(playLabel, 544f, 268f, textPaint)

            textPaint.color = primaryText
            textPaint.textSize = 22f
            val trackTitle = media.title.ifBlank { "No Active Track" }
            canvas.drawText(ellipsizeText(trackTitle, 224f, textPaint), 544f, 308f, textPaint)

            textPaint.typeface = normalTypeface
            textPaint.color = secondaryText
            textPaint.textSize = 18f
            val trackArtist = media.artist.ifBlank { "Audio synced via PulsarNav" }
            canvas.drawText(ellipsizeText(trackArtist, 224f, textPaint), 544f, 340f, textPaint)

            if (media.durationSeconds > 0) {
                val barLeft = 544f
                val barRight = w - 30f
                val barTop = 368f
                val progress = (media.elapsedSeconds.toFloat() / media.durationSeconds.toFloat()).coerceIn(0f, 1f)
                cardPaint.color = borderColor
                tmpRect.set(barLeft, barTop, barRight, barTop + 8f)
                canvas.drawRoundRect(tmpRect, 4f, 4f, cardPaint)
                accentPaint.color = accentColor
                tmpRect.set(barLeft, barTop, barLeft + (barRight - barLeft) * progress, barTop + 8f)
                canvas.drawRoundRect(tmpRect, 4f, 4f, accentPaint)
            }

            // Bottom Footer Strip (y: 416..470)
            cardPaint.color = cardColor
            cardRect.set(14f, 416f, w - 14f, 468f)
            canvas.drawRoundRect(cardRect, 12f, 12f, cardPaint)
            canvas.drawRoundRect(cardRect, 12f, 12f, borderPaint)

            textPaint.color = secondaryText
            textPaint.textSize = 18f
            textPaint.textAlign = Paint.Align.LEFT
            val modeChip = if (highContrast) "DAYLIGHT MODE (TAP)" else "NIGHT COCKPIT (TAP)"
            canvas.drawText(modeChip, 30f, 449f, textPaint)

            textPaint.textAlign = Paint.Align.RIGHT
            val nowMs = SystemClock.elapsedRealtime()
            val footerStatus = if (nowMs < recenterToastUntilMs) {
                "RECENTER TRIGGERED"
            } else {
                "PulsarNav Wi-Fi Mirror • 800x480"
            }
            textPaint.color = if (nowMs < recenterToastUntilMs) greenAccent else secondaryText
            canvas.drawText(footerStatus, w - 28f, 449f, textPaint)

            // Optional debug hitbox guide when inspect overlay is active
            if (debugOverlayEnabled) {
                borderPaint.color = Color.argb(140, 245, 158, 11)
                borderPaint.strokeWidth = 2f
                // Touch Zone 1: Day/Night Footer Chip
                cardRect.set(
                    FOOTER_THEME_LEFT.toFloat(),
                    FOOTER_THEME_TOP.toFloat(),
                    FOOTER_THEME_RIGHT.toFloat(),
                    FOOTER_THEME_BOTTOM.toFloat()
                )
                canvas.drawRoundRect(cardRect, 8f, 8f, borderPaint)
                // Touch Zone 2: Media Play/Pause Card
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

            // Transient touch ripple & coordinate crosshair feedback for cluster touch testing
            val touchAgeMs = nowMs - lastTouchTimestampMs
            val touchVisibleMs = if (debugOverlayEnabled) 2500L else 900L
            if (lastTouchX >= 0 && touchAgeMs < touchVisibleMs) {
                borderPaint.color = cyanAccent
                borderPaint.strokeWidth = 3f
                canvas.drawCircle(lastTouchX.toFloat(), lastTouchY.toFloat(), 20f, borderPaint)
                if (debugOverlayEnabled) {
                    canvas.drawLine((lastTouchX - 28).toFloat(), lastTouchY.toFloat(), (lastTouchX + 28).toFloat(), lastTouchY.toFloat(), borderPaint)
                    canvas.drawLine(lastTouchX.toFloat(), (lastTouchY - 28).toFloat(), lastTouchX.toFloat(), (lastTouchY + 28).toFloat(), borderPaint)
                    textPaint.textAlign = Paint.Align.LEFT
                    textPaint.textSize = 14f
                    textPaint.typeface = monoBoldTypeface
                    textPaint.color = cyanAccent
                    val tx = (lastTouchX + 24).coerceAtMost(width - 130).toFloat()
                    val ty = (lastTouchY - 10).coerceAtLeast(24).toFloat()
                    canvas.drawText("(${lastTouchX},${lastTouchY})", tx, ty, textPaint)
                }
                borderPaint.strokeWidth = 2f
            }
        }

        private fun formatDistanceShort(meters: Int, rawText: String): String {
            if (meters <= 0) return rawText.ifBlank { "--" }
            return if (meters >= 1000) {
                String.format(Locale.US, "%.1f km", meters / 1000f)
            } else {
                "${meters} m"
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
            val cx = box.centerX()
            val cy = box.centerY()
            val s = minOf(box.width(), box.height()) * 0.34f
            arrowStrokePaint.color = primary
            arrowStrokePaint.strokeWidth = 11f
            arrowPath.reset()

            when (maneuver) {
                Maneuver.TURN_LEFT, Maneuver.FORK_LEFT, Maneuver.KEEP_LEFT -> {
                    arrowPath.moveTo(cx + s * 0.25f, cy + s)
                    arrowPath.lineTo(cx + s * 0.25f, cy - s * 0.1f)
                    arrowPath.lineTo(cx - s * 0.8f, cy - s * 0.1f)
                    arrowPath.moveTo(cx - s * 0.45f, cy - s * 0.45f)
                    arrowPath.lineTo(cx - s * 0.85f, cy - s * 0.1f)
                    arrowPath.lineTo(cx - s * 0.45f, cy + s * 0.25f)
                }
                Maneuver.TURN_RIGHT, Maneuver.FORK_RIGHT, Maneuver.KEEP_RIGHT -> {
                    arrowPath.moveTo(cx - s * 0.25f, cy + s)
                    arrowPath.lineTo(cx - s * 0.25f, cy - s * 0.1f)
                    arrowPath.lineTo(cx + s * 0.8f, cy - s * 0.1f)
                    arrowPath.moveTo(cx + s * 0.45f, cy - s * 0.45f)
                    arrowPath.lineTo(cx + s * 0.85f, cy - s * 0.1f)
                    arrowPath.lineTo(cx + s * 0.45f, cy + s * 0.25f)
                }
                Maneuver.TURN_SLIGHT_LEFT, Maneuver.RAMP_LEFT -> {
                    arrowPath.moveTo(cx + s * 0.3f, cy + s)
                    arrowPath.lineTo(cx + s * 0.3f, cy + s * 0.1f)
                    arrowPath.lineTo(cx - s * 0.6f, cy - s * 0.8f)
                    arrowPath.moveTo(cx - s * 0.65f, cy - s * 0.3f)
                    arrowPath.lineTo(cx - s * 0.65f, cy - s * 0.85f)
                    arrowPath.lineTo(cx - s * 0.1f, cy - s * 0.85f)
                }
                Maneuver.TURN_SLIGHT_RIGHT, Maneuver.RAMP_RIGHT -> {
                    arrowPath.moveTo(cx - s * 0.3f, cy + s)
                    arrowPath.lineTo(cx - s * 0.3f, cy + s * 0.1f)
                    arrowPath.lineTo(cx + s * 0.6f, cy - s * 0.8f)
                    arrowPath.moveTo(cx + s * 0.1f, cy - s * 0.85f)
                    arrowPath.lineTo(cx + s * 0.65f, cy - s * 0.85f)
                    arrowPath.lineTo(cx + s * 0.65f, cy - s * 0.3f)
                }
                Maneuver.U_TURN_LEFT -> {
                    arrowPath.moveTo(cx + s * 0.45f, cy + s)
                    arrowPath.lineTo(cx + s * 0.45f, cy - s * 0.35f)
                    tmpRect.set(cx - s * 0.45f, cy - s * 0.9f, cx + s * 0.45f, cy)
                    arrowPath.arcTo(
                        tmpRect,
                        0f,
                        -180f,
                        false
                    )
                    arrowPath.lineTo(cx - s * 0.45f, cy + s * 0.55f)
                    arrowPath.moveTo(cx - s * 0.78f, cy + s * 0.25f)
                    arrowPath.lineTo(cx - s * 0.45f, cy + s * 0.65f)
                    arrowPath.lineTo(cx - s * 0.12f, cy + s * 0.25f)
                }
                Maneuver.U_TURN_RIGHT -> {
                    arrowPath.moveTo(cx - s * 0.45f, cy + s)
                    arrowPath.lineTo(cx - s * 0.45f, cy - s * 0.35f)
                    tmpRect.set(cx - s * 0.45f, cy - s * 0.9f, cx + s * 0.45f, cy)
                    arrowPath.arcTo(
                        tmpRect,
                        180f,
                        180f,
                        false
                    )
                    arrowPath.lineTo(cx + s * 0.45f, cy + s * 0.55f)
                    arrowPath.moveTo(cx + s * 0.12f, cy + s * 0.25f)
                    arrowPath.lineTo(cx + s * 0.45f, cy + s * 0.65f)
                    arrowPath.lineTo(cx + s * 0.78f, cy + s * 0.25f)
                }
                Maneuver.ROUNDABOUT_LEFT, Maneuver.ROUNDABOUT_RIGHT -> {
                    arrowStrokePaint.color = secondary
                    canvas.drawCircle(cx, cy - s * 0.1f, s * 0.45f, arrowStrokePaint)
                    arrowStrokePaint.color = primary
                    val dir = if (maneuver == Maneuver.ROUNDABOUT_LEFT) -1f else 1f
                    arrowPath.moveTo(cx, cy + s)
                    arrowPath.lineTo(cx, cy + s * 0.35f)
                    arrowPath.moveTo(cx + dir * s * 0.45f, cy - s * 0.1f)
                    arrowPath.lineTo(cx + dir * s * 0.9f, cy - s * 0.55f)
                }
                Maneuver.DESTINATION_REACHED -> {
                    arrowStrokePaint.color = Color.rgb(34, 197, 94)
                    canvas.drawCircle(cx, cy, s * 0.65f, arrowStrokePaint)
                    canvas.drawCircle(cx, cy, s * 0.22f, accentPaint.apply { color = Color.rgb(34, 197, 94) })
                    return
                }
                else -> {
                    // Straight / Merge / Sharp fallback upright arrow
                    val shiftX = if (abs(maneuver.opcode.toInt()) == 69) -s * 0.35f else 0f
                    arrowPath.moveTo(cx, cy + s)
                    arrowPath.lineTo(cx + shiftX, cy - s * 0.8f)
                    arrowPath.moveTo(cx + shiftX - s * 0.4f, cy - s * 0.35f)
                    arrowPath.lineTo(cx + shiftX, cy - s * 0.85f)
                    arrowPath.lineTo(cx + shiftX + s * 0.4f, cy - s * 0.35f)
                }
            }
            canvas.drawPath(arrowPath, arrowStrokePaint)
        }
    }
}
