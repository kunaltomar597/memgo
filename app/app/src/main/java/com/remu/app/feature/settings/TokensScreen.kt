package com.remu.app.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.remu.app.core.theme.RemuType
import com.remu.app.core.ui.Banner
import com.remu.app.core.ui.BannerKind
import com.remu.app.core.ui.DangerFilledButton
import com.remu.app.core.ui.DestructiveButton
import com.remu.app.core.ui.EmptyState
import com.remu.app.core.ui.HubLockedBanner
import com.remu.app.core.ui.InlineError
import com.remu.app.core.ui.RemuCard
import com.remu.app.core.ui.RemuSheet
import com.remu.app.core.ui.RemuText
import com.remu.app.core.ui.RemuTopBar
import com.remu.app.core.ui.SecondaryButton
import com.remu.app.core.ui.SkeletonRows
import com.remu.app.core.ui.StatusChip
import com.remu.app.feature.members.BackIcon
import com.remu.app.feature.members.loadErrorText
import com.remu.app.network.hub.HubToken

@Composable
private fun phoneRoleLabel(role: String): String =
    if (role == "owner") stringResource(R.string.paired_phone_owner) else stringResource(R.string.paired_phone_counter)

@Composable
fun TokensScreen(vm: TokensViewModel, onBack: () -> Unit) {
    val s by vm.state.collectAsState()
    val c = Remu.colors
    Box(Modifier.fillMaxSize().background(c.background)) {
        Column(Modifier.fillMaxSize()) {
            RemuTopBar(stringResource(R.string.paired_phones), navigationIcon = { BackIcon(onBack) })
            when {
                s.hub.lockedOut -> HubLockedBanner()
                !s.hub.onLan -> Banner(stringResource(R.string.paired_phones_needs_lan), BannerKind.WARNING, testTag = "tokens_needs_lan")
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                RemuText(stringResource(R.string.paired_phones_body), color = c.muted)
                if (s.revoked) RemuText(stringResource(R.string.paired_phone_revoked_done), color = c.success, modifier = Modifier.testTag("token_revoked_done"))
                when {
                    s.loading && s.tokens.isEmpty() -> SkeletonRows()
                    s.error != null && s.tokens.isEmpty() -> InlineError(loadErrorText(s.error!!), vm::load)
                    s.tokens.isEmpty() -> EmptyState(stringResource(R.string.paired_phones_empty))
                    else -> s.tokens.forEach { t -> TokenRow(t, t.id == s.thisPhoneId, s.hub.onLan) { vm.askRevoke(t) } }
                }
            }
        }
        s.confirm?.let { t ->
            RemuSheet(vm::cancelRevoke, testTag = "revoke_sheet") {
                RemuText(stringResource(R.string.paired_phone_revoke_confirm, phoneRoleLabel(t.role)), style = RemuType.section, modifier = Modifier.testTag("revoke_confirm_text"))
                if (s.revokeFailed) InlineError(stringResource(R.string.error_hub_no_reply), null)
                DangerFilledButton(stringResource(R.string.paired_phone_revoke), vm::revoke, working = s.revoking, modifier = Modifier.testTag("revoke_confirm"))
                SecondaryButton(stringResource(R.string.action_cancel), vm::cancelRevoke, modifier = Modifier.testTag("revoke_cancel"))
            }
        }
    }
}

@Composable
private fun TokenRow(t: HubToken, thisPhone: Boolean, onLan: Boolean, onRevoke: () -> Unit) {
    val c = Remu.colors
    RemuCard(Modifier.testTag("token_${t.id}")) {
        RemuText(phoneRoleLabel(t.role), style = RemuType.section)
        if (thisPhone) StatusChip(stringResource(R.string.paired_phone_this), c.primary, Modifier.testTag("token_this_${t.id}"))
        t.createdAt?.let { RemuText(stringResource(R.string.paired_phone_added, dateText(it)), color = c.muted) }
        if (t.revokedAt != null) {
            StatusChip(stringResource(R.string.paired_phone_revoked_on, dateText(t.revokedAt)), c.danger, Modifier.testTag("token_revoked_${t.id}"))
        } else if (!thisPhone) {
            DestructiveButton(stringResource(R.string.paired_phone_revoke), onRevoke, enabled = onLan, modifier = Modifier.testTag("token_revoke_${t.id}"))
        }
    }
}
