package com.remu.app.feature.enrol

/**
 * Which faces the cashier has chosen (TASK-042, TASK-043). The largest, closest face is selected
 * on its own; a tap moves the selection; with Household on, taps add adults up to six, and a
 * numbered chip removes one. Pure logic, so it is tested on recorded detection results.
 */
data class FaceSelection(
    val selected: List<Int> = emptyList(),
    val manual: Boolean = false,
    val household: Boolean = false,
) {
    val isHousehold: Boolean get() = household && selected.size > 1

    /** Re-applies the automatic choice after new detections arrive. */
    fun onFaces(faces: List<DetectedFace>): FaceSelection {
        val visible = faces.map { it.trackingId }.toSet()
        val largest = faces.maxByOrNull { it.box.area }?.trackingId
        if (household) {
            // Adults chosen for a household stay chosen while they shuffle out of view briefly.
            return if (selected.isEmpty() && largest != null) copy(selected = listOf(largest), manual = false) else this
        }
        val current = selected.firstOrNull()
        return when {
            largest == null -> if (manual && current != null) this else copy(selected = emptyList(), manual = false)
            current == null -> copy(selected = listOf(largest), manual = false)
            manual && current in visible -> this
            else -> copy(selected = listOf(largest), manual = false)
        }
    }

    fun tap(trackingId: Int): FaceSelection = when {
        !household -> copy(selected = listOf(trackingId), manual = true)
        trackingId in selected -> this
        selected.size >= MAX_FACES -> this
        else -> copy(selected = selected + trackingId, manual = true)
    }

    fun remove(trackingId: Int): FaceSelection = copy(selected = selected - trackingId, manual = true)

    fun setHousehold(on: Boolean): FaceSelection =
        if (on) copy(household = true) else copy(household = false, selected = selected.take(1))

    fun clear(): FaceSelection = FaceSelection(household = false)

    /** 1-based number shown on the badge, or null when the face is not selected. */
    fun numberOf(trackingId: Int): Int? = selected.indexOf(trackingId).takeIf { it >= 0 }?.plus(1)

    companion object { const val MAX_FACES = 6 }
}

/** Maps image boxes onto the preview, which scales with FILL_CENTER and mirrors the front camera. */
object OverlayTransform {
    data class ViewBox(val left: Float, val top: Float, val width: Float, val height: Float)

    fun map(box: Box, imageW: Int, imageH: Int, viewW: Float, viewH: Float, mirrored: Boolean): ViewBox {
        if (imageW <= 0 || imageH <= 0) return ViewBox(0f, 0f, 0f, 0f)
        val scale = maxOf(viewW / imageW, viewH / imageH)
        val dx = (viewW - imageW * scale) / 2f
        val dy = (viewH - imageH * scale) / 2f
        var l = box.left * scale + dx
        var r = box.right * scale + dx
        if (mirrored) { val nl = viewW - r; r = viewW - l; l = nl }
        val t = box.top * scale + dy
        val b = box.bottom * scale + dy
        return ViewBox(l, t, r - l, b - t)
    }
}
