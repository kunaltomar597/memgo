package com.remu.app.feature.members

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
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
import com.remu.app.core.ui.EmptyState
import com.remu.app.core.ui.InlineError
import com.remu.app.core.ui.MemberStatusChip
import com.remu.app.core.ui.PrimaryButton
import com.remu.app.core.ui.RemuCard
import com.remu.app.core.ui.RemuField
import com.remu.app.core.ui.RemuText
import com.remu.app.core.ui.RemuTopBar
import com.remu.app.core.ui.SecondaryButton
import com.remu.app.core.ui.SecureScreen
import com.remu.app.core.ui.SkeletonRows
import com.remu.app.network.hub.MemberSummary

@Composable
fun BackIcon(onBack: () -> Unit) {
    IconButton(onClick = onBack, modifier = Modifier.testTag("action_back")) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
    }
}

@Composable
fun loadErrorText(e: LoadError): String = when (e) {
    LoadError.HUB_UNREACHABLE -> stringResource(R.string.error_hub_no_reply)
    LoadError.CERT_CHANGED -> stringResource(R.string.error_cert_changed)
    LoadError.CLOUD_UNREACHABLE -> stringResource(R.string.error_cloud_no_reply)
    LoadError.OTHER -> stringResource(R.string.error_generic)
}

@Composable
fun messageText(m: Message): String = when (m) {
    Message.BILL_ADDED -> stringResource(R.string.bill_added)
    Message.NOTES_SAVED -> stringResource(R.string.notes_saved)
    Message.REMOVED -> stringResource(R.string.removed_done)
    Message.HOUSEHOLD_SAVED -> stringResource(R.string.household_saved)
}

@Composable
fun MemberSearchScreen(vm: MembersViewModel, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val s by vm.state.collectAsState()
    val c = Remu.colors
    SecureScreen()
    LaunchedEffect(s.message) { if (s.message != null) { kotlinx.coroutines.delay(4_000); vm.consumeMessage() } }
    Box(Modifier.fillMaxSize().background(c.background)) {
        Column(Modifier.fillMaxSize()) {
            RemuTopBar(stringResource(R.string.members_title), navigationIcon = { BackIcon(onBack) })
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RemuField(s.query, vm::onQuery, stringResource(R.string.members_search_label), tag = "member_query")
                // Search stays tappable while a search runs: a new tap replaces the one in flight.
                PrimaryButton(stringResource(R.string.members_search), vm::search, modifier = Modifier.testTag("member_search"))
                if (s.loading && s.results != null) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("members_loading"), color = c.primary)
                if (vm.ownerMode) StatusFilters(s.status, vm::onStatus)
                s.message?.let { RemuText(messageText(it), color = c.success, modifier = Modifier.testTag("members_message")) }
            }
            when {
                s.loading && s.results == null -> Box(Modifier.padding(16.dp)) { SkeletonRows() }
                s.error != null -> Box(Modifier.padding(16.dp)) { InlineError(loadErrorText(s.error!!), vm::search) }
                s.results.isNullOrEmpty() -> Box(Modifier.padding(16.dp)) {
                    EmptyState(stringResource(if (s.query.isBlank() && s.status == null) R.string.members_empty else R.string.members_no_results))
                }
                else -> LazyColumn(
                    Modifier.fillMaxSize().testTag("member_list"),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (vm.ownerMode) item { RemuText(stringResource(R.string.sort_last_seen), style = RemuType.caption, color = c.muted) }
                    items(s.results!!, key = { it.id }) { m -> MemberRow(m, onOpen = { onOpen(m.id) }, onAddBill = { vm.openBill(m) }) }
                }
            }
        }
        s.billFor?.let { m ->
            BillSheet(m.displayName, s.bill, s.billTried, s.billSending, s.billError, vm::onBill, vm::sendBill, vm::closeBill)
        }
    }
}

@Composable
private fun StatusFilters(current: String?, onPick: (String?) -> Unit) {
    val c = Remu.colors
    val options = listOf(
        null to stringResource(R.string.filter_all),
        "pending_notice" to stringResource(R.string.status_pending),
        "active" to stringResource(R.string.status_active),
        "withdrawn" to stringResource(R.string.status_withdrawn),
        "expired" to stringResource(R.string.status_expired),
    )
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            val on = current == value
            Box(
                Modifier
                    .heightIn(min = 48.dp)
                    .background(if (on) c.accent else c.surface, CircleShape)
                    .clickable { onPick(value) }
                    .testTag("filter_${value ?: "all"}")
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) { RemuText(label, style = RemuType.chip, color = if (on) c.onAccent else c.text) }
        }
    }
}

@Composable
fun MemberRow(m: MemberSummary, onOpen: () -> Unit, onAddBill: (() -> Unit)?) {
    val c = Remu.colors
    RemuCard(Modifier.clickable(onClick = onOpen).testTag("member_row_${m.id}")) {
        RemuText(m.displayName, style = RemuType.section)
        MemberStatusChip(m.status, m.hasTemplate)
        m.householdLabel?.takeIf { it.isNotBlank() }?.let { RemuText(it, color = c.muted) }
        m.memberSince?.let { RemuText(stringResource(R.string.member_since, dateText(it)), style = RemuType.caption, color = c.muted) }
        RemuText(
            m.lastSeenAt?.let { stringResource(R.string.last_seen, friendlyDateText(it)) } ?: stringResource(R.string.last_seen_never),
            style = RemuType.caption, color = c.muted,
        )
        m.phoneMasked?.let { RemuText(Formatters.maskedPhone(it), style = RemuType.caption, color = c.muted) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(stringResource(R.string.open_member), onOpen, modifier = Modifier.weight(1f))
            if (onAddBill != null && m.status != "withdrawn") {
                SecondaryButton(stringResource(R.string.add_bill), onAddBill, modifier = Modifier.weight(1f).testTag("add_bill_${m.id}"))
            }
        }
    }
}
