package com.pulsarnav.app.wifi

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.pulsarnav.app.data.AppSettings
import com.pulsarnav.app.model.Maneuver
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class IntSize(val width: Int, val height: Int)

data class IntRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = (right - left).coerceAtLeast(1)
    val height: Int get() = (bottom - top).coerceAtLeast(1)
}

/**
 * Pure aspect-ratio, black-border trim, and orientation transform math for
 * MediaProjection VirtualDisplay -> Bajaj NS400Z 800x480 TFT frames.
 */
object TftScreenCastTransform {

    private const val BLACK_LUMA_THRESHOLD = 14

    fun computeCaptureDimensions(displayW: Int, displayH: Int, maxEdge: Int = 960): IntSize {
        val w = displayW.coerceAtLeast(1)
        val h = displayH.coerceAtLeast(1)
        val longest = max(w, h).toFloat()
        val scale = if (longest > maxEdge) maxEdge / longest else 1f
        val outW = ((w * scale).roundToInt() and 0x7FFFFFFE).coerceAtLeast(240)
        val outH = ((h * scale).roundToInt() and 0x7FFFFFFE).coerceAtLeast(240)
        return IntSize(outW, outH)
    }

    /**
     * Computes the trimmed source crop rectangle inside [contentRect] so that casting a portrait
     * or ultra-wide phone display fills the 800x480 (5:3) TFT naturally without distortion or
     * giant black sidebars.
     */
    fun computeSourceCropRect(
        contentRect: IntRect,
        targetW: Int = TftWifiProtocol.TFT_WIDTH,
        targetH: Int = TftWifiProtocol.TFT_HEIGHT,
        layoutMode: String = AppSettings.CAST_LAYOUT_FILL
    ): IntRect {
        if (layoutMode == AppSettings.CAST_LAYOUT_FIT) {
            return contentRect
        }

        val cw = contentRect.width
        val ch = contentRect.height
        val targetAspect = targetW.toFloat() / targetH.toFloat()
        val srcAspect = cw.toFloat() / ch.toFloat()

        if (abs(srcAspect - targetAspect) < 0.03f) {
            return contentRect
        }

        return if (srcAspect < targetAspect) {
            // Portrait or taller than 5:3: take full width and slice a top-biased 5:3 window
            // (bias=0.15) so the top turn-by-turn banner in navigation apps stays inside the crop.
            val rawCropH = (cw / targetAspect).roundToInt().coerceIn(1, ch)
            val slack = ch - rawCropH
            val isTallPortrait = ch > cw * 1.35f
            val bias = if (isTallPortrait) 0.15f else 0.50f
            val topOffset = (slack * bias).roundToInt().coerceIn(0, slack)
            IntRect(
                left = contentRect.left,
                top = contentRect.top + topOffset,
                right = contentRect.right,
                bottom = contentRect.top + topOffset + rawCropH
            )
        } else {
            // Ultra-wide landscape (e.g. 20:9 phone rotated): center-crop sides to 5:3.
            val rawCropW = (ch * targetAspect).roundToInt().coerceIn(1, cw)
            val leftOffset = ((cw - rawCropW) / 2).coerceIn(0, cw - rawCropW)
            IntRect(
                left = contentRect.left + leftOffset,
                top = contentRect.top,
                right = contentRect.left + leftOffset + rawCropW,
                bottom = contentRect.bottom
            )
        }
    }

    fun computeFitDestinationRect(
        srcW: Int,
        srcH: Int,
        targetW: Int = TftWifiProtocol.TFT_WIDTH,
        targetH: Int = TftWifiProtocol.TFT_HEIGHT,
        viewport: IntRect = IntRect(0, 0, targetW, targetH)
    ): IntRect {
        val vpW = viewport.width
        val vpH = viewport.height
        val scale = min(vpW.toFloat() / srcW.coerceAtLeast(1), vpH.toFloat() / srcH.coerceAtLeast(1))
        val drawW = (srcW * scale).roundToInt().coerceIn(1, vpW)
        val drawH = (srcH * scale).roundToInt().coerceIn(1, vpH)
        val left = viewport.left + (vpW - drawW) / 2
        val top = viewport.top + (vpH - drawH) / 2
        return IntRect(left, top, left + drawW, top + drawH)
    }

    /**
     * Returns the unobstructed Bajaj NS400Z 800x480 viewport so casted Google Maps content
     * is never covered by the OEM top status strip, left drawer arrow, bottom-left OEM TBT bar,
     * or bottom-right speedometer/fuel trapezoid.
     */
    fun computeSafeViewport(
        srcW: Int,
        srcH: Int,
        layoutMode: String
    ): IntRect {
        val isPortraitSource = srcH > srcW
        return if (layoutMode == AppSettings.CAST_LAYOUT_FIT && isPortraitSource) {
            // Tall left-column pocket: 380x312 gives 22% more height for uncropped portrait navigation
            IntRect(
                left = TftWifiProtocol.NS400Z_SAFE_LEFT,
                top = TftWifiProtocol.NS400Z_SAFE_TOP,
                right = TftWifiProtocol.NS400Z_POCKET_RIGHT,
                bottom = TftWifiProtocol.NS400Z_POCKET_BOTTOM
            )
        } else {
            // Wide top safe panorama: 754x256 completely above speedo (y=324) & below OEM strip (y=64)
            IntRect(
                left = TftWifiProtocol.NS400Z_SAFE_LEFT,
                top = TftWifiProtocol.NS400Z_SAFE_TOP,
                right = TftWifiProtocol.NS400Z_SAFE_RIGHT,
                bottom = TftWifiProtocol.NS400Z_SAFE_BOTTOM
            )
        }
    }

    private val rowScratch = ThreadLocal<IntArray?>()
    private val colScratch = ThreadLocal<IntArray?>()

    /**
     * Scans sparse scanlines on [bitmap] to locate non-black active content bounds when
     * Android SurfaceFlinger or OEM overlays insert black pillarbox/letterbox bars.
     */
    fun detectNonBlackBounds(bitmap: Bitmap, maxWidth: Int = bitmap.width): IntRect {
        val w = maxWidth.coerceIn(1, bitmap.width)
        val h = bitmap.height
        val fallback = IntRect(0, 0, w, h)
        if (w < 64 || h < 64) return fallback

        val sampleYs = intArrayOf(h / 5, (h * 2) / 5, h / 2, (h * 3) / 5, (h * 4) / 5)
        val sampleXs = intArrayOf(w / 5, (w * 2) / 5, w / 2, (w * 3) / 5, (w * 4) / 5)

        var rowBuf = rowScratch.get()
        if (rowBuf == null || rowBuf.size < w) {
            rowBuf = IntArray(w)
            rowScratch.set(rowBuf)
        }
        var colBuf = colScratch.get()
        if (colBuf == null || colBuf.size < h) {
            colBuf = IntArray(h)
            colScratch.set(colBuf)
        }

        var left = w
        var right = -1
        for (y in sampleYs) {
            bitmap.getPixels(rowBuf, 0, w, 0, y, w, 1)
            var x = 0
            while (x < w) {
                if (isNonBlackPixel(rowBuf[x])) {
                    if (x < left) left = x
                    if (x > right) right = x
                }
                x += 8
            }
        }

        var top = h
        var bottom = -1
        for (x in sampleXs) {
            bitmap.getPixels(colBuf, 0, 1, x, 0, 1, h)
            var y = 0
            while (y < h) {
                if (isNonBlackPixel(colBuf[y])) {
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
                y += 8
            }
        }

        if (right <= left || bottom <= top) return fallback
        val cleanLeft = (left - 4).coerceAtLeast(0)
        val cleanRight = (right + 8).coerceAtMost(w)
        val cleanTop = (top - 4).coerceAtLeast(0)
        val cleanBottom = (bottom + 8).coerceAtMost(h)
        if (cleanRight - cleanLeft < w / 6 || cleanBottom - cleanTop < h / 6) {
            return fallback
        }
        return IntRect(cleanLeft, cleanTop, cleanRight, cleanBottom)
    }

    fun renderIntoTarget(
        srcBitmap: Bitmap,
        dstBitmap: Bitmap,
        dstCanvas: Canvas,
        layoutMode: String,
        drawMatrix: Matrix,
        filterPaint: Paint,
        srcRectTmp: android.graphics.Rect,
        dstRectTmp: android.graphics.Rect,
        srcClipRect: IntRect? = null,
        cachedContentBounds: IntRect? = null
    ) {
        val dstW = dstBitmap.width
        val dstH = dstBitmap.height
        val rawContent = cachedContentBounds ?: detectNonBlackBounds(srcBitmap, srcClipRect?.right ?: srcBitmap.width)
        val content = if (srcClipRect != null) {
            IntRect(
                left = rawContent.left.coerceIn(srcClipRect.left, srcClipRect.right - 1),
                top = rawContent.top.coerceIn(srcClipRect.top, srcClipRect.bottom - 1),
                right = rawContent.right.coerceIn(srcClipRect.left + 1, srcClipRect.right),
                bottom = rawContent.bottom.coerceIn(srcClipRect.top + 1, srcClipRect.bottom)
            )
        } else {
            rawContent
        }
        val safeViewport = computeSafeViewport(content.width, content.height, layoutMode)
        dstCanvas.drawColor(Color.BLACK)

        when (layoutMode) {
            AppSettings.CAST_LAYOUT_FIT -> {
                val dstRect = computeFitDestinationRect(content.width, content.height, dstW, dstH, safeViewport)
                srcRectTmp.set(content.left, content.top, content.right, content.bottom)
                dstRectTmp.set(dstRect.left, dstRect.top, dstRect.right, dstRect.bottom)
                dstCanvas.drawBitmap(srcBitmap, srcRectTmp, dstRectTmp, filterPaint)
            }
            AppSettings.CAST_LAYOUT_ROT90 -> {
                // Rotate content -90 deg (counter-clockwise so top of phone lands on left of TFT)
                // and aspect-fill strictly inside the NS400Z unobstructed safe viewport.
                val rotW = content.height.toFloat()
                val rotH = content.width.toFloat()
                val vpW = safeViewport.width.toFloat()
                val vpH = safeViewport.height.toFloat()
                val scale = max(vpW / rotW, vpH / rotH)
                val vpCenterX = (safeViewport.left + safeViewport.right) * 0.5f
                val vpCenterY = (safeViewport.top + safeViewport.bottom) * 0.5f
                drawMatrix.reset()
                drawMatrix.postTranslate(-content.left - content.width * 0.5f, -content.top - content.height * 0.5f)
                drawMatrix.postRotate(-90f)
                drawMatrix.postScale(scale, scale)
                drawMatrix.postTranslate(vpCenterX, vpCenterY)
                dstCanvas.save()
                dstCanvas.clipRect(
                    safeViewport.left.toFloat(),
                    safeViewport.top.toFloat(),
                    safeViewport.right.toFloat(),
                    safeViewport.bottom.toFloat()
                )
                dstCanvas.drawBitmap(srcBitmap, drawMatrix, filterPaint)
                dstCanvas.restore()
            }
            else -> {
                val crop = computeSourceCropRect(
                    contentRect = content,
                    targetW = safeViewport.width,
                    targetH = safeViewport.height,
                    layoutMode = AppSettings.CAST_LAYOUT_FILL
                )
                srcRectTmp.set(crop.left, crop.top, crop.right, crop.bottom)
                dstRectTmp.set(safeViewport.left, safeViewport.top, safeViewport.right, safeViewport.bottom)
                dstCanvas.drawBitmap(srcBitmap, srcRectTmp, dstRectTmp, filterPaint)
            }
        }
    }

    private fun isNonBlackPixel(argb: Int): Boolean {
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        return r > BLACK_LUMA_THRESHOLD || g > BLACK_LUMA_THRESHOLD || b > BLACK_LUMA_THRESHOLD
    }
}

internal object TftManeuverVectorDrawer {

    fun draw(
        canvas: Canvas,
        maneuver: Maneuver,
        cx: Float,
        cy: Float,
        s: Float,
        strokePaint: Paint,
        fillPaint: Paint,
        path: Path,
        arcRect: RectF,
        primaryColor: Int,
        secondaryColor: Int
    ) {
        strokePaint.color = primaryColor
        path.reset()

        when (maneuver) {
            Maneuver.TURN_LEFT, Maneuver.FORK_LEFT, Maneuver.KEEP_LEFT, Maneuver.TURN_SHARP_LEFT -> {
                path.moveTo(cx + s * 0.25f, cy + s)
                path.lineTo(cx + s * 0.25f, cy - s * 0.1f)
                path.lineTo(cx - s * 0.8f, cy - s * 0.1f)
                path.moveTo(cx - s * 0.45f, cy - s * 0.45f)
                path.lineTo(cx - s * 0.85f, cy - s * 0.1f)
                path.lineTo(cx - s * 0.45f, cy + s * 0.25f)
            }
            Maneuver.TURN_RIGHT, Maneuver.FORK_RIGHT, Maneuver.KEEP_RIGHT, Maneuver.TURN_SHARP_RIGHT -> {
                path.moveTo(cx - s * 0.25f, cy + s)
                path.lineTo(cx - s * 0.25f, cy - s * 0.1f)
                path.lineTo(cx + s * 0.8f, cy - s * 0.1f)
                path.moveTo(cx + s * 0.45f, cy - s * 0.45f)
                path.lineTo(cx + s * 0.85f, cy - s * 0.1f)
                path.lineTo(cx + s * 0.45f, cy + s * 0.25f)
            }
            Maneuver.TURN_SLIGHT_LEFT, Maneuver.RAMP_LEFT -> {
                path.moveTo(cx + s * 0.3f, cy + s)
                path.lineTo(cx + s * 0.3f, cy + s * 0.1f)
                path.lineTo(cx - s * 0.6f, cy - s * 0.8f)
                path.moveTo(cx - s * 0.65f, cy - s * 0.3f)
                path.lineTo(cx - s * 0.65f, cy - s * 0.85f)
                path.lineTo(cx - s * 0.1f, cy - s * 0.85f)
            }
            Maneuver.TURN_SLIGHT_RIGHT, Maneuver.RAMP_RIGHT -> {
                path.moveTo(cx - s * 0.3f, cy + s)
                path.lineTo(cx - s * 0.3f, cy + s * 0.1f)
                path.lineTo(cx + s * 0.6f, cy - s * 0.8f)
                path.moveTo(cx + s * 0.1f, cy - s * 0.85f)
                path.lineTo(cx + s * 0.65f, cy - s * 0.85f)
                path.lineTo(cx + s * 0.65f, cy - s * 0.3f)
            }
            Maneuver.U_TURN_LEFT -> {
                path.moveTo(cx + s * 0.45f, cy + s)
                path.lineTo(cx + s * 0.45f, cy - s * 0.35f)
                arcRect.set(cx - s * 0.45f, cy - s * 0.9f, cx + s * 0.45f, cy)
                path.arcTo(arcRect, 0f, -180f, false)
                path.lineTo(cx - s * 0.45f, cy + s * 0.55f)
                path.moveTo(cx - s * 0.78f, cy + s * 0.25f)
                path.lineTo(cx - s * 0.45f, cy + s * 0.65f)
                path.lineTo(cx - s * 0.12f, cy + s * 0.25f)
            }
            Maneuver.U_TURN_RIGHT -> {
                path.moveTo(cx - s * 0.45f, cy + s)
                path.lineTo(cx - s * 0.45f, cy - s * 0.35f)
                arcRect.set(cx - s * 0.45f, cy - s * 0.9f, cx + s * 0.45f, cy)
                path.arcTo(arcRect, 180f, 180f, false)
                path.lineTo(cx + s * 0.45f, cy + s * 0.55f)
                path.moveTo(cx + s * 0.12f, cy + s * 0.25f)
                path.lineTo(cx + s * 0.45f, cy + s * 0.65f)
                path.lineTo(cx + s * 0.78f, cy + s * 0.25f)
            }
            Maneuver.ROUNDABOUT_LEFT, Maneuver.ROUNDABOUT_RIGHT -> {
                strokePaint.color = secondaryColor
                canvas.drawCircle(cx, cy - s * 0.1f, s * 0.45f, strokePaint)
                strokePaint.color = primaryColor
                val dir = if (maneuver == Maneuver.ROUNDABOUT_LEFT) -1f else 1f
                path.moveTo(cx, cy + s)
                path.lineTo(cx, cy + s * 0.35f)
                path.moveTo(cx + dir * s * 0.45f, cy - s * 0.1f)
                path.lineTo(cx + dir * s * 0.9f, cy - s * 0.55f)
            }
            Maneuver.DESTINATION_REACHED -> {
                val destGreen = Color.rgb(34, 197, 94)
                strokePaint.color = destGreen
                canvas.drawCircle(cx, cy, s * 0.65f, strokePaint)
                fillPaint.color = destGreen
                canvas.drawCircle(cx, cy, s * 0.22f, fillPaint)
                return
            }
            else -> {
                val shiftX = if (abs(maneuver.opcode.toInt()) == 69) -s * 0.35f else 0f
                path.moveTo(cx, cy + s)
                path.lineTo(cx + shiftX, cy - s * 0.8f)
                path.moveTo(cx + shiftX - s * 0.4f, cy - s * 0.35f)
                path.lineTo(cx + shiftX, cy - s * 0.85f)
                path.lineTo(cx + shiftX + s * 0.4f, cy - s * 0.35f)
            }
        }
        canvas.drawPath(path, strokePaint)
    }
}
