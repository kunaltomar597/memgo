package com.remu.app.core.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.remu.app.R
import com.remu.app.core.theme.Remu
import com.remu.app.data.state.HubIdentityProblem
import com.remu.app.data.state.HubStatus

/** "Hub connected" or "Hub not connected", from GET /health every 30 seconds (TASK-041). */
@Composable
fun HubChip(hub: HubStatus, modifier: Modifier = Modifier) {
    val c = Remu.colors
    Row(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        if (hub.reachable == true) {
            StatusChip(stringResource(R.string.hub_connected), c.success, Modifier.testTag("hub_chip_connected"))
        } else {
            StatusChip(stringResource(R.string.hub_not_connected), c.danger, Modifier.testTag("hub_chip_disconnected"))
        }
    }
}

/**
 * Cashier mode tells "Hub not reachable" apart from "Hub online, internet down" (TASK-051,
 * TASK-117). Shown while the condition holds.
 */
@Composable
fun CashierBanners(hub: HubStatus) {
    when {
        hub.certificateChanged -> Banner(stringResource(R.string.error_cert_changed), BannerKind.DANGER, testTag = "banner_cert")
        hub.lockedOut -> HubLockedBanner()
        hub.reachable == false -> Banner(stringResource(R.string.banner_hub_unreachable), BannerKind.DANGER, testTag = "banner_hub_unreachable")
        hub.locked -> Banner(stringResource(R.string.banner_hub_locked), BannerKind.DANGER, testTag = "banner_hub_locked")
        hub.internetDown -> Banner(stringResource(R.string.banner_no_internet), BannerKind.WARNING, testTag = "banner_no_internet")
    }
}

/**
 * Owner mode keeps LAN alerts with the internet down and says phone alerts are paused (TASK-117).
 */
@Composable
fun OwnerBanners(hub: HubStatus, phoneOnline: Boolean) {
    when {
        hub.certificateChanged -> Banner(stringResource(R.string.error_cert_changed), BannerKind.DANGER, testTag = "banner_cert")
        hub.lockedOut -> HubLockedBanner()
        !phoneOnline -> Banner(stringResource(R.string.banner_owner_phone_offline), BannerKind.WARNING, testTag = "banner_owner_phone_offline")
        hub.internetDown -> Banner(stringResource(R.string.banner_owner_shop_internet_down), BannerKind.WARNING, testTag = "banner_owner_shop_internet_down")
        hub.reachable == false -> Banner(stringResource(R.string.banner_owner_off_lan), BannerKind.INFO, testTag = "banner_owner_off_lan")
    }
}

/** A hub route answered 423 `locked` (docs/API_CONTRACT.md 7.7). Same banner in both modes. */
@Composable
fun HubLockedBanner() = Banner(stringResource(R.string.banner_hub_locked_support), BannerKind.DANGER, testTag = "banner_hub_locked_support")

/** This phone was unpaired because the hub is not the shop's (7.4) or belongs to another shop (7.6). */
@Composable
fun HubIdentityBanner(problem: HubIdentityProblem?) {
    when (problem) {
        null -> Unit
        HubIdentityProblem.NOT_YOUR_HUB -> Banner(stringResource(R.string.hub_not_yours), BannerKind.DANGER, testTag = "banner_not_your_hub")
        HubIdentityProblem.OTHER_SHOP -> Banner(stringResource(R.string.hub_other_shop), BannerKind.DANGER, testTag = "banner_hub_other_shop")
    }
}
