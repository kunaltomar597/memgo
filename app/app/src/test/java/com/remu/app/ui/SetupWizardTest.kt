package com.remu.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.remu.app.core.theme.RemuTheme
import com.remu.app.data.session.StoredSession
import com.remu.app.feature.settings.InviteViewModel
import com.remu.app.feature.setup.SetupScreen
import com.remu.app.feature.setup.SetupStep
import com.remu.app.feature.setup.SetupViewModel
import com.remu.app.testsupport.PHONE
import com.remu.app.testsupport.TestWorld
import com.remu.app.testsupport.waitFor
import com.remu.app.testsupport.waitForTag
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** TASK-106: every wizard step against a mock cloud and a mock hub, resumable, with elapsed time. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = PHONE)
class SetupWizardTest {
    @get:Rule val rule = createComposeRule()
    private val w = TestWorld(
        paired = false, ownerSignedIn = true,
        session = StoredSession(ownerAccessToken = "a", ownerRefreshToken = "r", ownerExpiresAtEpochS = System.currentTimeMillis() / 1000 + 3600),
    )

    @After fun tearDown() = w.close()

    @Test fun walksEveryStep() {
        w.cloud.on("POST /api/shops", 201, """{"id":"sharma-jewellers-jaipur"}""")
        w.cloud.on("POST /api/devices/pair", 200, """{"device_id":"d_1"}""")
        w.hub.on("POST /pair", 200, """{"app_token":"owner-token","cert_sha256":"AB:CD","shop_id":"sharma-jewellers-jaipur","role":"owner"}""")
        w.hub.on("GET /staff", body = "[]")
        w.hub.on("POST /staff", 201, """{"staff_id":"s_owner"}""")
        w.hub.on("POST /staff/verify-pin", 200, """{"staff_id":"s_owner","name":"Rajesh","role":"owner","staff_session":"sess-owner-1"}""")
        w.hub.on("GET /discover", body = """{"streams":[{"id":"ch1","label":"Channel 1 (gate)","url":"rtsp://dvr/ch1"},{"id":"ch2","label":"Channel 2","url":"rtsp://dvr/ch2"}]}""")
        w.hub.on("PUT /settings/streams", 200, "{}")
        w.hub.onRaw("GET /preview") { MockResponse().setHeader("Content-Type", "multipart/x-mixed-replace; boundary=frame").setBody("--frame\r\nContent-Type: image/jpeg\r\n\r\n") }
        w.hub.on("POST /pair/invite", 200, """{"invite_code":"483920","expires_at":"2026-10-18T12:10:00+05:30"}""")

        val vm = SetupViewModel(w.c)
        var finished = false
        rule.setContent { RemuTheme { SetupScreen(vm, { InviteViewModel(w.c) }, w.c, {}, { finished = true }) } }
        rule.waitForTag("setup_title_SHOP")
        rule.onNodeWithText("Step 1 of 6").assertIsDisplayed()
        rule.onNodeWithTag("setup_elapsed").assertIsDisplayed()

        // 1. Create shop
        rule.onNodeWithTag("setup_shop_name").performTextInput("Sharma Jewellers")
        rule.onNodeWithTag("setup_city").performTextInput("Jaipur")
        rule.onNodeWithTag("setup_primary").performClick()
        rule.waitForTag("setup_title_PAIR")
        assertEquals("""{"name":"Sharma Jewellers","city":"Jaipur","notice_language":"hi"}""", w.cloud.requestsTo("POST", "/api/shops").single().body)
        assertEquals(1, w.c.session.current.wizard!!.step)

        // 2. Pair hub, then set the owner PIN
        rule.onNodeWithTag("setup_host").performTextInput(w.hub.url().removeSuffix("/"))
        rule.onNodeWithTag("setup_code").performTextInput("K7QM-2XPA")
        rule.onNodeWithTag("setup_primary").performClick()
        rule.waitForTag("setup_owner_name")
        assertEquals("""{"shop_id":"sharma-jewellers-jaipur","pairing_code":"K7QM-2XPA"}""", w.cloud.requestsTo("POST", "/api/devices/pair").single().body)
        assertEquals("""{"code":"K7QM-2XPA"}""", w.hub.requestsTo("POST", "/pair").single().body)
        assertEquals("owner-token", w.c.session.current.hubToken)
        assertEquals("owner", w.c.session.current.hubRole)
        rule.onNodeWithTag("setup_owner_name").performTextInput("Rajesh")
        rule.onNodeWithTag("setup_pin").performTextInput("482199")
        rule.onNodeWithTag("setup_pin2").performTextInput("482199")
        rule.onNodeWithTag("setup_primary").performClick()
        rule.waitForTag("setup_title_STREAM")
        assertEquals("""{"name":"Rajesh","role":"owner","pin":"482199"}""", w.hub.requestsTo("POST", "/staff").single().body)
        // The first owner is created before any staff session; the same PIN then opens one (contract 7.1).
        assertEquals(null, w.hub.requestsTo("POST", "/staff").single().headers["X-Remu-Staff-Session"])
        assertEquals("""{"pin":"482199"}""", w.hub.requestsTo("POST", "/staff/verify-pin").single().body)
        assertEquals("sess-owner-1", w.c.appState.staff.value?.sessionToken)
        assertTrue("the staff session is never persisted", !w.c.session.current.toString().contains("sess-owner-1"))

        // 3. Gate stream
        rule.waitForTag("stream_0")
        rule.onNodeWithTag("stream_0").performClick()
        rule.onNodeWithTag("setup_primary").performClick()
        rule.waitForTag("setup_title_AIM")
        assertEquals("""{"gate_url":"rtsp://dvr/ch1"}""", w.hub.requestsTo("PUT", "/settings/streams").single().body)
        assertEquals("sess-owner-1", w.hub.requestsTo("PUT", "/settings/streams").single().headers["X-Remu-Staff-Session"])

        // 4. Aim
        rule.onNodeWithTag("aim_preview").assertIsDisplayed()
        rule.onNodeWithTag("setup_primary").performClick()
        rule.waitForTag("setup_title_COUNTER")

        // 5. Counter phone invite as QR
        rule.waitForTag("invite_qr")
        rule.onNodeWithText("Invite code: 483920").assertIsDisplayed()
        assertEquals("sess-owner-1", w.hub.requestsTo("POST", "/pair/invite").single().headers["X-Remu-Staff-Session"])
        rule.onNodeWithTag("setup_primary").performClick()
        rule.waitForTag("setup_title_STANDEE")

        // 6. Standee and finish
        rule.onNodeWithTag("setup_print").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("setup_primary").performClick()
        rule.waitFor { finished }
        assertTrue(w.c.session.current.wizard!!.completed)
    }

    @Test fun resumesAtTheSavedStep() {
        kotlinx.coroutines.runBlocking {
            w.c.session.update { it.copy(shopId = "s", wizard = com.remu.app.data.session.WizardProgress(System.currentTimeMillis() - 12 * 60_000, step = SetupStep.AIM.ordinal)) }
        }
        w.hub.onRaw("GET /preview") { MockResponse().setResponseCode(404) }
        val vm = SetupViewModel(w.c)
        rule.setContent { RemuTheme { SetupScreen(vm, { InviteViewModel(w.c) }, w.c, {}, {}) } }
        rule.waitForTag("setup_title_AIM")
        rule.onNodeWithText("Time so far: 12 min").assertIsDisplayed()
        rule.onNodeWithText("Step 4 of 6").assertIsDisplayed()
    }

    @Test fun wrongPairingCode() {
        kotlinx.coroutines.runBlocking { w.c.session.update { it.copy(shopId = "s", wizard = com.remu.app.data.session.WizardProgress(System.currentTimeMillis(), step = 1)) } }
        w.cloud.on("POST /api/devices/pair", 404, """{"error":"not_found"}""")
        val vm = SetupViewModel(w.c)
        rule.setContent { RemuTheme { SetupScreen(vm, { InviteViewModel(w.c) }, w.c, {}, {}) } }
        rule.waitForTag("setup_title_PAIR")
        rule.onNodeWithTag("setup_host").performTextInput("192.168.1.20")
        rule.onNodeWithTag("setup_code").performTextInput("BAD")
        rule.onNodeWithTag("setup_primary").performClick()
        rule.waitFor { rule.onAllNodes(androidx.compose.ui.test.hasText("That code did not work. Check it and try again.")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(w.hub.requestsTo("POST", "/pair").isEmpty())
    }
}
