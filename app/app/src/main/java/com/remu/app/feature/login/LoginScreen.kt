package com.remu.app.feature.login

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.remu.app.R
import com.remu.app.core.locale.LanguageManager
import com.remu.app.core.theme.Remu
import com.remu.app.core.theme.RemuType
import com.remu.app.core.ui.InlineError
import com.remu.app.core.ui.PrimaryButton
import com.remu.app.core.ui.RemuText
import com.remu.app.core.ui.RemuTopBar
import com.remu.app.core.ui.SecondaryButton
import com.remu.app.data.state.Mode

@Composable
fun LoginScreen(
    vm: LoginViewModel,
    language: LanguageManager,
    onDestination: (LoginDestination) -> Unit,
    onPairAgain: () -> Unit,
) {
    val s by vm.state.collectAsState()
    val c = Remu.colors
    LaunchedEffect(s.destination) { s.destination?.let { onDestination(it); vm.consumeDestination() } }
    Column(Modifier.fillMaxSize().background(c.background)) {
        RemuTopBar(stringResource(R.string.login_title))
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeOption(stringResource(R.string.mode_cashier), s.mode == Mode.CASHIER, "mode_cashier", Modifier.weight(1f)) { vm.setMode(Mode.CASHIER) }
                ModeOption(stringResource(R.string.mode_owner), s.mode == Mode.OWNER, "mode_owner", Modifier.weight(1f)) { vm.setMode(Mode.OWNER) }
            }
            RemuText(stringResource(R.string.login_pin_label), style = RemuType.section)
            PinDots(s.pin.length)
            when {
                s.waitSeconds > 0 -> InlineError(pluralStringResource(R.plurals.login_wait_seconds, s.waitSeconds, s.waitSeconds), null)
                s.error != null -> {
                    val text = when (s.error!!) {
                        LoginError.WRONG_PIN -> stringResource(R.string.login_wrong_pin)
                        LoginError.HUB_UNREACHABLE -> stringResource(R.string.error_hub_no_reply)
                        LoginError.CERT_CHANGED -> stringResource(R.string.error_cert_changed)
                        LoginError.NEEDS_OWNER_PIN -> stringResource(R.string.login_owner_needs_owner_pin)
                        LoginError.NEEDS_OWNER_PHONE -> stringResource(R.string.login_owner_needs_owner_phone)
                        LoginError.OTHER -> stringResource(R.string.error_generic)
                    }
                    Box(Modifier.testTag("login_error")) { InlineError(text, null) }
                    if (s.error == LoginError.CERT_CHANGED) SecondaryButton(stringResource(R.string.pairing_title), onPairAgain)
                    if (s.error == LoginError.HUB_UNREACHABLE && s.canContinueOffline) {
                        SecondaryButton(stringResource(R.string.login_continue_offline), vm::continueOffline, modifier = Modifier.testTag("login_offline"))
                    }
                }
            }
            PinPad(onDigit = vm::digit, onDelete = vm::delete)
            LanguageSwitch(language)
        }
        PrimaryButton(
            stringResource(R.string.login_unlock), vm::submit,
            enabled = s.pin.length >= 4 && s.waitSeconds == 0, working = s.working,
            modifier = Modifier.padding(16.dp).testTag("pin_unlock"),
        )
    }
}

@Composable
private fun ModeOption(label: String, selected: Boolean, tag: String, modifier: Modifier, onClick: () -> Unit) {
    val c = Remu.colors
    Box(
        modifier
            .heightIn(min = 56.dp)
            .background(if (selected) c.primary else c.surface, RoundedCornerShape(12.dp))
            .border(2.dp, c.primary, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .testTag(tag)
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) { RemuText(label, style = RemuType.button, color = if (selected) c.onPrimary else c.primary) }
}

@Composable
private fun PinDots(count: Int) {
    val c = Remu.colors
    val desc = pluralStringResource(R.plurals.cd_pin_entered, count, count)
    Row(Modifier.semantics { contentDescription = desc }.testTag("pin_dots"), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(6) { i ->
            Box(
                Modifier.size(16.dp).background(if (i < count) c.primary else c.border, CircleShape),
            )
        }
    }
}

@Composable
fun PinPad(onDigit: (Char) -> Unit, onDelete: () -> Unit) {
    val c = Remu.colors
    val rows = listOf("123", "456", "789")
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { d -> PinKey(d.toString(), Modifier.weight(1f), "pin_key_$d") { onDigit(d) } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Spacer(Modifier.weight(1f).height(64.dp))
            PinKey("0", Modifier.weight(1f), "pin_key_0") { onDigit('0') }
            val del = stringResource(R.string.cd_pin_delete)
            Box(
                Modifier.weight(1f).height(64.dp).background(c.surface, RoundedCornerShape(12.dp))
                    .border(1.dp, c.border, RoundedCornerShape(12.dp))
                    .clickable(onClick = onDelete).semantics { contentDescription = del }.testTag("pin_delete"),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = c.text) }
        }
    }
}

@Composable
private fun PinKey(label: String, modifier: Modifier, tag: String, onClick: () -> Unit) {
    val c = Remu.colors
    Box(
        modifier.height(64.dp).fillMaxWidth().background(c.surface, RoundedCornerShape(12.dp))
            .border(1.dp, c.border, RoundedCornerShape(12.dp)).clickable(onClick = onClick).testTag(tag),
        contentAlignment = Alignment.Center,
    ) { RemuText(label, style = RemuType.screenTitle) }
}
