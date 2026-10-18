package com.remu.app.feature.alerts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.remu.app.R
import com.remu.app.core.format.Formatters
import com.remu.app.core.format.currentLocale
import com.remu.app.core.format.dateWords
import com.remu.app.core.theme.Remu
import com.remu.app.core.theme.RemuType
import com.remu.app.core.ui.InlineError
import com.remu.app.core.ui.PrimaryButton
import com.remu.app.core.ui.RemuCard
import com.remu.app.core.ui.RemuText
import com.remu.app.core.ui.RemuTextButton
import com.remu.app.core.ui.SecondaryButton
import com.remu.app.core.ui.StatusChip
import com.remu.app.data.alerts.AlertItem
import com.remu.app.data.alerts.Feedback
import java.time.Instant

@Composable
fun alertLabels(): AlertLabels = AlertLabels(
    lastVisit = stringResource(R.string.alert_last_visit),
    note = stringResource(R.string.alert_note),
    words = dateWords(),
    locale = currentLocale(),
)

/**
 * One visit card (docs/DESIGN.md section 5): name at 28sp, then the rest of the brief's line,
 * the note up to 3 lines with "More", "2 min ago · Gate", and Greeted and Not them at the bottom.
 * The card's accessibility text is the exact one-line format. Older than 10 minutes greys out.
 */
@Composable
fun AlertCard(
    item: AlertItem,
    now: Instant,
    stale: Boolean,
    sending: Boolean,
    failed: Boolean,
    onLan: Boolean,
    onGreeted: () -> Unit,
    onNotThem: () -> Unit,
    onOpen: (() -> Unit)?,
    onPlayClip: (() -> Unit)?,
) {
    val c = Remu.colors
    val text = AlertCardFormatter.format(item.payload, now, alertLabels())
    var expanded by rememberSaveable(item.visitId) { mutableStateOf(false) }
    RemuCard(
        Modifier
            .alpha(if (stale) 0.6f else 1f)
            .then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier)
            .semantics { contentDescription = text.full }
            .testTag("alert_${item.visitId}"),
        stripe = if (item.feedback == null) c.accent else null,
    ) {
        RemuText(text.name, style = RemuType.alertName, modifier = Modifier.testTag("alert_name"))
        if (text.details.isNotEmpty()) RemuText(text.details, color = c.muted, modifier = Modifier.testTag("alert_details"))
        text.note?.let {
            RemuText(it, maxLines = if (expanded) Int.MAX_VALUE else 3, modifier = Modifier.testTag("alert_note"))
            if (it.length > 120) RemuTextButton(stringResource(if (expanded) R.string.alert_less else R.string.alert_more), { expanded = !expanded })
        }
        val ago = Formatters.ago(item.receivedAt, now, dateWords(), currentLocale())
        RemuText("$ago · ${stringResource(R.string.alert_gate)}", style = RemuType.caption, color = c.muted)
        if (onLan && onPlayClip != null) {
            RemuTextButton(stringResource(R.string.play_clip), onPlayClip, modifier = Modifier.testTag("alert_play_${item.visitId}"))
        }
        when (item.feedback) {
            Feedback.GREETED -> StatusChip(stringResource(R.string.greeted), c.success, Modifier.testTag("chip_greeted"))
            Feedback.NOT_THEM -> StatusChip(stringResource(R.string.not_them), c.muted)
            null -> {
                if (failed) InlineError(stringResource(R.string.error_generic), null)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PrimaryButton(stringResource(R.string.greeted), onGreeted, working = sending, modifier = Modifier.weight(1f).testTag("greeted_${item.visitId}"))
                    SecondaryButton(stringResource(R.string.not_them), onNotThem, enabled = !sending, modifier = Modifier.weight(1f).testTag("not_them_${item.visitId}"))
                }
            }
        }
    }
}
