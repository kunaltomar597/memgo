package com.remu.app.feature.members

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.remu.app.R
import com.remu.app.core.theme.Remu
import com.remu.app.core.theme.RemuType
import com.remu.app.core.ui.InlineError
import com.remu.app.core.ui.PrimaryButton
import com.remu.app.core.ui.RemuCard
import com.remu.app.core.ui.RemuField
import com.remu.app.core.ui.RemuText
import com.remu.app.core.ui.RemuTextButton
import com.remu.app.core.ui.RemuTopBar
import com.remu.app.core.ui.SecondaryButton
import com.remu.app.core.ui.SectionHeading
import com.remu.app.core.ui.SecureScreen
import com.remu.app.core.ui.SkeletonRows

@Composable
fun HouseholdScreen(vm: HouseholdViewModel, onBack: () -> Unit, onAddAdult: (householdId: String, label: String) -> Unit) {
    val s by vm.state.collectAsState()
    val c = Remu.colors
    SecureScreen()
    Column(Modifier.fillMaxSize().background(c.background)) {
        RemuTopBar(stringResource(R.string.household_title), navigationIcon = { BackIcon(onBack) })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                s.loading && s.members.isEmpty() -> SkeletonRows()
                s.error != null && s.members.isEmpty() -> InlineError(loadErrorText(s.error!!), vm::load)
                else -> {
                    if (s.saved) RemuText(stringResource(R.string.household_saved), color = c.success, modifier = Modifier.testTag("household_saved"))
                    RemuField(s.label, vm::onLabel, stringResource(R.string.field_household_label),
                        error = if (s.labelTried && s.label.isBlank()) stringResource(R.string.error_household_label) else null, tag = "household_label")
                    SectionHeading(stringResource(R.string.household_members))
                    s.visibleMembers.forEach { m ->
                        RemuCard(Modifier.testTag("household_member_${m.id}")) {
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { vm.onPayer(m.id) }.testTag("payer_pick_${m.id}"),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = s.payerId == m.id, onClick = { vm.onPayer(m.id) },
                                    colors = RadioButtonDefaults.colors(selectedColor = c.primary))
                                Column(Modifier.weight(1f)) {
                                    RemuText(m.name)
                                    if (s.payerId == m.id) RemuText(stringResource(R.string.household_payer), style = RemuType.caption, color = c.primary)
                                }
                            }
                            if (s.householdId != null && s.visibleMembers.size > 1) {
                                RemuTextButton(stringResource(R.string.household_remove_from), { vm.removeFromHousehold(m.id) },
                                    modifier = Modifier.testTag("household_remove_${m.id}"))
                            }
                        }
                    }
                    s.householdId?.let { id ->
                        SecondaryButton(stringResource(R.string.household_add_adult), { onAddAdult(id, s.label) }, modifier = Modifier.testTag("household_add_adult"))
                    }
                    when (s.saveError) {
                        SaveError.CONFLICT -> InlineError(stringResource(R.string.household_conflict), null)
                        SaveError.HUB -> InlineError(stringResource(R.string.error_hub_no_reply), vm::save)
                        null -> Unit
                    }
                }
            }
        }
        PrimaryButton(stringResource(R.string.action_save), vm::save, working = s.saving,
            enabled = !s.loading, modifier = Modifier.padding(16.dp).testTag("household_save"))
    }
}
