package com.remu.app.feature.enrol

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Crops a face with a margin, scales it down, encodes JPEG in memory under 300 KB and moves the
 * five landmarks into crop pixels (TASK-044). Nothing here touches the file system.
 */
object FaceCropper {
    const val MAX_BYTES = 300 * 1024
    const val MARGIN = 0.35f
    const val MAX_SIDE = 480

    /** The crop rectangle in image pixels, the face box grown by [MARGIN] and clamped. */
    fun cropRect(box: Box, imageW: Int, imageH: Int, margin: Float = MARGIN): IntArray {
        val mx = box.width * margin
        val my = box.height * margin
        val l = (box.left - mx).coerceIn(0f, imageW.toFloat()).toInt()
        val t = (box.top - my).coerceIn(0f, imageH.toFloat()).toInt()
        val r = (box.right + mx).coerceIn(0f, imageW.toFloat()).roundToInt().coerceAtLeast(l + 1).coerceAtMost(imageW)
        val b = (box.bottom + my).coerceIn(0f, imageH.toFloat()).roundToInt().coerceAtLeast(t + 1).coerceAtMost(imageH)
        return intArrayOf(l, t, r, b)
    }

    /**
     * Five landmarks in the order the hub expects: left eye, right eye, nose, left mouth corner,
     * right mouth corner, "left" meaning image-left (the InsightFace five-point convention). A
     * missing point is estimated from the box; the hub re-detects and uses these as a fallback.
     */
    fun landmarks(face: DetectedFace): List<Pt> {
        val b = face.box
        fun est(fx: Float, fy: Float) = Pt(b.left + b.width * fx, b.top + b.height * fy)
        val eyes = listOf(face.leftEye ?: est(0.3f, 0.4f), face.rightEye ?: est(0.7f, 0.4f)).sortedBy { it.x }
        val mouth = listOf(face.mouthLeft ?: est(0.35f, 0.78f), face.mouthRight ?: est(0.65f, 0.78f)).sortedBy { it.x }
        return listOf(eyes[0], eyes[1], face.nose ?: est(0.5f, 0.6f), mouth[0], mouth[1])
    }

    fun crop(image: Bitmap, face: DetectedFace): FaceCrop {
        val (l, t, r, b) = cropRect(face.box, image.width, image.height).let { listOf(it[0], it[1], it[2], it[3]) }
        val w = r - l
        val h = b - t
        val scale = minOf(1f, MAX_SIDE.toFloat() / max(w, h))
        val cut = Bitmap.createBitmap(image, l, t, w, h)
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(cut, (w * scale).roundToInt().coerceAtLeast(1), (h * scale).roundToInt().coerceAtLeast(1), true) else cut
        val jpeg = encodeUnderLimit(scaled)
        val points = landmarks(face).map { p -> listOf((p.x - l) * scale, (p.y - t) * scale) }
        if (scaled !== cut) scaled.recycle()
        if (cut !== image) cut.recycle()
        return FaceCrop(jpeg, points)
    }

    /** JPEG quality steps down from 90 until the crop is under [MAX_BYTES]. */
    fun encodeUnderLimit(bitmap: Bitmap): ByteArray {
        var quality = 90
        while (true) {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            val bytes = out.toByteArray()
            if (bytes.size < MAX_BYTES || quality <= 30) return bytes
            quality -= 15
        }
    }
}
