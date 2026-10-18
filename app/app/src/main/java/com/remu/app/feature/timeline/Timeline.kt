package com.remu.app.feature.timeline

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.remu.app.R
import com.remu.app.core.format.Formatters
import com.remu.app.core.format.currentLocale
import com.remu.app.core.theme.Remu
import com.remu.app.core.theme.RemuType
import com.remu.app.core.ui.EmptyState
import com.remu.app.core.ui.InlineError
import com.remu.app.core.ui.RemuCard
import com.remu.app.core.ui.RemuText
import com.remu.app.core.ui.SecondaryButton
import com.remu.app.feature.members.ClipState
import com.remu.app.feature.members.TimelineEntry
import com.remu.app.network.hub.Visit

/**
 * Visits and purchases, newest first: "18 Oct 2026, 11:42 am · Gate · Greeted by Rajesh" and
 * "18 Oct 2026 · ₹20,000 · gold bangles · Diwali" (docs/DESIGN.md section 5). No score shown.
 */
@Composable
fun TimelineSection(
    entries: List<TimelineEntry>,
    onLan: Boolean,
    clip: ClipState,
    staffNames: Map<String, String>,
    canPlay: (Visit) -> Boolean,
    onPlay: (Visit) -> Unit,
    onCloseClip: () -> Unit,
) {
    val c = Remu.colors
    val locale = currentLocale()
    if (entries.isEmpty()) { EmptyState(stringResource(R.string.timeline_empty)); return }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.testTag("timeline")) {
        entries.forEach { e ->
            when (e) {
                is TimelineEntry.VisitEntry -> {
                    val v = e.visit
                    val outcome = when {
                        v.notThem -> stringResource(R.string.not_them)
                        v.greeted && v.greetedBy != null -> stringResource(R.string.timeline_greeted_by, staffNames[v.greetedBy] ?: v.greetedBy)
                        v.greeted -> stringResource(R.string.greeted)
                        else -> stringResource(R.string.timeline_not_greeted)
                    }
                    val line = listOf(Formatters.dateTime(e.at, locale), stringResource(R.string.alert_gate), outcome).joinToString(" · ")
                    RemuCard(Modifier.testTag("visit_${v.id}")) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Home, contentDescription = stringResource(R.string.cd_visit), tint = c.primary)
                            RemuText(line, modifier = Modifier.padding(start = 12.dp))
                        }
                        if (v.hasClip) {
                            when {
                                !onLan -> RemuText(stringResource(R.string.clips_lan_only), style = RemuType.caption, color = c.muted, modifier = Modifier.testTag("clip_lan_only_${v.id}"))
                                clip is ClipState.Ready && clip.visitId == v.id -> {
                                    ClipPlayer(clip.bytes)
                                    SecondaryButton(stringResource(R.string.action_close), onCloseClip)
                                }
                                clip is ClipState.Loading && clip.visitId == v.id -> RemuText(stringResource(R.string.clip_loading), color = c.muted)
                                clip is ClipState.Failed && clip.visitId == v.id -> InlineError(stringResource(R.string.clip_failed), { onPlay(v) })
                                canPlay(v) -> SecondaryButton(stringResource(R.string.play_clip), { onPlay(v) }, modifier = Modifier.testTag("play_clip_${v.id}"))
                            }
                        }
                    }
                }
                is TimelineEntry.PurchaseEntry -> {
                    val p = e.purchase
                    val parts = listOfNotNull(
                        Formatters.date(e.at, locale),
                        p.amount?.let { Formatters.rupees(it) },
                        p.items?.takeIf { it.isNotBlank() },
                        p.occasion?.takeIf { it.isNotBlank() },
                    )
                    RemuCard(Modifier.testTag("purchase_${p.id}")) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Filled.ShoppingCart, contentDescription = stringResource(R.string.cd_purchase), tint = c.primary)
                            RemuText(parts.joinToString(" · "), modifier = Modifier.padding(start = 12.dp))
                        }
                        p.billNo?.let { RemuText(it, style = RemuType.caption, color = c.muted) }
                    }
                }
            }
        }
    }
}
