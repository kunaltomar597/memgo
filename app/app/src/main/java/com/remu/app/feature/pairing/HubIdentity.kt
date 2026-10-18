package com.remu.app.feature.pairing

import com.remu.app.AppContainer
import com.remu.app.data.state.HubIdentityProblem
import com.remu.app.network.ApiResult
import com.remu.app.network.cloud.CloudDevice
import com.remu.app.network.hub.HubClient
import com.remu.app.network.hub.HubHealth

/**
 * Owner mode checks that the paired hub really is the shop's (docs/API_CONTRACT.md 7.4, 7.6):
 * the fingerprint pinned at pairing must be one the cloud holds for the shop's devices, and the
 * hub must not be claimed by another cloud shop. A failed check unpairs the phone: the hub token
 * and the fingerprint are cleared.
 */
object HubIdentity {
    /** True or false when the cloud knows at least one device fingerprint; null when it cannot tell. */
    fun certMatches(pinned: String?, devices: List<CloudDevice>): Boolean? {
        val known = devices.mapNotNull { d -> d.certSha256?.takeIf { it.isNotBlank() } }
        if (pinned.isNullOrBlank() || known.isEmpty()) return null
        return known.any { HubClient.fingerprintsEqual(it, pinned) }
    }

    /** The hub says it is claimed, and not by [shopId]. */
    fun claimedByOtherShop(health: HubHealth?, shopId: String?): Boolean {
        val claimed = health?.cloudShopId ?: return false
        return shopId != null && claimed != shopId
    }

    /** After `GET /api/shops/{id}/devices` in Owner mode. Returns the problem when it unpaired. */
    suspend fun checkDevices(c: AppContainer, devices: List<CloudDevice>): HubIdentityProblem? {
        val s = c.session.current
        if (s.hubRole != "owner" || s.hubToken == null) return null
        if (certMatches(s.certSha256, devices) != false) return null
        unpair(c, HubIdentityProblem.NOT_YOUR_HUB)
        return HubIdentityProblem.NOT_YOUR_HUB
    }

    /**
     * Right after an owner pairing: the claimed shop from `GET /health` (the monitor has just
     * checked it), then the certificate the cloud holds. A cloud that does not answer is not a
     * mismatch; the check runs again whenever the devices are loaded.
     */
    suspend fun checkAfterOwnerPairing(c: AppContainer): HubIdentityProblem? {
        val s = c.session.current
        if (claimedByOtherShop(c.hubStatus.status.value.health, s.shopId)) {
            unpair(c, HubIdentityProblem.OTHER_SHOP)
            return HubIdentityProblem.OTHER_SHOP
        }
        val shop = s.shopId ?: return null
        if (!s.ownerSignedIn) return null
        val devices = (c.cloud.devices(shop) as? ApiResult.Ok)?.value ?: return null
        return checkDevices(c, devices)
    }

    suspend fun unpair(c: AppContainer, problem: HubIdentityProblem) {
        c.stopOwnerRealtime()
        c.session.update { it.copy(hubToken = null, certSha256 = null) }
        c.appState.setHubIdentity(problem)
        c.hubStatus.check()
    }
}
