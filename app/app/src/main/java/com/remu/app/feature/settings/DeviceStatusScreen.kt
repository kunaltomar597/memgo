package com.remu.app.feature.settings

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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.remu.app.R
import com.remu.app.core.format.friendlyDateText
import com.remu.app.core.theme.Remu
import com.remu.app.core.ui.HubIdentityBanner
import com.remu.app.core.ui.HubLockedBanner
import com.remu.app.core.ui.PrimaryButton
import com.remu.app.core.ui.RemuCard
import com.remu.app.core.ui.RemuText
import com.remu.app.core.ui.RemuTopBar
import com.remu.app.core.ui.SecondaryButton
import com.remu.app.core.ui.StatusChip
import com.remu.app.feature.members.BackIcon

@Composable
fun DeviceStatusScreen(vm: DeviceStatusViewModel, onBack: () -> Unit, onPairHub: () -> Unit) {
    val s by vm.state.collectAsState()
    val c = Remu.colors
    Column(Modifier.fillMaxSize().background(c.background)) {
        RemuTopBar(stringResource(R.string.settings_device), navigationIcon = { BackIcon(onBack) })
        HubIdentityBanner(s.identity)
        if (s.hub.lockedOut) HubLockedBanner()
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RemuCard(Modifier.testTag("device_lan")) {
                val online = s.hub.reachable == true
                StatusChip(stringResource(if (online) R.string.hub_online else R.string.hub_offline), if (online) c.success else c.danger)
                s.hub.health?.let { h ->
                    h.version?.let { RemuText(stringResource(R.string.device_version, it)) }
                    h.gallerySize?.let { RemuText(stringResource(R.string.device_gallery, it), modifier = Modifier.testTag("device_gallery")) }
                    RemuText(stringResource(if (h.internet) R.string.device_internet_ok else R.string.device_internet_down))
                    RemuText(stringResource(R.string.device_outbox, h.outboxPending))
                    h.stream?.let {
                        val label = when (it) {
                            "ok" -> stringResource(R.string.stream_ok)
                            "connecting" -> stringResource(R.string.stream_connecting)
                            else -> stringResource(R.string.stream_down)
                        }
                        RemuText(stringResource(R.string.device_stream, label))
                    }
                    if (h.locked) RemuText(stringResource(R.string.device_locked), color = c.danger)
                    when {
                        s.otherShop -> RemuText(stringResource(R.string.hub_other_shop), color = c.danger, modifier = Modifier.testTag("device_other_shop"))
                        h.cloudShopId != null -> RemuText(stringResource(R.string.device_claimed_this_shop), modifier = Modifier.testTag("device_claimed"))
                        else -> RemuText(stringResource(R.string.device_not_claimed), color = c.muted, modifier = Modifier.testTag("device_not_claimed"))
                    }
                }
                if (s.hub.certificateChanged) RemuText(stringResource(R.string.error_cert_changed), color = c.danger)
            }
            RemuCard(Modifier.testTag("device_cloud")) {
                val d = s.device
                if (d == null) RemuText(stringResource(R.string.device_cloud_unknown), color = c.muted)
                else {
                    StatusChip(stringResource(if (d.online) R.string.hub_online else R.string.hub_offline), if (d.online) c.success else c.danger)
                    d.lastSeenAt?.let { RemuText(stringResource(R.string.last_seen, friendlyDateText(it))) }
                    d.hubVersion?.let { RemuText(stringResource(R.string.device_version, it)) }
                    if (d.locked) RemuText(stringResource(R.string.device_locked), color = c.danger)
                    if (d.chainBreak) RemuText(stringResource(R.string.device_chain_break), color = c.danger)
                }
            }
            SecondaryButton(stringResource(R.string.action_retry), vm::refresh, working = s.refreshing)
        }
        PrimaryButton(stringResource(R.string.pair_hub), onPairHub, modifier = Modifier.padding(16.dp).testTag("device_pair"))
    }
}
