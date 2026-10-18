package com.remu.app.feature.alerts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.remu.app.R
import com.remu.app.core.theme.Remu
import com.remu.app.core.ui.EmptyState
import com.remu.app.core.ui.OwnerBanners
import com.remu.app.core.ui.RemuTopBar
import com.remu.app.core.ui.SecureScreen
import com.remu.app.core.ui.SectionHeading
import com.remu.app.feature.members.BackIcon
import com.remu.app.feature.members.MemberDetailViewModel
import com.remu.app.feature.timeline.TimelineSection

/** The full alert card, the member's timeline and the clip player on the LAN. */
@Composable
fun AlertDetailScreen(home: OwnerHomeViewModel, member: MemberDetailViewModel, visitId: String, onBack: () -> Unit) {
    val s by home.state.collectAsState()
    val m by member.state.collectAsState()
    val c = Remu.colors
    SecureScreen()
    val item = s.alerts.firstOrNull { it.visitId == visitId }
    Column(Modifier.fillMaxSize().background(c.background)) {
        RemuTopBar(stringResource(R.string.alert_detail_title), navigationIcon = { BackIcon(onBack) })
        OwnerBanners(s.hub, s.phoneOnline)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (item == null) EmptyState(stringResource(R.string.alerts_empty))
            else AlertCard(
                item = item, now = s.now, stale = home.isStale(item), sending = visitId in s.sending, failed = s.failed == visitId,
                onLan = false, onGreeted = { home.greeted(visitId) }, onNotThem = { home.notThem(visitId); onBack() },
                onOpen = null, onPlayClip = null,
            )
            SectionHeading(stringResource(R.string.timeline_heading))
            TimelineSection(m.timeline, m.hub.onLan, m.clip, m.staffNames, member::canPlay, member::playClip, member::closeClip)
        }
    }
}
