package com.remu.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.remu.app.core.theme.RemuTheme
import com.remu.app.feature.alerts.OwnerHomeScreen
import com.remu.app.feature.alerts.OwnerHomeViewModel
import com.remu.app.feature.enrol.CashierHomeScreen
import com.remu.app.feature.enrol.EnrolViewModel
import com.remu.app.feature.login.LoginError
import com.remu.app.feature.login.LoginViewModel
import com.remu.app.feature.members.MemberDetailScreen
import com.remu.app.feature.members.MemberDetailViewModel
import com.remu.app.testsupport.PHONE
import com.remu.app.testsupport.TestWorld
import com.remu.app.testsupport.waitFor
import com.remu.app.testsupport.waitForTag
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** docs/API_CONTRACT.md 7.7: a 423 `locked` from any hub route shows one banner and stops hub actions. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = PHONE)
class LockedHubTest {
    @get:Rule val rule = createComposeRule()
    private var world: TestWorld? = null
    private fun world(w: TestWorld) = w.also { world = it }

    @After fun tearDown() { world?.close() }

    private val locked = """{"error":"locked"}"""

    @Test fun cashierSeesTheBannerAndMakeMemberStops() {
        val w = world(TestWorld().also { it.loginCashier() })
        w.hub.on("GET /members", 423, locked)
        val vm = EnrolViewModel(SavedStateHandle(), w.c, w.camera, resetAfterMs = 60_000)
        rule.setContent { RemuTheme(forceLight = true) { CashierHomeScreen(vm, {}, {}, onOpenMember = {}, onPairAgain = {}) } }
        rule.onNodeWithTag("make_member").assertIsEnabled()
        runBlocking { w.c.hub.members(q = "Raj") }
        rule.waitForTag("banner_hub_locked_support")
        rule.onNodeWithText("Hub is locked. Ask Remu support.").assertIsDisplayed()
        rule.onNodeWithTag("make_member").assertIsNotEnabled()
        rule.onNodeWithTag("skip_face").assertIsNotEnabled()
        assertFalse(w.c.hubStatus.status.value.onLan)

        // The hub is unlocked again: the next health check probes one route and clears the banner.
        w.hub.on("GET /staff", body = "[]")
        runBlocking { w.c.hubStatus.check() }
        rule.waitFor { rule.onAllNodesWithTag("banner_hub_locked_support").fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithTag("make_member").assertIsEnabled()
    }

    @Test fun enrolAnswering423ShowsLockedNotAGenericError() {
        val w = world(TestWorld().also { it.loginCashier() })
        w.hub.on("POST /enrol", 423, locked)
        w.hub.on("GET /staff", 423, locked)
        val vm = EnrolViewModel(SavedStateHandle(), w.c, w.camera, resetAfterMs = 60_000)
        rule.setContent { RemuTheme(forceLight = true) { CashierHomeScreen(vm, {}, {}, onOpenMember = {}, onPairAgain = {}) } }
        vm.onForm(vm.state.value.form.copy(name = "Rajesh Sharma", phone = "9876543210", billNo = "INV-1", amount = "100"))
        vm.makeMember()
        rule.waitForTag("outcome_hub_locked")
        rule.waitForTag("banner_hub_locked_support")
        // Still locked after a health check: the probe answers 423 too.
        runBlocking { w.c.hubStatus.check() }
        assertTrue(w.c.hubStatus.status.value.lockedOut)
    }

    @Test fun ownerModeShowsTheSameBanner() {
        val w = world(TestWorld(role = "owner", ownerSignedIn = true).also { it.loginOwner() })
        w.cloud.on("GET /api/shops/sharma-jewellers-jaipur/devices", body = "[]")
        w.hub.on("PATCH /visits/v_1", 423, locked)
        val vm = OwnerHomeViewModel(w.c)
        rule.setContent { RemuTheme { OwnerHomeScreen(vm, {}, {}, { _, _ -> }) } }
        runBlocking { w.c.hub.patchVisit("v_1", com.remu.app.network.hub.VisitPatch(greeted = true)) }
        rule.waitForTag("banner_hub_locked_support")
        rule.onNodeWithText("Hub is locked. Ask Remu support.").assertIsDisplayed()
    }

    @Test fun lanOnlyMemberActionsAreDisabled() {
        val w = world(TestWorld().also { it.loginCashier() })
        w.hub.on("GET /members/m_1", body = MEMBER_CARD)
        val vm = MemberDetailViewModel(w.c, "m_1", ownerMode = false)
        rule.setContent { RemuTheme(forceLight = true) { MemberDetailScreen(vm, true, {}, {}) } }
        rule.waitForTag("member_card")
        rule.waitForTag("detail_add_bill")
        w.c.hubStatus.setLockedOut(true)
        rule.waitForTag("banner_hub_locked_support")
        rule.waitFor { rule.onAllNodesWithTag("detail_add_bill").fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithTag("notes_edit").assertIsNotEnabled()
    }

    @Test fun loginAgainstALockedHubSaysSo() {
        val w = world(TestWorld())
        w.hub.on("POST /staff/verify-pin", 423, locked)
        val vm = LoginViewModel(w.c)
        "4821".forEach(vm::digit)
        vm.submit()
        rule.waitFor { vm.state.value.error != null }
        assertEquals(LoginError.HUB_LOCKED, vm.state.value.error)
        assertTrue(w.c.hubStatus.status.value.lockedOut)
    }
}
