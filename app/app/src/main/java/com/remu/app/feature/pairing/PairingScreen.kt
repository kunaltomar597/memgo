package com.remu.app.feature.pairing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.remu.app.R
import com.remu.app.core.theme.Remu
import com.remu.app.core.theme.RemuType
import com.remu.app.core.ui.InlineError
import com.remu.app.core.ui.PrimaryButton
import com.remu.app.core.ui.RemuField
import com.remu.app.core.ui.RemuText
import com.remu.app.core.ui.RemuTopBar
import com.remu.app.core.ui.SecondaryButton
import com.remu.app.feature.members.BackIcon

@Composable
fun pairErrorText(e: PairError): String = when (e) {
    PairError.HOST_INVALID -> stringResource(R.string.pairing_host_invalid)
    PairError.CODE_EMPTY -> stringResource(R.string.pairing_code_empty)
    PairError.WRONG_CODE -> stringResource(R.string.pairing_wrong_code)
    PairError.TOO_MANY -> stringResource(R.string.pairing_too_many)
    PairError.CERT_MISMATCH -> stringResource(R.string.pairing_cert_mismatch)
    PairError.UNREACHABLE -> stringResource(R.string.error_hub_no_reply)
    PairError.FAILED -> stringResource(R.string.error_generic)
}

@Composable
fun PairingScreen(vm: PairingViewModel, onBack: () -> Unit, onPaired: () -> Unit) {
    val s by vm.state.collectAsState()
    val c = Remu.colors
    LaunchedEffect(s.done) { if (s.done) onPaired() }
    Column(Modifier.fillMaxSize().background(c.background)) {
        RemuTopBar(stringResource(R.string.pairing_title), navigationIcon = { BackIcon(onBack) })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RemuText(stringResource(if (vm.kind == PairKind.OWNER) R.string.pairing_code_hint_owner else R.string.pairing_code_hint_counter))
            when {
                s.found != null -> RemuText(stringResource(R.string.pairing_found, s.found!!), style = RemuType.caption, color = c.success)
                s.searching -> RemuText(stringResource(R.string.pairing_searching), style = RemuType.caption, color = c.muted)
            }
            RemuField(s.host, vm::onHost, stringResource(R.string.pairing_host_label), keyboardType = KeyboardType.Uri, tag = "pair_host")
            RemuField(s.code, vm::onCode, stringResource(R.string.pairing_code_label), tag = "pair_code")
            s.error?.let { InlineError(pairErrorText(it), null) }
            if (vm.kind == PairKind.COUNTER) {
                if (s.scanning) QrScanner(vm::onScanned)
                else SecondaryButton(stringResource(R.string.pairing_scan_qr), { vm.scan(true) }, modifier = Modifier.testTag("pair_scan"))
            }
        }
        PrimaryButton(stringResource(R.string.pairing_pair), vm::pair, working = s.working, modifier = Modifier.padding(16.dp).testTag("pair_submit"))
    }
}
