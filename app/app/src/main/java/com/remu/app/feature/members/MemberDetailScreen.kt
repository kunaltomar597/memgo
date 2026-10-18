package com.remu.app.feature.members

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.unit.dp
import com.remu.app.R
import com.remu.app.core.format.Formatters
import com.remu.app.core.format.dateText
import com.remu.app.core.format.friendlyDateText
import com.remu.app.core.theme.Remu
import com.remu.app.core.theme.RemuType
import com.remu.app.core.ui.DangerFilledButton
import com.remu.app.core.ui.DestructiveButton
import com.remu.app.core.ui.EmptyState
import com.remu.app.core.ui.InlineError
import com.remu.app.core.ui.MemberStatusChip
import com.remu.app.core.ui.OwnerBanners
import com.remu.app.core.ui.CashierBanners
import com.remu.app.core.ui.PrimaryButton
import com.remu.app.core.ui.RemuCard
import com.remu.app.core.ui.RemuField
import com.remu.app.core.ui.RemuSheet
import com.remu.app.core.ui.RemuText
import com.remu.app.core.ui.RemuTopBar
import com.remu.app.core.ui.SecondaryButton
import com.remu.app.core.ui.SectionHeading
import com.remu.app.core.ui.SecureScreen
import com.remu.app.core.ui.SkeletonRows
import com.remu.app.core.ui.StatusChip
import com.remu.app.feature.timeline.TimelineSection

@Composable
fun MemberDetailScreen(
    vm: MemberDetailViewModel,
    phoneOnline: Boolean,
    onBack: () -> Unit,
    onEditHousehold: (String) -> Unit,
) {
    val s by vm.state.collectAsState()
    val c = Remu.colors
    SecureScreen()
    LaunchedEffect(s.message) { if (s.message != null) { kotlinx.coroutines.delay(4_000); vm.consumeMessage() } }
    Box(Modifier.fillMaxSize().background(c.background)) {
        Column(Modifier.fillMaxSize()) {
            RemuTopBar(stringResource(R.string.member_detail_title), navigationIcon = { BackIcon(onBack) })
            if (vm.ownerMode) OwnerBanners(s.hub, phoneOnline) else CashierBanners(s.hub)
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                s.message?.let { RemuText(messageText(it), color = c.success, modifier = Modifier.testTag("detail_message")) }
                val card = s.card
                when {
                    s.loading && card == null -> SkeletonRows(4)
                    s.error != null && card == null -> InlineError(loadErrorText(s.error!!), vm::load)
                    card != null -> {
                        RemuCard(Modifier.testTag("member_card")) {
                            RemuText(card.displayName, style = RemuType.alertName)
                            (card.household?.label ?: card.householdLabel)?.let { RemuText(it, color = c.muted) }
                            MemberStatusChip(card.status, card.hasTemplate)
                            card.memberSince?.let { RemuText(stringResource(R.string.member_since, dateText(it))) }
                            RemuText(card.lastSeenAt?.let { stringResource(R.string.last_seen, friendlyDateText(it)) } ?: stringResource(R.string.last_seen_never))
                            if (card.discountFlag) StatusChip(stringResource(R.string.discount_chip), c.accent)
                            val phone = card.phone?.let { Formatters.fullPhone(it) } ?: Formatters.maskedPhone(card.phoneMasked)
                            if (phone.isNotEmpty()) RemuText(stringResource(R.string.phone_label, phone), color = c.muted)
                            if (vm.ownerMode) RemuText(stringResource(R.string.visits_count, card.visitsCount), color = c.muted)
                        }

                        SectionHeading(stringResource(R.string.notes_heading))
                        if (s.editingNotes) {
                            RemuField(s.notesDraft, vm::onNotes, stringResource(R.string.field_notes), singleLine = false, tag = "notes_field")
                            if (s.notesError) InlineError(stringResource(R.string.error_hub_no_reply), vm::saveNotes)
                            PrimaryButton(stringResource(R.string.action_save), vm::saveNotes, working = s.savingNotes, modifier = Modifier.testTag("notes_save"))
                            SecondaryButton(stringResource(R.string.action_cancel), vm::cancelNotes)
                        } else {
                            RemuText(card.notes?.takeIf { it.isNotBlank() } ?: stringResource(R.string.notes_empty), modifier = Modifier.testTag("notes_text"))
                            if (card.status != "withdrawn") {
                                PrimaryButton(stringResource(R.string.add_note), vm::editNotes, enabled = s.hub.onLan, modifier = Modifier.testTag("notes_edit"))
                            }
                        }

                        SectionHeading(stringResource(R.string.household_heading))
                        RemuText(card.household?.label ?: stringResource(R.string.household_none))
                        if (card.status != "withdrawn" && s.hub.onLan) {
                            SecondaryButton(
                                stringResource(if (card.household != null) R.string.household_edit else R.string.household_create),
                                { onEditHousehold(card.id) },
                                modifier = Modifier.testTag("household_open"),
                            )
                        }

                        if (vm.ownerMode) {
                            SectionHeading(stringResource(R.string.timeline_heading))
                            if (s.visitsError) InlineError(stringResource(R.string.error_hub_no_reply), vm::load)
                            TimelineSection(s.timeline, s.hub.onLan, s.clip, s.staffNames, vm::canPlay, vm::playClip, vm::closeClip)
                        } else {
                            SectionHeading(stringResource(R.string.purchases))
                            if (card.purchases.isEmpty()) EmptyState(stringResource(R.string.purchases_empty))
                            card.purchases.forEach { p ->
                                RemuCard(Modifier.testTag("purchase_${p.id}")) {
                                    RemuText(listOfNotNull(dateText(p.ts).ifEmpty { null }, p.amount?.let { Formatters.rupees(it) }, p.items, p.occasion).joinToString(" · "))
                                    p.billNo?.let { RemuText(it, style = RemuType.caption, color = c.muted) }
                                }
                            }
                        }
                        if (card.status != "withdrawn" && s.hub.onLan) {
                            SecondaryButton(stringResource(R.string.add_bill), vm::openBill, modifier = Modifier.testTag("detail_add_bill"))
                        }

                        SectionHeading(stringResource(R.string.consent_heading))
                        MemberStatusChip(card.status, card.hasTemplate)
                        if (card.status != "withdrawn" && card.status != "expired") {
                            DestructiveButton(stringResource(R.string.remove_member), vm::askRemove, modifier = Modifier.testTag("remove_member"),
                                enabled = s.hub.onLan || vm.ownerMode)
                        }
                    }
                }
            }
        }
        if (s.confirmRemove) {
            val name = s.card?.displayName.orEmpty()
            RemuSheet(vm::cancelRemove, testTag = "remove_sheet") {
                RemuText(stringResource(R.string.remove_confirm, name), style = RemuType.section, modifier = Modifier.testTag("remove_confirm_text"))
                if (s.removeError) InlineError(stringResource(if (s.hub.onLan) R.string.error_hub_no_reply else R.string.error_cloud_no_reply), null)
                DangerFilledButton(stringResource(R.string.remove_confirm_button), vm::remove, working = s.removing, modifier = Modifier.testTag("remove_confirm"))
                SecondaryButton(stringResource(R.string.action_cancel), vm::cancelRemove)
            }
        }
        if (s.billOpen) {
            BillSheet(s.card?.displayName.orEmpty(), s.bill, s.billTried, s.billSending, s.billError, vm::onBill, vm::sendBill, vm::closeBill)
        }
    }
}
