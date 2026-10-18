package com.remu.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.remu.app.core.theme.RemuTheme
import com.remu.app.data.session.StoredSession
import com.remu.app.data.session.WizardProgress
import com.remu.app.data.state.HubIdentityProblem
import com.remu.app.feature.alerts.OwnerHomeScreen
import com.remu.app.feature.alerts.OwnerHomeViewModel
import com.remu.app.feature.pairing.PairError
import com.remu.app.feature.pairing.PairKind
import com.remu.app.feature.pairing.PairLink
import com.remu.app.feature.pairing.PairingScreen
import com.remu.app.feature.pairing.PairingViewModel
import com.remu.app.feature.settings.DeviceStatusScreen
import com.remu.app.feature.settings.DeviceStatusViewModel
import com.remu.app.feature.settings.InviteViewModel
import com.remu.app.feature.setup.SetupScreen
import com.remu.app.feature.setup.SetupStep
import com.remu.app.feature.setup.SetupViewModel
import com.remu.app.testsupport.MockServer
import com.remu.app.testsupport.PHONE
import com.remu.app.testsupport.TestWorld
import com.remu.app.testsupport.waitFor
import com.remu.app.testsupport.waitForTag
import com.remu.app.testsupport.waitForText
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * docs/API_CONTRACT.md 7.4 (certificate check) and 7.6 (claimed shop): Owner mode refuses and
 * unpairs a hub that is not the shop's; the counter phone refuses a hub other than the QR's `fp`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = PHONE)
class HubIdentityTest {
    @get:Rule val rule = createComposeRule()
    private var world: TestWorld? = null
    private fun world(w: TestWorld) = w.also { world = it }

    @After fun tearDown() { world?.close() }

    private fun devices(cert: String?) =
        """[{"id":"d_1","hub_version":"0.1.0","last_seen_at":"2026-10-18T11:00:00+05:30","online":true,"locked":false,"chain_break":false,"cert_sha256":${cert?.let { "\"$it\"" } ?: "null"}}]"""

    private fun health(cloudShop: String?) = MockServer.HEALTH_OK.dropLast(1) + ""","cloud_shop_id":${cloudShop?.let { "\"$it\"" } ?: "null"}}"""

    private fun owner() = world(TestWorld(role = "owner", ownerSignedIn = true).also { it.loginOwner() })

    // ---- 7.4 Certificate check -----------------------------------------------------------------

    @Test fun devicesWithAnotherCertificateUnpairTheOwnerPhone() {
        val w = owner()
        w.cloud.on("GET /api/shops/sharma-jewellers-jaipur/devices", body = devices("EF:01:23"))
        rule.setContent { RemuTheme { DeviceStatusScreen(DeviceStatusViewModel(w.c), {}, {}) } }
        rule.waitForText("This is not your shop's hub.")
        rule.onNodeWithTag("banner_not_your_hub").assertIsDisplayed()
        assertNull(w.c.session.current.hubToken)
        assertNull(w.c.session.current.certSha256)
        assertEquals(HubIdentityProblem.NOT_YOUR_HUB, w.c.appState.hubIdentity.value)
        rule.waitFor { w.c.hubStatus.status.value.reachable == false }
    }

    @Test fun ownerHomeChecksTooAndAMatchStaysPaired() {
        val w = owner()
        w.cloud.on("GET /api/shops/sharma-jewellers-jaipur/devices", body = devices("ab:cd"))
        val vm = OwnerHomeViewModel(w.c)
        rule.setContent { RemuTheme { OwnerHomeScreen(vm, {}, {}, { _, _ -> }) } }
        rule.waitFor { vm.state.value.device != null }
        assertEquals("app-token-1", w.c.session.current.hubToken)
        assertNull(w.c.appState.hubIdentity.value)

        w.cloud.on("GET /api/shops/sharma-jewellers-jaipur/devices", body = devices("99:99"))
        vm.refreshDevice()
        rule.waitForTag("banner_not_your_hub")
        assertNull(w.c.session.current.hubToken)
    }

    @Test fun aCloudWithoutFingerprintsIsNotAMismatch() {
        val w = owner()
        w.cloud.on("GET /api/shops/sharma-jewellers-jaipur/devices", body = devices(null))
        val vm = DeviceStatusViewModel(w.c)
        rule.waitFor { vm.state.value.device != null }
        assertEquals("app-token-1", w.c.session.current.hubToken)
    }

    @Test fun counterPhoneNeverUnpairsOnTheCloudCheck() {
        val w = world(TestWorld(role = "counter", ownerSignedIn = true).also { it.loginCashier() })
        w.cloud.on("GET /api/shops/sharma-jewellers-jaipur/devices", body = devices("EF:01"))
        val vm = DeviceStatusViewModel(w.c)
        rule.waitFor { vm.state.value.device != null }
        assertEquals("app-token-1", w.c.session.current.hubToken)
    }

    @Test fun ownerPairingIsRefusedWhenTheCloudHasAnotherCertificate() {
        val w = world(TestWorld(paired = false, ownerSignedIn = true))
        w.hub.on("POST /pair", 200, """{"app_token":"owner-token","cert_sha256":"AB:CD","shop_id":"sharma-jewellers-jaipur","role":"owner"}""")
        w.cloud.on("GET /api/shops/sharma-jewellers-jaipur/devices", body = devices("EF:01"))
        val vm = PairingViewModel(w.c, PairKind.OWNER, null)
        rule.setContent { RemuTheme { PairingScreen(vm, {}, {}) } }
        rule.onNodeWithTag("pair_host").performTextInput(w.hub.url().removeSuffix("/"))
        rule.onNodeWithTag("pair_code").performTextInput("K7QM-2XPA")
        rule.onNodeWithTag("pair_submit").performClick()
        rule.waitForText("This is not your shop's hub.")
        assertEquals(PairError.NOT_YOUR_HUB, vm.state.value.error)
        assertTrue(!vm.state.value.done)
        assertNull(w.c.session.current.hubToken)
        assertNull(w.c.session.current.certSha256)
    }

    @Test fun inviteQrCarriesThePinnedFingerprint() {
        val w = owner()
        w.hub.on("POST /pair/invite", 200, """{"invite_code":"483920","expires_at":"2026-10-18T12:10:00+05:30"}""")
        val vm = InviteViewModel(w.c)
        rule.waitFor { vm.state.value.link != null }
        val link = PairLink.parse(vm.state.value.link!!)!!
        assertEquals("AB:CD", link.fp)
        assertEquals("483920", link.code)
    }

    @Test fun counterPhoneRefusesAHubOtherThanTheQrFingerprint() {
        val w = world(TestWorld(paired = false))
        w.hub.on("POST /pair", 200, """{"app_token":"counter-token","cert_sha256":"AB:CD","shop_id":"sharma-jewellers-jaipur","role":"counter"}""")
        val vm = PairingViewModel(w.c, PairKind.COUNTER, null)
        rule.setContent { RemuTheme { PairingScreen(vm, {}, {}) } }
        vm.onScanned(PairLink(w.hub.url().removeSuffix("/"), "483920", "EF:01").toUri())
        rule.waitForText("The hub's certificate did not match. Pairing stopped.")
        assertEquals(PairError.CERT_MISMATCH, vm.state.value.error)
        assertNull(w.c.session.current.hubToken)

        vm.onScanned(PairLink(w.hub.url().removeSuffix("/"), "483921", "AB:CD").toUri())
        rule.waitFor { vm.state.value.done }
        assertEquals("counter-token", w.c.session.current.hubToken)
    }

    // ---- 7.6 Claimed shop ----------------------------------------------------------------------

    @Test fun setupRefusesAHubClaimedByAnotherShop() {
        val w = world(TestWorld(paired = false, ownerSignedIn = true))
        runBlocking { w.c.session.update { it.copy(wizard = WizardProgress(System.currentTimeMillis(), step = SetupStep.PAIR.ordinal)) } }
        w.cloud.on("POST /api/devices/pair", 409, """{"error":"conflict"}""")
        w.hub.on("POST /pair", 200, """{"app_token":"owner-token","cert_sha256":"AB:CD","shop_id":"other-shop","role":"owner"}""")
        w.hub.on("GET /health", body = health("other-shop"))
        w.hub.on("GET /staff", body = "[]")
        val vm = SetupViewModel(w.c)
        rule.setContent { RemuTheme { SetupScreen(vm, { InviteViewModel(w.c) }, w.c, {}, {}) } }
        rule.waitForTag("setup_title_PAIR")
        rule.onNodeWithTag("setup_host").performTextInput(w.hub.url().removeSuffix("/"))
        rule.onNodeWithTag("setup_code").performTextInput("K7QM-2XPA")
        rule.onNodeWithTag("setup_primary").performClick()
        rule.waitForTag("setup_pair_error")
        rule.onNodeWithText("This hub belongs to another shop.").assertIsDisplayed()
        assertNull(w.c.session.current.hubToken)
        assertEquals(SetupStep.PAIR, vm.state.value.step)
        // Pairing did not continue: no owner PIN step, no staff read.
        assertTrue(w.hub.requestsTo("GET", "/staff").isEmpty())
        assertEquals(HubIdentityProblem.OTHER_SHOP, w.c.appState.hubIdentity.value)
    }

    @Test fun deviceStatusShowsWhomTheHubIsClaimedBy() {
        val w = owner()
        w.cloud.on("GET /api/shops/sharma-jewellers-jaipur/devices", body = devices("AB:CD"))
        w.hub.on("GET /health", body = health("other-shop"))
        rule.setContent { RemuTheme { DeviceStatusScreen(DeviceStatusViewModel(w.c), {}, {}) } }
        rule.waitForTag("device_other_shop")
        rule.onNodeWithText("This hub belongs to another shop.").assertIsDisplayed()

        w.hub.on("GET /health", body = health("sharma-jewellers-jaipur"))
        runBlocking { w.c.hubStatus.check() }
        rule.waitForTag("device_claimed")

        w.hub.on("GET /health", body = health(null))
        runBlocking { w.c.hubStatus.check() }
        rule.waitForTag("device_not_claimed")
    }

    @Test fun storedSessionDefaultsStillWork() {
        // A session written before TASK-112 decodes, and a device row without cert_sha256 too.
        val s = com.remu.app.network.RemuJson.decodeFromString(StoredSession.serializer(), """{"hubBaseUrl":"https://h:8443/","hubToken":"t","certSha256":"AB"}""")
        assertTrue(s.paired)
        val d = com.remu.app.network.RemuJson.decodeFromString(com.remu.app.network.cloud.CloudDevice.serializer(), """{"id":"d_1"}""")
        assertNull(d.certSha256)
    }
}
