package com.remu.app.feature.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.remu.app.R
import com.remu.app.core.theme.RemuType
import com.remu.app.core.ui.InlineError
import com.remu.app.core.ui.RemuText
import com.remu.app.core.ui.SecondaryButton
import com.remu.app.core.ui.SkeletonRows

@Composable
fun InviteContent(vm: InviteViewModel) {
    val s by vm.state.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        RemuText(stringResource(R.string.setup_counter_body))
        when {
            s.loading -> SkeletonRows(1)
            s.failed -> InlineError(stringResource(R.string.error_hub_no_reply), vm::newCode)
            s.link != null -> {
                val bmp = remember(s.link) { InviteViewModel.qr(s.link!!) }
                Image(bmp.asImageBitmap(), contentDescription = stringResource(R.string.cd_invite_qr), modifier = Modifier.size(240.dp).testTag("invite_qr"))
                RemuText(stringResource(R.string.setup_counter_code, s.code.orEmpty()), style = RemuType.section, modifier = Modifier.testTag("invite_code"))
                RemuText(stringResource(R.string.setup_counter_expires), style = RemuType.caption)
                SecondaryButton(stringResource(R.string.setup_counter_new_code), vm::newCode)
            }
        }
    }
}

@Composable
fun InviteScreen(vm: InviteViewModel, onBack: () -> Unit) {
    val c = com.remu.app.core.theme.Remu.colors
    Column(Modifier.then(Modifier).background(c.background).fillMaxSize()) {
        com.remu.app.core.ui.RemuTopBar(stringResource(R.string.invite_counter), navigationIcon = { com.remu.app.feature.members.BackIcon(onBack) })
        Column(Modifier.padding(16.dp)) { InviteContent(vm) }
    }
}
