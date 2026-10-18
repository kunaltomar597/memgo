package com.remu.app.feature.enrol

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.remu.app.R
import com.remu.app.core.theme.Remu
import com.remu.app.core.theme.RemuType
import com.remu.app.core.ui.CashierBanners
import com.remu.app.core.ui.HubChip
import com.remu.app.core.ui.InlineError
import com.remu.app.core.ui.KeepScreenOn
import com.remu.app.core.ui.PrimaryButton
import com.remu.app.core.ui.RemuCard
import com.remu.app.core.ui.RemuField
import com.remu.app.core.ui.RemuText
import com.remu.app.core.ui.RemuTopBar
import com.remu.app.core.ui.SecondaryButton
import com.remu.app.core.ui.StatusChip

@Composable
fun CashierHomeScreen(
    vm: EnrolViewModel,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onOpenMember: (String) -> Unit,
    onPairAgain: () -> Unit,
    onHouseholdDone: () -> Unit = {},
) {
    val s by vm.state.collectAsState()
    KeepScreenOn()
    LaunchedEffect(s.openMemberId) { s.openMemberId?.let { onOpenMember(it); vm.consumeOpenMember() } }
    LaunchedEffect(s.outcome) { if (s.outcome == EnrolOutcome.AddedToHousehold) onHouseholdDone() }
    val familyTemplate = stringResource(R.string.household_label_default)
    val c = Remu.colors

    Column(Modifier.fillMaxSize().background(c.background)) {
        RemuTopBar(
            title = stringResource(R.string.app_name),
            actions = {
                IconButton(onClick = onSearch, modifier = Modifier.testTag("action_search")) {
                    Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.cd_search))
                }
                IconButton(onClick = onSettings, modifier = Modifier.testTag("action_settings")) {
                    Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.cd_settings))
                }
            },
        )
        CashierBanners(s.hub)
        Box(Modifier.fillMaxWidth().height(280.dp).clipToBounds().background(Color(0xFF1C1917))) {
            vm.camera.Preview(Modifier.fillMaxSize())
            FaceOverlay(s.frames, s.selection, s.refusedTracking, onTap = vm::tapFace)
            if (s.frames != null && s.frames?.faces.isNullOrEmpty()) {
                RemuText(
                    stringResource(R.string.camera_no_face),
                    color = Color.White,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)
                        .background(Color(0xCC1C1917), RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 8.dp)
                        .testTag("no_face_hint"),
                )
            }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HubChip(s.hub, Modifier.padding(0.dp))
            s.householdTarget?.let {
                RemuText(stringResource(R.string.enrol_adding_to_household, it.label), style = RemuType.chip, color = c.primary)
            }
            HouseholdRow(s, onToggle = { vm.setHousehold(it) { name -> com.remu.app.core.format.Validation.defaultHouseholdLabel(name, familyTemplate) } }, onRemove = vm::removeFace)
            OutcomeCard(s, onRetry = { s.lastKind?.let(vm::retry) }, onPairAgain = onPairAgain)
            BillingFields(s, vm::onForm)
            ScriptPrompt()
        }
        Column(
            Modifier.fillMaxWidth().background(c.background).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PrimaryButton(
                stringResource(R.string.make_member),
                onClick = vm::makeMember,
                enabled = s.hubReady,
                working = s.sending == SendKind.MAKE_MEMBER,
                modifier = Modifier.testTag("make_member"),
            )
            if (s.householdTarget == null) {
                SecondaryButton(
                    stringResource(R.string.skip_face),
                    onClick = vm::skipFace,
                    enabled = s.hubReady && s.sending == null,
                    working = s.sending == SendKind.SKIP_FACE,
                    modifier = Modifier.testTag("skip_face"),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HouseholdRow(s: EnrolUiState, onToggle: (Boolean) -> Unit, onRemove: (Int) -> Unit) {
    val c = Remu.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        RemuText(stringResource(R.string.household_toggle), modifier = Modifier.weight(1f))
        Switch(
            checked = s.selection.household,
            onCheckedChange = onToggle,
            modifier = Modifier.testTag("household_toggle"),
            colors = SwitchDefaults.colors(checkedTrackColor = c.primary),
        )
    }
    if (s.selection.household && s.selection.selected.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            s.selection.selected.forEachIndexed { i, id ->
                val removeDesc = stringResource(R.string.cd_remove_chip, i + 1)
                Row(
                    Modifier
                        .heightIn(min = 48.dp)
                        .background(c.primary.copy(alpha = 0.15f), CircleShape)
                        .clickable { onRemove(id) }
                        .semantics { contentDescription = removeDesc }
                        .testTag("chip_$id")
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RemuText(stringResource(R.string.household_chip, i + 1), style = RemuType.chip, color = c.primary)
                    Icon(Icons.Filled.Close, contentDescription = null, tint = c.primary, modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
        if (s.selection.selected.size >= FaceSelection.MAX_FACES) {
            RemuText(stringResource(R.string.household_max), style = RemuType.caption, color = c.muted)
        }
    }
}

@Composable
private fun OutcomeCard(s: EnrolUiState, onRetry: () -> Unit, onPairAgain: () -> Unit) {
    val c = Remu.colors
    when (s.outcome) {
        null -> Unit
        EnrolOutcome.Success -> RemuCard(Modifier.testTag("outcome_success")) {
            RemuText(stringResource(R.string.enrol_success), style = RemuType.section)
            StatusChip(stringResource(R.string.status_pending), c.warning)
        }
        EnrolOutcome.PartlyRefused -> RemuCard(Modifier.testTag("outcome_partial")) {
            RemuText(stringResource(R.string.enrol_partial_refused), style = RemuType.section)
            StatusChip(stringResource(R.string.status_pending), c.warning)
        }
        EnrolOutcome.AddedToHousehold -> RemuCard(Modifier.testTag("outcome_household")) {
            RemuText(stringResource(R.string.enrol_household_added), style = RemuType.section)
            StatusChip(stringResource(R.string.status_pending), c.warning)
        }
        EnrolOutcome.PhoneMemberSaved -> RemuCard(Modifier.testTag("outcome_phone_member")) {
            RemuText(stringResource(R.string.enrol_phone_member_saved), style = RemuType.section)
        }
        EnrolOutcome.AllUnder18 -> RemuCard(Modifier.testTag("outcome_under18"), stripe = c.danger) {
            RemuText(stringResource(R.string.face_under_18), style = RemuType.section, color = c.danger)
            RemuText(stringResource(R.string.enrol_refused_under_18))
        }
        EnrolOutcome.NoFace -> InlineError(stringResource(R.string.enrol_no_face), onRetry)
        EnrolOutcome.FacesNotInView -> InlineError(stringResource(R.string.enrol_faces_not_in_view), null)
        EnrolOutcome.SelectFace -> InlineError(stringResource(R.string.enrol_select_face), null)
        EnrolOutcome.HubError -> InlineError(stringResource(R.string.error_hub_no_reply), onRetry)
        EnrolOutcome.NotLoggedIn -> InlineError(stringResource(R.string.error_generic), null)
        EnrolOutcome.CertificateChanged -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            InlineError(stringResource(R.string.error_cert_changed), null)
            SecondaryButton(stringResource(R.string.pairing_title), onPairAgain)
        }
    }
}

@Composable
private fun errorText(e: FieldError?): String? = when (e) {
    null -> null
    FieldError.PHONE_EMPTY -> stringResource(R.string.error_phone_empty)
    FieldError.PHONE_INVALID, FieldError.ADULT_PHONE_INVALID -> stringResource(R.string.error_phone_invalid)
    FieldError.AMOUNT_EMPTY -> stringResource(R.string.error_amount_empty)
    FieldError.AMOUNT_INVALID -> stringResource(R.string.error_amount_invalid)
    FieldError.BILL_NO -> stringResource(R.string.error_bill_no)
    FieldError.NAME_EMPTY -> stringResource(R.string.error_name_empty)
    FieldError.TOO_LONG -> stringResource(R.string.error_too_long)
    FieldError.HOUSEHOLD_LABEL -> stringResource(R.string.error_household_label)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BillingFields(s: EnrolUiState, onForm: (BillingForm) -> Unit) {
    val f = s.form
    val e = s.errors
    val c = Remu.colors
    RemuField(f.name, { onForm(f.copy(name = it)) }, stringResource(R.string.field_name), error = errorText(e["name"]), tag = "field_name")
    RemuField(f.phone, { onForm(f.copy(phone = it)) }, stringResource(R.string.field_phone), error = errorText(e["phone"]), keyboardType = KeyboardType.Phone, tag = "field_phone")
    RemuField(f.billNo, { onForm(f.copy(billNo = it)) }, stringResource(R.string.field_bill_no), error = errorText(e["billNo"]), tag = "field_bill_no")
    RemuField(f.amount, { onForm(f.copy(amount = it)) }, stringResource(R.string.field_amount), error = errorText(e["amount"]), keyboardType = KeyboardType.Decimal, tag = "field_amount")
    RemuField(f.items, { onForm(f.copy(items = it)) }, stringResource(R.string.field_items), error = errorText(e["items"]), tag = "field_items")
    RemuField(f.occasion, { onForm(f.copy(occasion = it)) }, stringResource(R.string.field_occasion), error = errorText(e["occasion"]), tag = "field_occasion")
    RemuField(f.notes, { onForm(f.copy(notes = it)) }, stringResource(R.string.field_notes), error = errorText(e["notes"]), singleLine = false, tag = "field_notes")
    val n = s.selection.selected.size
    if (s.selection.household && n > 1) {
        RemuField(f.householdLabel, { onForm(f.copy(householdLabel = it)) }, stringResource(R.string.field_household_label), error = errorText(e["householdLabel"]), tag = "field_household_label")
        RemuText(stringResource(R.string.field_payer), style = RemuType.chip, color = c.muted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (i in 0 until n) {
                val chosen = f.payerIndex == i
                Box(
                    Modifier
                        .heightIn(min = 48.dp)
                        .background(if (chosen) c.accent else c.surface, RoundedCornerShape(24.dp))
                        .clickable { onForm(f.copy(payerIndex = i)) }
                        .testTag("payer_$i")
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) { RemuText(stringResource(R.string.household_chip, i + 1), style = RemuType.chip, color = if (chosen) c.onAccent else c.text) }
            }
        }
        for (i in 1 until n) {
            val names = f.adultNames.padded(n - 1)
            val phones = f.adultPhones.padded(n - 1)
            RemuField(names[i - 1], { v -> onForm(f.copy(adultNames = names.toMutableList().also { it[i - 1] = v })) }, stringResource(R.string.field_adult_name, i + 1), tag = "field_adult_name_$i")
            RemuField(phones[i - 1], { v -> onForm(f.copy(adultPhones = phones.toMutableList().also { it[i - 1] = v })) }, stringResource(R.string.field_adult_phone, i + 1), error = errorText(e["adultPhone$i"]), keyboardType = KeyboardType.Phone, tag = "field_adult_phone_$i")
        }
    }
}

private fun List<String>.padded(n: Int): List<String> = if (size >= n) take(n) else this + List(n - size) { "" }

/** The v1.0 cashier line, verbatim, Devanagari above English, at 22sp above the button. */
@Composable
fun ScriptPrompt() {
    val c = Remu.colors
    RemuCard(Modifier.testTag("script_prompt"), stripe = c.accent) {
        RemuText(stringResource(R.string.script_heading), style = RemuType.chip, color = c.muted)
        RemuText(stringResource(R.string.script_line_hi), style = RemuType.scriptLine, modifier = Modifier.testTag("script_hi"))
        RemuText(stringResource(R.string.script_line_en), style = RemuType.scriptLine, modifier = Modifier.testTag("script_en"))
    }
}
