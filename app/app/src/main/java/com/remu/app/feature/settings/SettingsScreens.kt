package com.remu.app.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.remu.app.AppContainer
import com.remu.app.R
import com.remu.app.core.theme.Remu
import com.remu.app.core.theme.RemuType
import com.remu.app.core.ui.HubChip
import com.remu.app.core.ui.PrimaryButton
import com.remu.app.core.ui.RemuCard
import com.remu.app.core.ui.RemuText
import com.remu.app.core.ui.RemuTopBar
import com.remu.app.core.ui.SecondaryButton
import com.remu.app.core.ui.SectionHeading
import com.remu.app.feature.login.LanguageSwitch
import com.remu.app.feature.members.BackIcon
import kotlinx.coroutines.launch

@Composable
private fun Choice(label: String, on: Boolean, tag: String, onClick: () -> Unit) {
    val c = Remu.colors
    Box(
        Modifier.heightIn(min = 48.dp).background(if (on) c.accent else c.surface, CircleShape)
            .clickable(onClick = onClick).testTag(tag).padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) { RemuText(label, style = RemuType.chip, color = if (on) c.onAccent else c.text) }
}

/** Owner settings (TASK-082, TASK-094): staff, device status, language, export, sign-out. */
@Composable
fun OwnerSettingsScreen(
    c: AppContainer,
    onBack: () -> Unit,
    onStaff: () -> Unit,
    onDevice: () -> Unit,
    onExport: () -> Unit,
    onInvite: () -> Unit,
    onSignedOut: () -> Unit,
) {
    val colors = Remu.colors
    val session by c.session.state.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().background(colors.background)) {
        RemuTopBar(stringResource(R.string.settings_title), navigationIcon = { BackIcon(onBack) })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeading(stringResource(R.string.settings_shop))
            RemuCard {
                session.shopName?.let { RemuText(it, style = RemuType.section) }
                session.shopCity?.let { RemuText(it, color = colors.muted) }
                RemuText(stringResource(R.string.settings_versions, stringResource(R.string.script_version), stringResource(R.string.notice_version)), color = colors.muted)
                RemuText(stringResource(R.string.settings_cooldown), color = colors.muted)
            }
            SecondaryButton(stringResource(R.string.settings_staff), onStaff, modifier = Modifier.testTag("settings_staff"))
            SecondaryButton(stringResource(R.string.settings_device), onDevice, modifier = Modifier.testTag("settings_device"))
            SecondaryButton(stringResource(R.string.invite_counter), onInvite, modifier = Modifier.testTag("settings_invite"))
            SecondaryButton(stringResource(R.string.export_consent), onExport, modifier = Modifier.testTag("settings_export"))
            SecondaryButton(stringResource(R.string.print_standee), { StandeePrinter.print(context, session.shopName) })
            LanguageSwitch(c.language, Modifier.testTag("settings_language"))
            RemuText(stringResource(R.string.settings_alert_sound), style = RemuType.chip, color = colors.muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice(stringResource(R.string.alert_vibrate), !session.alertTone, "sound_vibrate") { scope.launch { c.session.update { it.copy(alertTone = false) } } }
                Choice(stringResource(R.string.alert_tone), session.alertTone, "sound_tone") { scope.launch { c.session.update { it.copy(alertTone = true) } } }
            }
        }
        PrimaryButton(stringResource(R.string.sign_out), {
            scope.launch {
                c.stopOwnerRealtime()
                c.owner.signOut()
                c.alerts.clear()
                c.appState.lock()
                onSignedOut()
            }
        }, modifier = Modifier.padding(16.dp).testTag("sign_out"))
    }
}

/** Cashier settings and PIN (docs/DESIGN.md section 6): hub connection, language, camera, Lock. */
@Composable
fun CashierSettingsScreen(c: AppContainer, onBack: () -> Unit, onPairAgain: () -> Unit, onLock: () -> Unit) {
    val colors = Remu.colors
    val session by c.session.state.collectAsState()
    val hub by c.hubStatus.status.collectAsState()
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().background(colors.background)) {
        RemuTopBar(stringResource(R.string.settings_title), navigationIcon = { BackIcon(onBack) })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeading(stringResource(R.string.settings_hub_connection))
            HubChip(hub, Modifier)
            session.hubBaseUrl?.let { RemuText(it, style = RemuType.caption, color = colors.muted) }
            SecondaryButton(stringResource(R.string.pairing_title), onPairAgain)
            LanguageSwitch(c.language)
            RemuText(stringResource(R.string.settings_camera), style = RemuType.chip, color = colors.muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice(stringResource(R.string.camera_back), !session.useFrontCamera, "camera_back") { scope.launch { c.session.update { it.copy(useFrontCamera = false) } } }
                Choice(stringResource(R.string.camera_front), session.useFrontCamera, "camera_front") { scope.launch { c.session.update { it.copy(useFrontCamera = true) } } }
            }
            RemuText(stringResource(R.string.settings_bills_only_count, session.skipFaceCount), color = colors.muted, modifier = Modifier.testTag("skip_count"))
        }
        PrimaryButton(stringResource(R.string.lock), { c.appState.lock(); onLock() }, modifier = Modifier.padding(16.dp).testTag("lock"))
    }
}
