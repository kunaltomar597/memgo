package com.remu.app.feature.enrol

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.remu.app.R
import com.remu.app.core.theme.Remu
import com.remu.app.core.theme.RemuType
import com.remu.app.core.ui.RemuText
import kotlin.math.roundToInt

private enum class BoxStyle { DETECTED, SELECTED, HOUSEHOLD, REFUSED }

/**
 * Corner brackets over the live preview (docs/DESIGN.md section 5). The whole bracket is the tap
 * target, at least 64dp. Boxes never show a name: nothing is recognised at the counter.
 */
@Composable
fun FaceOverlay(
    frames: FrameFaces?,
    selection: FaceSelection,
    refused: Set<Int>,
    onTap: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Remu.colors
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val viewW = with(density) { maxWidth.toPx() }
        val viewH = with(density) { maxHeight.toPx() }
        val f = frames ?: return@BoxWithConstraints
        val minPx = with(density) { 64.dp.toPx() }
        f.faces.forEachIndexed { index, face ->
            val vb = OverlayTransform.map(face.box, f.imageWidth, f.imageHeight, viewW, viewH, f.mirrored)
            val w = maxOf(vb.width, minPx)
            val h = maxOf(vb.height, minPx)
            val left = vb.left - (w - vb.width) / 2
            val top = vb.top - (h - vb.height) / 2
            val number = selection.numberOf(face.trackingId)
            val style = when {
                face.trackingId in refused -> BoxStyle.REFUSED
                number != null && selection.household -> BoxStyle.HOUSEHOLD
                number != null -> BoxStyle.SELECTED
                else -> BoxStyle.DETECTED
            }
            val desc = if (number != null) stringResource(R.string.cd_face_selected, index + 1) else stringResource(R.string.cd_face_box, index + 1)
            val (color, stroke: Dp) = when (style) {
                BoxStyle.DETECTED -> Color.White to 3.dp
                BoxStyle.SELECTED -> c.accent to 4.dp
                BoxStyle.HOUSEHOLD -> c.primary to 4.dp
                BoxStyle.REFUSED -> c.danger to 4.dp
            }
            Box(
                Modifier
                    .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                    .size(with(density) { w.toDp() }, with(density) { h.toDp() })
                    .testTag("face_${face.trackingId}")
                    .semantics { contentDescription = desc }
                    .clickable { onTap(face.trackingId) }
                    .drawBehind {
                        if (style == BoxStyle.DETECTED) brackets(Color(0xCC1C1917), (stroke + 2.dp).toPx())
                        brackets(color, stroke.toPx())
                    },
            ) {
                when (style) {
                    BoxStyle.SELECTED -> Badge("✓", c.accent, c.onAccent, Modifier.align(Alignment.TopEnd))
                    BoxStyle.HOUSEHOLD -> Badge(number.toString(), c.primary, c.onPrimary, Modifier.align(Alignment.TopEnd))
                    else -> Unit
                }
            }
            if (style == BoxStyle.REFUSED) {
                RemuText(
                    stringResource(R.string.face_under_18),
                    style = RemuType.chip,
                    color = Color.White,
                    modifier = Modifier
                        .offset { IntOffset(left.roundToInt(), (top + h).roundToInt() + 8) }
                        .background(c.danger, RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .testTag("under18_${face.trackingId}"),
                )
            }
        }
    }
}

@Composable
private fun Badge(text: String, bg: Color, fg: Color, modifier: Modifier) {
    Box(modifier.padding(4.dp).size(28.dp).background(bg, CircleShape), contentAlignment = Alignment.Center) {
        RemuText(text, style = RemuType.chip, color = fg)
    }
}

private fun DrawScope.brackets(color: Color, strokePx: Float) {
    val len = minOf(size.width, size.height) * 0.25f
    val w = size.width
    val h = size.height
    val i = strokePx / 2
    fun line(a: Offset, b: Offset) = drawLine(color, a, b, strokePx, StrokeCap.Round)
    line(Offset(i, i), Offset(len, i)); line(Offset(i, i), Offset(i, len))
    line(Offset(w - i, i), Offset(w - len, i)); line(Offset(w - i, i), Offset(w - i, len))
    line(Offset(i, h - i), Offset(len, h - i)); line(Offset(i, h - i), Offset(i, h - len))
    line(Offset(w - i, h - i), Offset(w - len, h - i)); line(Offset(w - i, h - i), Offset(w - i, h - len))
}
