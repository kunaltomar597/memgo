package com.remu.app.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.remu.app.core.nav.RemuNavHost
import com.remu.app.core.theme.RemuTheme
import com.remu.app.data.session.StoredSession
import com.remu.app.data.session.WizardProgress
import com.remu.app.data.state.Mode
import com.remu.app.feature.login.LoginViewModel
import com.remu.app.feature.settings.InviteViewModel
import com.remu.app.feature.setup.SetupScreen
import com.remu.app.feature.setup.SetupStep
import com.remu.app.feature.setup.SetupViewModel
import com.remu.app.network.hub.HubClient
import com.remu.app.network.hub.MemberPatch
import com.remu.app.testsupport.MockServer
import com.remu.app.testsupport.PHONE
import com.remu.app.testsupport.TestWorld
import com.remu.app.testsupport.waitFor
import com.remu.app.testsupport.waitForTag
import com.remu.app.testsupport.waitForText
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** docs/API_CONTRACT.md 7.1: the staff session from verify-pin, its header, and its end. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = PHONE)
class StaffSessionTest {
    @get:Rule val rule = createComposeRule()
    private var world: TestWorld? = null
    private fun world(w: TestWorld) = w.also { world = it }

    @After fun tearDown() { world?.close() }

    private fun type(pin: String) = pin.forEach { rule.onNodeWithTag("pin_key_$it").performScrollTo().performClick() }

    @Test fun pinLoginKeepsTheSessionInMemoryAndSendsItOnWrites() {
        val w = world(TestWorld())
        w.hub.on("POST /staff/verify-pin", body = """{"staff_id":"s_ravi","name":"Ravi","role":"cashier","staff_session":"sess-ravi-7"}""")
        w.hub.on("PATCH /members/m_1", 200, "{}")
        val vm = LoginViewModel(w.c)
        vm.setMode(Mode.CASHIER)
        "4821".forEach(vm::digit)
        vm.submit()
        rule.waitFor { w.c.appState.staff.value != null }
        assertEquals("sess-ravi-7", w.c.appState.staff.value!!.sessionToken)
        // Never persisted: the encrypted DataStore value holds no staff session.
        assertFalse(w.c.session.current.toString().contains("sess-ravi-7"))
        assertFalse(w.c.appState.staff.value.toString().contains("sess-ravi-7"))
        runBlocking { w.c.hub.patchMember("m_1", MemberPatch(notes = "Likes kundan")) }
        assertEquals("sess-ravi-7", w.hub.requestsTo("PATCH", "/members/m_1").single().headers[HubClient.STAFF_SESSION_HEADER])
        // Lock drops it from memory too.
        w.c.appState.lock()
        w.hub.on("PATCH /members/m_1", 401, """{"error":"staff_session_required"}""")
        runBlocking { w.c.hub.patchMember("m_1", MemberPatch(notes = "x")) }
        assertNull(w.hub.requestsTo("PATCH", "/members/m_1").last().headers[HubClient.STAFF_SESSION_HEADER])
    }

    @Test fun sessionRequiredReturnsToThePinScreen() {
        val w = world(TestWorld())
        w.hub.on("POST /staff/verify-pin", body = """{"staff_id":"s_ravi","name":"Ravi","role":"cashier","staff_session":"sess-old"}""")
        rule.setContent { RemuNavHost(w.c) }
        rule.waitForTag("pin_unlock")
        rule.onNodeWithTag("mode_cashier").performClick()
        type("4821")
        rule.onNodeWithTag("pin_unlock").performClick()
        rule.waitForTag("make_member")
        // The hub restarted or 12 hours passed: the session is no longer valid.
        w.hub.on("PATCH /members/m_1", 401, """{"error":"staff_session_required"}""")
        runBlocking { w.c.hub.patchMember("m_1", MemberPatch(notes = "x")) }
        rule.waitForTag("pin_unlock")
        rule.waitForText("Enter your PIN again to continue.")
        assertNull(w.c.appState.staff.value)
        assertTrue(w.c.appState.sessionEnded.value)
    }

    @Test fun anOrdinaryUnauthorizedDoesNotEndTheSession() {
        val w = world(TestWorld())
        w.loginCashier()
        w.hub.on("PATCH /members/m_1", 401, """{"error":"unauthorized"}""")
        runBlocking { w.c.hub.patchMember("m_1", MemberPatch(notes = "x")) }
        assertEquals("s_ravi", w.c.appState.staff.value?.staffId)
    }

    @Test fun wizardWithAnExistingOwnerAsksForTheOwnerPinBeforeAnyWrite() {
        val w = world(TestWorld(role = "owner", ownerSignedIn = true))
        runBlocking { w.c.session.update { it.copy(wizard = WizardProgress(System.currentTimeMillis(), step = SetupStep.PAIR.ordinal)) } }
        w.hub.on("GET /health", body = MockServer.HEALTH_OK.dropLast(1) + ""","cloud_shop_id":"sharma-jewellers-jaipur"}""")
        runBlocking { w.c.hubStatus.check() }
        w.hub.on("GET /staff", body = """[{"id":"s_owner","name":"Rajesh","role":"owner"}]""")
        w.hub.on("POST /staff/verify-pin", body = """{"staff_id":"s_owner","name":"Rajesh","role":"owner","staff_session":"sess-owner-2"}""")
        w.hub.on("GET /discover", body = """{"streams":[{"id":"ch1","label":"Channel 1 (gate)","url":"rtsp://dvr/ch1"}]}""")
        w.hub.on("PUT /settings/streams", 200, "{}")
        val vm = SetupViewModel(w.c)
        rule.setContent { RemuTheme { SetupScreen(vm, { InviteViewModel(w.c) }, w.c, {}, {}) } }
        rule.waitForTag("setup_owner_login")
        // 7.6: the wizard shows whom the hub is claimed by.
        rule.onNodeWithText("Hub is linked to this shop.").assertIsDisplayed()
        assertTrue(w.hub.requestsTo("GET", "/discover").isEmpty())
        rule.onNodeWithTag("setup_login_pin").performTextInput("482199")
        rule.onNodeWithTag("setup_primary").performClick()
        rule.waitForTag("stream_0")
        assertEquals("""{"pin":"482199"}""", w.hub.requestsTo("POST", "/staff/verify-pin").single().body)
        assertEquals(Mode.OWNER, w.c.appState.staff.value!!.mode)
        rule.onNodeWithTag("stream_0").performClick()
        rule.onNodeWithTag("setup_primary").performClick()
        rule.waitForTag("setup_title_AIM")
        assertEquals("sess-owner-2", w.hub.requestsTo("PUT", "/settings/streams").single().headers[HubClient.STAFF_SESSION_HEADER])
    }

    @Test fun wizardAsksAgainWhenTheSessionEnds() {
        val w = world(TestWorld(role = "owner", ownerSignedIn = true, session = null))
        runBlocking { w.c.session.update { it.copy(wizard = WizardProgress(System.currentTimeMillis(), step = SetupStep.STREAM.ordinal)) } }
        w.loginOwner()
        w.hub.on("GET /discover", body = """{"streams":[{"id":"ch1","label":"Channel 1 (gate)","url":"rtsp://dvr/ch1"}]}""")
        w.hub.on("PUT /settings/streams", 401, """{"error":"staff_session_required"}""")
        val vm = SetupViewModel(w.c)
        rule.setContent { RemuTheme { SetupScreen(vm, { InviteViewModel(w.c) }, w.c, {}, {}) } }
        rule.waitForTag("stream_0")
        rule.onNodeWithTag("stream_0").performClick()
        rule.onNodeWithTag("setup_primary").performClick()
        rule.waitForTag("setup_owner_login")
    }

    @Test fun storedSessionHasNoStaffSessionField() {
        // The persisted shape cannot carry a staff session at all.
        val names = StoredSession::class.java.declaredFields.map { it.name.lowercase() }
        assertTrue(names.none { it.contains("staffsession") || it.contains("sessiontoken") })
    }
}
