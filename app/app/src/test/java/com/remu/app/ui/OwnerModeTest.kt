package com.remu.app.ui

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.remu.app.core.theme.RemuTheme
import com.remu.app.data.alerts.AlertSource
import com.remu.app.feature.alerts.OwnerHomeScreen
import com.remu.app.feature.alerts.OwnerHomeViewModel
import com.remu.app.feature.members.MemberDetailScreen
import com.remu.app.feature.members.MemberDetailViewModel
import com.remu.app.feature.owner.OwnerSignInScreen
import com.remu.app.feature.owner.OwnerSignInViewModel
import com.remu.app.feature.owner.SignInNext
import com.remu.app.network.hub.AlertPayload
import com.remu.app.network.hub.LastPurchase
import com.remu.app.testsupport.PHONE
import com.remu.app.testsupport.TestWorld
import com.remu.app.testsupport.waitFor
import com.remu.app.testsupport.waitForTag
import com.remu.app.testsupport.waitForText
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant

/** Owner mode against a mock hub and a mock cloud (TASK-075, TASK-078 to TASK-081, TASK-117). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = PHONE)
class OwnerModeTest {
    @get:Rule val rule = createComposeRule()
    private val w = TestWorld(role = "owner", ownerSignedIn = true).also { it.loginOwner() }

    @After fun tearDown() = w.close()

    private val fixture = AlertPayload(
        visitId = "v_1", memberId = "m_1", displayName = "Rajesh Sharma", householdLabel = "Sharma family",
        lastVisit = "2025-10-12T17:42:11+05:30", lastPurchase = LastPurchase(20000.0, "Gold necklace"), notes = "Asked about matching earrings",
    )

    private fun home(): OwnerHomeViewModel {
        w.cloud.on("GET /api/shops/sharma-jewellers-jaipur/devices", body = """[{"id":"d_1","hub_version":"0.1.0","last_seen_at":"2026-10-18T11:00:00+05:30","online":true,"locked":false,"chain_break":false}]""")
        val vm = OwnerHomeViewModel(w.c)
        rule.setContent { RemuTheme { OwnerHomeScreen(vm, {}, {}, { _, _ -> }) } }
        return vm
    }

    @Test fun signInWithMockCloudStoresTheSession() {
        val fresh = TestWorld(role = "owner", ownerSignedIn = false)
        try {
            val vm = OwnerSignInViewModel(fresh.c, forSetup = false)
            var next: SignInNext? = null
            rule.setContent { RemuTheme { OwnerSignInScreen(vm, {}, { next = it }) } }
            rule.onNodeWithTag("signin_email").performTextInput("owner@example.com")
            rule.onNodeWithTag("signin_password").performTextInput("secret")
            rule.onNodeWithTag("signin_submit").performClick()
            rule.waitFor { next != null }
            assertEquals(SignInNext.HOME, next)
            assertEquals("access-1", fresh.c.session.current.ownerAccessToken)
            assertEquals(listOf("owner@example.com"), fresh.auth.signIns)
            runBlocking { fresh.c.owner.signOut() }
            assertEquals(null, fresh.c.session.current.ownerAccessToken)
            assertEquals(1, fresh.auth.signOuts)
        } finally { fresh.close() }
    }

    @Test fun wrongPasswordShowsAnError() {
        val fresh = TestWorld(role = "owner", auth = com.remu.app.testsupport.FakeOwnerAuth(ok = false))
        try {
            val vm = OwnerSignInViewModel(fresh.c, forSetup = false)
            rule.setContent { RemuTheme { OwnerSignInScreen(vm, {}, {}) } }
            rule.onNodeWithTag("signin_email").performTextInput("owner@example.com")
            rule.onNodeWithTag("signin_password").performTextInput("bad")
            rule.onNodeWithTag("signin_submit").performClick()
            rule.waitForText("Email or password is wrong.")
        } finally { fresh.close() }
    }

    @Test fun homeShowsCountsHubStatusAndEmptyState() {
        home()
        rule.waitForTag("cloud_hub_status")
        rule.onNodeWithText("0 visits today, 0 greeted").assertIsDisplayed()
        rule.onNodeWithText("No visits yet today.").assertIsDisplayed()
        rule.onNodeWithText("Hub online").assertIsDisplayed()
    }

    @Test fun alertCardRendersTheExactLine() {
        w.c.alerts.add(fixture, AlertSource.WEBSOCKET, Instant.now().minusSeconds(120))
        home()
        rule.waitForTag("alert_v_1")
        rule.onNodeWithTag("alert_v_1").assertContentDescriptionEquals(
            "Rajesh Sharma · Sharma family · Last visit 12 Oct 2025 · ₹20,000, Gold necklace · Note: Asked about matching earrings",
        )
        rule.onNodeWithText("Rajesh Sharma", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("2 min ago · Gate", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("1 visit today, 0 greeted").assertIsDisplayed()
        rule.onNodeWithTag("greeted_v_1").assertIsDisplayed()
        rule.onNodeWithTag("not_them_v_1").assertIsDisplayed()
    }

    @Test fun cardsOlderThanTenMinutesGreyOut() {
        w.c.alerts.add(fixture, AlertSource.WEBSOCKET, Instant.now().minusSeconds(11 * 60))
        w.c.alerts.add(fixture.copy(visitId = "v_2"), AlertSource.WEBSOCKET, Instant.now().minusSeconds(60))
        val vm = home()
        rule.waitForTag("alert_v_2")
        assertTrue(vm.isStale(w.c.alerts.get("v_1")!!))
        assertTrue(!vm.isStale(w.c.alerts.get("v_2")!!))
        // Newest first.
        assertEquals(listOf("v_2", "v_1"), vm.state.value.alerts.map { it.visitId })
    }

    @Test fun greetedOnTheShopWifiGoesToTheHub() {
        w.hub.on("PATCH /visits/v_1", 200, "{}")
        w.c.alerts.add(fixture, AlertSource.WEBSOCKET)
        home()
        rule.waitForTag("greeted_v_1")
        rule.onNodeWithTag("greeted_v_1").performClick()
        rule.waitForTag("chip_greeted")
        assertEquals("""{"greeted":true,"greeted_by":"s_owner"}""", w.hub.requestsTo("PATCH", "/visits/v_1").single().body)
        assertTrue(w.cloud.requests.none { it.path.contains("/feedback") })
        rule.onNodeWithText("1 visit today, 1 greeted").assertIsDisplayed()
    }

    @Test fun offTheShopWifiFeedbackGoesToTheCloud() {
        w.cloud.on("POST /api/visits/v_1/feedback", 204, "")
        w.hubDown()
        w.c.alerts.add(fixture, AlertSource.FCM)
        home()
        rule.waitForTag("greeted_v_1")
        rule.onNodeWithTag("greeted_v_1").performClick()
        rule.waitForTag("chip_greeted")
        assertEquals("""{"greeted":true,"greeted_by":"s_owner"}""", w.cloud.requestsTo("POST", "/api/visits/v_1/feedback").single().body)
        rule.onNodeWithTag("banner_owner_off_lan").assertIsDisplayed()
    }

    @Test fun notThemSendsNotThemAndDismissesTheCard() {
        w.hub.on("PATCH /visits/v_1", 200, "{}")
        w.c.alerts.add(fixture, AlertSource.WEBSOCKET)
        home()
        rule.waitForTag("not_them_v_1")
        rule.onNodeWithTag("not_them_v_1").performClick()
        rule.waitFor { rule.onAllNodesWithTag("alert_v_1").fetchSemanticsNodes().isEmpty() }
        assertEquals("""{"not_them":true}""", w.hub.requestsTo("PATCH", "/visits/v_1").single().body)
    }

    @Test fun lanAlertsContinueWithInternetDownAndFcmIsPaused() {
        w.hub.on("GET /health", body = com.remu.app.testsupport.MockServer.HEALTH_NO_INTERNET)
        runBlocking { w.c.hubStatus.check() }
        w.c.alerts.add(fixture, AlertSource.WEBSOCKET)
        home()
        rule.waitForTag("banner_owner_shop_internet_down")
        rule.onNodeWithText("Shop internet is down. Alerts on shop WiFi continue. Phone alerts away from the shop are paused.").assertIsDisplayed()
        rule.onNodeWithTag("alert_v_1").assertIsDisplayed()
    }

    @Test fun phoneOfflineBanner() {
        w.internet.online.value = false
        home()
        rule.waitForTag("banner_owner_phone_offline")
        rule.onNodeWithText("No internet on this phone. Alerts on shop WiFi continue. Phone alerts are paused.").assertIsDisplayed()
    }

    // ---- Member card, notes, timeline and clips ------------------------------------------

    private fun detail(): MemberDetailViewModel {
        w.hub.on("GET /members/m_1", body = MEMBER_CARD)
        w.hub.on("GET /staff", body = """[{"id":"s_owner","name":"Rajesh","role":"owner"}]""")
        w.hub.on("GET /visits", body = """[{"id":"v_1","member_id":"m_1","ts":"${Instant.now().minusSeconds(3600)}","camera":"gate","score":0.71,"frames_agreed":4,"greeted":true,"greeted_by":"s_owner","not_them":false,"has_clip":true},{"id":"v_0","member_id":"m_1","ts":"2026-03-01T11:42:00+05:30","greeted":false,"not_them":false,"has_clip":false}]""")
        val vm = MemberDetailViewModel(w.c, "m_1", ownerMode = true)
        rule.setContent { RemuTheme { MemberDetailScreen(vm, true, {}, {}) } }
        rule.waitForTag("member_card")
        return vm
    }

    @Test fun noteEditSavesThroughPatch() {
        w.hub.on("PATCH /members/m_1", 200, "{}")
        detail()
        rule.onNodeWithText("Asked about matching earrings").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("notes_edit").performScrollTo().performClick()
        rule.onNodeWithTag("notes_field").performTextReplacement("Daughter's wedding in March")
        rule.onNodeWithTag("notes_save").performScrollTo().performClick()
        rule.waitForText("Note saved.")
        assertEquals("""{"notes":"Daughter's wedding in March"}""", w.hub.requestsTo("PATCH", "/members/m_1").single().body)
        rule.onNodeWithTag("notes_text").performScrollTo().assertIsDisplayed()
    }

    @Test fun timelineShowsVisitsAndPurchasesAndPlaysClipsOnLan() {
        w.hub.onRaw("GET /clips/v_1") { okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "video/mp4").setBody(okio.Buffer().write(ByteArray(64) { 1 })) }
        detail()
        rule.waitForTag("visit_v_1")
        rule.onNodeWithTag("purchase_p_1").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Greeted by Rajesh", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Not greeted", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("play_clip_v_1").performScrollTo().performClick()
        rule.waitForTag("clip_player")
        assertEquals(1, w.hub.requestsTo("GET", "/clips/v_1").size)
    }

    @Test fun offLanClipsDoNotPlayAndNoClipRequestLeaves() {
        val vm = detail()
        rule.waitForTag("visit_v_1")
        w.hubDown()
        rule.waitForTag("clip_lan_only_v_1")
        rule.onNodeWithText("Clips play only on shop WiFi").performScrollTo().assertIsDisplayed()
        assertTrue(rule.onAllNodesWithTag("play_clip_v_1").fetchSemanticsNodes().isEmpty())
        vm.playClip(vm.state.value.visits.first())
        rule.waitForIdle()
        assertTrue(w.hub.requests.none { it.path.startsWith("/clips/") })
    }

    @Test fun offLanRemoveGoesToTheCloud() {
        w.cloud.on("POST /api/members/m_1/withdraw", 202, "")
        detail()
        w.hubDown()
        rule.onNodeWithTag("remove_member").performScrollTo().performClick()
        rule.onNodeWithTag("remove_confirm").performClick()
        rule.waitForText("Removed. Face data is deleted within 1 hour.")
        assertEquals(1, w.cloud.requestsTo("POST", "/api/members/m_1/withdraw").size)
        assertTrue(w.hub.requests.none { it.path.endsWith("/remove") })
    }

    @Test fun offLanNotesAreDisabled() {
        detail()
        w.hubDown()
        rule.waitFor { !w.c.hubStatus.status.value.onLan }
        rule.onNodeWithTag("notes_edit").performScrollTo().assertIsNotEnabled()
    }
}
