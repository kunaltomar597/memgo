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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.remu.app.R
import com.remu.app.core.theme.Remu
import com.remu.app.core.theme.RemuType
import com.remu.app.core.ui.Banner
import com.remu.app.core.ui.BannerKind
import com.remu.app.core.ui.EmptyState
import com.remu.app.core.ui.InlineError
import com.remu.app.core.ui.PrimaryButton
import com.remu.app.core.ui.RemuCard
import com.remu.app.core.ui.RemuField
import com.remu.app.core.ui.RemuText
import com.remu.app.core.ui.RemuTopBar
import com.remu.app.core.ui.SecondaryButton
import com.remu.app.core.ui.SkeletonRows
import com.remu.app.core.ui.StatusChip
import com.remu.app.feature.members.BackIcon
import com.remu.app.feature.members.loadErrorText

@Composable
fun roleLabel(role: String): String = if (role == "owner") stringResource(R.string.role_owner) else stringResource(R.string.role_cashier)

@Composable
fun StaffScreen(vm: StaffViewModel, onBack: () -> Unit) {
    val s by vm.state.collectAsState()
    val c = Remu.colors
    Column(Modifier.fillMaxSize().background(c.background)) {
        RemuTopBar(stringResource(R.string.settings_staff), navigationIcon = { BackIcon(onBack) })
        if (!s.hub.onLan) Banner(stringResource(R.string.staff_needs_lan), BannerKind.WARNING, testTag = "staff_needs_lan")
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (s.added) RemuText(stringResource(R.string.staff_added), color = c.success, modifier = Modifier.testTag("staff_added"))
            when {
                s.loading && s.staff.isEmpty() -> SkeletonRows()
                s.error != null && s.staff.isEmpty() -> InlineError(loadErrorText(s.error!!), vm::load)
                s.staff.isEmpty() -> EmptyState(stringResource(R.string.staff_empty))
                else -> s.staff.forEach { st ->
                    RemuCard(Modifier.testTag("staff_${st.id}")) {
                        RemuText(st.name, style = RemuType.section)
                        StatusChip(roleLabel(st.role), if (st.role == "owner") c.primary else c.muted)
                    }
                }
            }
            if (s.adding) {
                RemuCard(Modifier.testTag("staff_form")) {
                    RemuField(s.draft.name, { vm.onDraft(s.draft.copy(name = it)) }, stringResource(R.string.field_staff_name),
                        error = if (s.formError == StaffFormError.NAME) stringResource(R.string.error_name_empty) else null, tag = "staff_name")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("cashier", "owner").forEach { r ->
                            val on = s.draft.role == r
                            Box(
                                Modifier.heightIn(min = 48.dp).background(if (on) c.accent else c.surface, CircleShape)
                                    .clickable { vm.onDraft(s.draft.copy(role = r)) }.testTag("staff_role_$r").padding(horizontal = 16.dp, vertical = 12.dp),
                                contentAlignment = Alignment.Center,
                            ) { RemuText(roleLabel(r), style = RemuType.chip, color = if (on) c.onAccent else c.text) }
                        }
                    }
                    RemuField(s.draft.pin, { vm.onDraft(s.draft.copy(pin = it)) }, stringResource(R.string.field_pin), keyboardType = KeyboardType.NumberPassword,
                        visualTransformation = PasswordVisualTransformation(),
                        error = if (s.formError == StaffFormError.PIN_INVALID) stringResource(R.string.error_pin_invalid) else null, tag = "staff_pin")
                    RemuField(s.draft.pin2, { vm.onDraft(s.draft.copy(pin2 = it)) }, stringResource(R.string.field_pin_confirm), keyboardType = KeyboardType.NumberPassword,
                        visualTransformation = PasswordVisualTransformation(),
                        error = if (s.formError == StaffFormError.PIN_MISMATCH) stringResource(R.string.error_pin_mismatch) else null, tag = "staff_pin2")
                    if (s.saveFailed) InlineError(stringResource(R.string.error_hub_no_reply), vm::save)
                    PrimaryButton(stringResource(R.string.action_save), vm::save, enabled = s.hub.onLan, working = s.saving, modifier = Modifier.testTag("staff_save"))
                    SecondaryButton(stringResource(R.string.action_cancel), vm::cancelAdd)
                }
            }
        }
        if (!s.adding) {
            PrimaryButton(stringResource(R.string.add_staff), vm::startAdd, enabled = s.hub.onLan, modifier = Modifier.padding(16.dp).testTag("staff_add"))
        }
    }
}
