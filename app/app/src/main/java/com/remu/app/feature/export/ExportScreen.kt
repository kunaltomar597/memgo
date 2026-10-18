package com.remu.app.feature.export

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.remu.app.R
import com.remu.app.core.format.dateText
import com.remu.app.core.theme.Remu
import com.remu.app.core.ui.InlineError
import com.remu.app.core.ui.PrimaryButton
import com.remu.app.core.ui.RemuText
import com.remu.app.core.ui.RemuTopBar
import com.remu.app.feature.members.BackIcon

@Composable
fun ExportScreen(vm: ExportViewModel, onBack: () -> Unit) {
    val s by vm.state.collectAsState()
    val c = Remu.colors
    Column(Modifier.fillMaxSize().background(c.background)) {
        RemuTopBar(stringResource(R.string.export_title), navigationIcon = { BackIcon(onBack) })
        Column(Modifier.weight(1f).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RemuText(stringResource(R.string.export_body))
            RemuText(
                s.lastExportAt?.let { stringResource(R.string.export_last, dateText(it)) } ?: stringResource(R.string.export_never),
                color = c.muted, modifier = Modifier.testTag("export_last"),
            )
            if (s.working) RemuText(stringResource(R.string.export_working), color = c.muted, modifier = Modifier.testTag("export_working"))
            if (s.done) RemuText(stringResource(R.string.export_ready), color = c.success, modifier = Modifier.testTag("export_done"))
            if (s.failed) InlineError(stringResource(R.string.export_failed), vm::export)
        }
        PrimaryButton(stringResource(R.string.export_button), vm::export, working = s.working, modifier = Modifier.padding(16.dp).testTag("export_button"))
    }
}
