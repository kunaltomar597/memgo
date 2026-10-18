package com.remu.app.feature.enrol

/** A point in upright image pixels. */
data class Pt(val x: Float, val y: Float)

/** A box in upright image pixels. */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val area: Float get() = width.coerceAtLeast(0f) * height.coerceAtLeast(0f)
    val centerX: Float get() = (left + right) / 2
    val centerY: Float get() = (top + bottom) / 2
}

/**
 * One face ML Kit found in a frame. [trackingId] is stable across frames, so a tap keeps the
 * same person selected. Landmarks are the five ML Kit points, any of which may be missing.
 */
data class DetectedFace(
    val trackingId: Int,
    val box: Box,
    val leftEye: Pt? = null,
    val rightEye: Pt? = null,
    val nose: Pt? = null,
    val mouthLeft: Pt? = null,
    val mouthRight: Pt? = null,
)

/** The latest detections and the size of the upright image they refer to. */
data class FrameFaces(
    val imageWidth: Int,
    val imageHeight: Int,
    val faces: List<DetectedFace>,
    /** True for the front camera: the preview is mirrored, the analysed image is not. */
    val mirrored: Boolean = false,
)

/** A face crop for `POST /enrol`: JPEG bytes in memory and five landmarks in crop pixels. */
class FaceCrop(val jpeg: ByteArray, val landmarks: List<List<Float>>) {
    /** Overwrites the bytes once the request is done, so no copy lingers in memory either. */
    fun wipe() { jpeg.fill(0) }
}
