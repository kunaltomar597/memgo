package com.remu.app.feature.setup

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.remu.app.AppContainer
import com.remu.app.R
import com.remu.app.core.theme.Remu
import com.remu.app.core.theme.RemuType
import com.remu.app.core.ui.EmptyState
import com.remu.app.core.ui.InlineError
import com.remu.app.core.ui.PrimaryButton
import com.remu.app.core.ui.RemuCard
import com.remu.app.core.ui.RemuField
import com.remu.app.core.ui.RemuText
import com.remu.app.core.ui.RemuTopBar
import com.remu.app.core.ui.SecondaryButton
import com.remu.app.core.ui.SkeletonRows
import com.remu.app.feature.members.BackIcon
import com.remu.app.feature.pairing.pairErrorText
import com.remu.app.feature.settings.InviteContent
import com.remu.app.feature.settings.InviteViewModel
import com.remu.app.feature.settings.StandeePrinter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
private fun stepTitle(step: SetupStep): String = when (step) {
    SetupStep.SHOP -> stringResource(R.string.setup_step_shop)
    SetupStep.PAIR -> stringResource(R.string.setup_step_pair)
    SetupStep.STREAM -> stringResource(R.string.setup_step_stream)
    SetupStep.AIM -> stringResource(R.string.setup_step_aim)
    SetupStep.COUNTER -> stringResource(R.string.setup_step_counter)
    SetupStep.STANDEE -> stringResource(R.string.setup_step_standee)
}

@Composable
private fun setupErrorText(e: SetupError): String = when (e) {
    SetupError.FIELDS -> stringResource(R.string.error_generic)
    SetupError.CLOUD -> stringResource(R.string.error_cloud_no_reply)
    SetupError.PAIR_CODE -> stringResource(R.string.pairing_wrong_code)
    SetupError.HUB -> stringResource(R.string.error_hub_no_reply)
    SetupError.PIN_INVALID -> stringResource(R.string.error_pin_invalid)
    SetupError.PIN_MISMATCH -> stringResource(R.string.error_pin_mismatch)
    SetupError.NAME -> stringResource(R.string.error_name_empty)
    SetupError.NO_STREAMS -> stringResource(R.string.setup_stream_empty)
    SetupError.PIN_WRONG -> stringResource(R.string.login_wrong_pin)
    SetupError.NOT_OWNER_PIN -> stringResource(R.string.login_owner_needs_owner_pin)
    SetupError.HUB_LOCKED -> stringResource(R.string.banner_hub_locked_support)
}

@Composable
fun SetupScreen(vm: SetupViewModel, invite: () -> InviteViewModel, c: AppContainer, onBack: () -> Unit, onFinished: () -> Unit) {
    val s by vm.state.collectAsState()
    val colors = Remu.colors
    val context = LocalContext.current
    LaunchedEffect(s.finished) { if (s.finished) onFinished() }
    Column(Modifier.fillMaxSize().background(colors.background)) {
        RemuTopBar(stringResource(R.string.setup_title), navigationIcon = { BackIcon { if (s.step == SetupStep.SHOP) onBack() else vm.back() } })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RemuText(stringResource(R.string.setup_step, s.step.ordinal + 1, SetupStep.entries.size), style = RemuType.caption, color = colors.muted, modifier = Modifier.testTag("setup_step"))
            RemuText(stringResource(R.string.setup_elapsed, s.elapsedMin), style = RemuType.caption, color = colors.muted, modifier = Modifier.testTag("setup_elapsed"))
            RemuText(stepTitle(s.step), style = RemuType.screenTitle, modifier = Modifier.testTag("setup_title_${s.step.name}"))
            when (s.claim) {
                HubClaim.THIS_SHOP -> RemuText(stringResource(R.string.device_claimed_this_shop), style = RemuType.caption, color = colors.success, modifier = Modifier.testTag("setup_claim_this"))
                HubClaim.UNCLAIMED -> RemuText(stringResource(R.string.device_not_claimed), style = RemuType.caption, color = colors.muted, modifier = Modifier.testTag("setup_claim_none"))
                HubClaim.OTHER_SHOP -> RemuText(stringResource(R.string.hub_other_shop), style = RemuType.caption, color = colors.danger, modifier = Modifier.testTag("setup_claim_other"))
                null -> Unit
            }
            s.error?.let { InlineError(setupErrorText(it), null) }
            s.pairError?.let { Box(Modifier.testTag("setup_pair_error")) { InlineError(pairErrorText(it), null) } }
            if (s.needsOwnerLogin) {
                RemuText(stringResource(R.string.setup_owner_pin_enter), style = RemuType.section, modifier = Modifier.testTag("setup_owner_login"))
                RemuField(s.pin, { v -> vm.update { it.copy(pin = v.filter(Char::isDigit).take(6)) } }, stringResource(R.string.login_pin_label),
                    keyboardType = KeyboardType.NumberPassword, visualTransformation = PasswordVisualTransformation(), tag = "setup_login_pin")
            } else when (s.step) {
                SetupStep.SHOP -> {
                    RemuField(s.shopName, { v -> vm.update { it.copy(shopName = v) } }, stringResource(R.string.field_shop_name), tag = "setup_shop_name")
                    RemuField(s.city, { v -> vm.update { it.copy(city = v) } }, stringResource(R.string.field_city), tag = "setup_city")
                    RemuText(stringResource(R.string.field_notice_language), style = RemuType.chip, color = colors.muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill(stringResource(R.string.notice_language_hi), s.noticeLanguage == "hi", "setup_lang_hi") { vm.update { it.copy(noticeLanguage = "hi") } }
                        Pill(stringResource(R.string.notice_language_en), s.noticeLanguage == "en", "setup_lang_en") { vm.update { it.copy(noticeLanguage = "en") } }
                    }
                }
                SetupStep.PAIR -> if (!s.needsOwnerPin) {
                    RemuText(stringResource(R.string.pairing_code_hint_owner))
                    RemuField(s.host, { v -> vm.update { it.copy(host = v.trim()) } }, stringResource(R.string.pairing_host_label), keyboardType = KeyboardType.Uri, tag = "setup_host")
                    RemuField(s.code, { v -> vm.update { it.copy(code = v.trim()) } }, stringResource(R.string.pairing_code_label), tag = "setup_code")
                } else {
                    RemuText(stringResource(R.string.setup_owner_pin_title), style = RemuType.section)
                    RemuField(s.ownerName, { v -> vm.update { it.copy(ownerName = v) } }, stringResource(R.string.field_your_name), tag = "setup_owner_name")
                    RemuField(s.pin, { v -> vm.update { it.copy(pin = v.filter(Char::isDigit).take(6)) } }, stringResource(R.string.field_pin),
                        keyboardType = KeyboardType.NumberPassword, visualTransformation = PasswordVisualTransformation(), tag = "setup_pin")
                    RemuField(s.pin2, { v -> vm.update { it.copy(pin2 = v.filter(Char::isDigit).take(6)) } }, stringResource(R.string.field_pin_confirm),
                        keyboardType = KeyboardType.NumberPassword, visualTransformation = PasswordVisualTransformation(), tag = "setup_pin2")
                }
                SetupStep.STREAM -> {
                    RemuText(stringResource(R.string.setup_stream_body))
                    val list = s.streams
                    when {
                        list == null -> SkeletonRows(2)
                        list.isEmpty() -> {
                            EmptyState(stringResource(R.string.setup_stream_empty))
                            SecondaryButton(stringResource(R.string.action_retry), vm::discover)
                        }
                        else -> list.forEachIndexed { i, st ->
                            val on = s.chosenStream == st.url
                            RemuCard(Modifier.clickable { vm.chooseStream(st.url) }.testTag("stream_$i"), stripe = if (on) colors.accent else null) {
                                RemuText(st.label ?: st.id ?: "${i + 1}", style = RemuType.section)
                            }
                        }
                    }
                }
                SetupStep.AIM -> {
                    RemuText(stringResource(R.string.setup_aim_body))
                    AimPreview(c)
                }
                SetupStep.COUNTER -> {
                    val inviteVm = remember { invite() }
                    InviteContent(inviteVm)
                }
                SetupStep.STANDEE -> {
                    RemuText(stringResource(R.string.setup_standee_body))
                    SecondaryButton(stringResource(R.string.print_standee), { StandeePrinter.print(context, c.session.current.shopName) }, modifier = Modifier.testTag("setup_print"))
                }
            }
        }
        val (label, action, enabled) = if (s.needsOwnerLogin) Triple(stringResource(R.string.login_unlock), vm::unlockWithOwnerPin, true) else when (s.step) {
            SetupStep.SHOP -> Triple(stringResource(R.string.setup_create_shop), vm::createShop, true)
            SetupStep.PAIR -> if (s.needsOwnerPin) Triple(stringResource(R.string.action_save), vm::createOwnerPin, true) else Triple(stringResource(R.string.pairing_pair), vm::pairHub, true)
            SetupStep.STREAM -> Triple(stringResource(R.string.action_next), vm::saveStream, s.chosenStream != null)
            SetupStep.AIM -> Triple(stringResource(R.string.setup_aim_ok), { vm.aimDone(); Unit }, true)
            SetupStep.COUNTER -> Triple(stringResource(R.string.action_next), { vm.counterDone(); Unit }, true)
            SetupStep.STANDEE -> Triple(stringResource(R.string.setup_finish), { vm.finish(); Unit }, true)
        }
        PrimaryButton(label, action, enabled = enabled, working = s.working, modifier = Modifier.padding(16.dp).testTag("setup_primary"))
    }
}

@Composable
private fun Pill(label: String, on: Boolean, tag: String, onClick: () -> Unit) {
    val c = Remu.colors
    Box(
        Modifier.heightIn(min = 48.dp).background(if (on) c.accent else c.surface, CircleShape).clickable(onClick = onClick)
            .testTag(tag).padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) { RemuText(label, style = RemuType.chip, color = if (on) c.onAccent else c.text) }
}

/** The hub's MJPEG aim preview, decoded frame by frame in memory and never saved. */
@Composable
private fun AimPreview(c: AppContainer) {
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(Unit) {
        try {
            c.hub.previewFrames().collect { jpeg ->
                val bmp = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) }
                jpeg.fill(0)
                if (bmp != null) frame = bmp
            }
        } catch (_: Exception) { }
    }
    DisposableEffect(Unit) { onDispose { frame = null } }
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color(0xFF1C1917)).testTag("aim_preview"), contentAlignment = Alignment.Center) {
        val f = frame
        if (f == null) RemuText(stringResource(R.string.setup_aim_waiting), color = Color.White)
        else Image(f.asImageBitmap(), contentDescription = stringResource(R.string.cd_aim_preview), modifier = Modifier.fillMaxSize())
    }
}
