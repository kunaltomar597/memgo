package com.remu.app.feature.alerts

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.remu.app.R
import com.remu.app.core.format.friendlyDateText
import com.remu.app.core.theme.Remu
import com.remu.app.core.theme.RemuType
import com.remu.app.core.ui.EmptyState
import com.remu.app.core.ui.HubChip
import com.remu.app.core.ui.OwnerBanners
import com.remu.app.core.ui.RemuCard
import com.remu.app.core.ui.RemuText
import com.remu.app.core.ui.RemuTopBar
import com.remu.app.core.ui.SecondaryButton
import com.remu.app.core.ui.SecureScreen
import com.remu.app.core.ui.StatusChip

@Composable
fun OwnerHomeScreen(
    vm: OwnerHomeViewModel,
    onMembers: () -> Unit,
    onSettings: () -> Unit,
    onOpenAlert: (visitId: String, memberId: String) -> Unit,
) {
    val s by vm.state.collectAsState()
    val c = Remu.colors
    SecureScreen()
    Column(Modifier.fillMaxSize().background(c.background)) {
        RemuTopBar(
            stringResource(R.string.alerts_title),
            actions = {
                IconButton(onClick = onMembers, modifier = Modifier.testTag("action_members")) {
                    Icon(Icons.Filled.Person, contentDescription = stringResource(R.string.cd_members))
                }
                IconButton(onClick = onSettings, modifier = Modifier.testTag("action_settings")) {
                    Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.cd_settings))
                }
            },
        )
        OwnerBanners(s.hub, s.phoneOnline)
        LazyColumn(
            Modifier.fillMaxSize().testTag("alert_list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                RemuText(
                    pluralStringResource(R.plurals.alerts_visits_today, s.today.visits, s.today.visits) + ", " +
                        pluralStringResource(R.plurals.alerts_greeted_count, s.today.greeted, s.today.greeted),
                    style = RemuType.section, modifier = Modifier.testTag("alerts_summary"),
                )
            }
            item { HubChip(s.hub, Modifier) }
            item { CloudHubStatus(s) }
            item { NotificationPermissionCard() }
            if (s.alerts.isEmpty()) item { EmptyState(stringResource(R.string.alerts_empty)) }
            items(s.alerts, key = { it.visitId }) { a ->
                AlertCard(
                    item = a, now = s.now, stale = vm.isStale(a), sending = a.visitId in s.sending, failed = s.failed == a.visitId,
                    onLan = s.hub.onLan,
                    onGreeted = { vm.greeted(a.visitId) }, onNotThem = { vm.notThem(a.visitId) },
                    onOpen = { onOpenAlert(a.visitId, a.payload.memberId) },
                    onPlayClip = { onOpenAlert(a.visitId, a.payload.memberId) },
                )
            }
        }
    }
}

@Composable
private fun CloudHubStatus(s: OwnerHomeUiState) {
    val c = Remu.colors
    val d = s.device ?: return
    Column(Modifier.testTag("cloud_hub_status"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        StatusChip(stringResource(if (d.online) R.string.hub_online else R.string.hub_offline), if (d.online) c.success else c.danger)
        d.lastSeenAt?.let { RemuText(stringResource(R.string.last_seen, friendlyDateText(it, s.now)), style = RemuType.caption, color = c.muted) }
        if (d.chainBreak) RemuText(stringResource(R.string.device_chain_break), color = c.danger)
    }
}

/** Android 13 and later need permission for the heads-up alert. */
@Composable
private fun NotificationPermissionCard() {
    if (Build.VERSION.SDK_INT < 33) return
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    if (granted) return
    RemuCard {
        RemuText(stringResource(R.string.notification_permission))
        SecondaryButton(stringResource(R.string.allow_notifications), { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) })
    }
}
