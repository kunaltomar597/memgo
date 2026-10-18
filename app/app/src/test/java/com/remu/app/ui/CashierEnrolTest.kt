package com.remu.app.ui

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.remu.app.core.theme.RemuTheme
import com.remu.app.feature.enrol.CashierHomeScreen
import com.remu.app.feature.enrol.EnrolViewModel
import com.remu.app.network.RemuJson
import com.remu.app.testsupport.PHONE
import com.remu.app.testsupport.TestWorld
import com.remu.app.testsupport.recordedFrame
import com.remu.app.testsupport.waitFor
import com.remu.app.testsupport.waitForTag
import com.remu.app.testsupport.waitForText
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** Cashier mode against a mock hub (TASK-042 to TASK-048, TASK-051, TASK-117). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = PHONE)
class CashierEnrolTest {
    @get:Rule val rule = createComposeRule()
    private val w = TestWorld()
    private lateinit var vm: EnrolViewModel
    private var opened: String? = null

    @Before fun setUp() { w.loginCashier() }
    @After fun tearDown() = w.close()

    private fun show() {
        vm = EnrolViewModel(SavedStateHandle(), w.c, w.camera, resetAfterMs = 60_000)
        rule.setContent { RemuTheme(forceLight = true) { CashierHomeScreen(vm, {}, {}, onOpenMember = { opened = it }, onPairAgain = {}) } }
        rule.waitForIdle()
    }

    private fun fill(name: String = "Rajesh Sharma", phone: String = "9876543210") {
        rule.onNodeWithTag("field_name").performScrollTo().performTextInput(name)
        rule.onNodeWithTag("field_phone").performScrollTo().performTextInput(phone)
        rule.onNodeWithTag("field_bill_no").performScrollTo().performTextInput("INV-2026-10-18-0417")
        rule.onNodeWithTag("field_amount").performScrollTo().performTextInput("20000")
        rule.onNodeWithTag("field_items").performScrollTo().performTextInput("Gold necklace")
        rule.onNodeWithTag("field_occasion").performScrollTo().performTextInput("Diwali")
    }

    private fun enrolBody() = RemuJson.parseToJsonElement(w.hub.requestsTo("POST", "/enrol").last().body).jsonObject

    @Test fun boxesShowAndTheLargestFaceIsPreselected() {
        show()
        rule.onNodeWithTag("face_2").assertContentDescriptionEquals("Face 2, selected")
        rule.onNodeWithTag("face_1").assertContentDescriptionEquals("Face 1")
        rule.onNodeWithTag("face_1").performClick()
        rule.onNodeWithTag("face_1").assertContentDescriptionEquals("Face 1, selected")
        rule.onNodeWithTag("face_2").assertContentDescriptionEquals("Face 2")
    }

    @Test fun noFaceHint() {
        w.camera.flow.value = recordedFrame(*IntArray(0))
        show()
        rule.onNodeWithTag("no_face_hint").assertIsDisplayed()
        rule.onNodeWithText("No face in view. Ask the customer to face the phone.").assertIsDisplayed()
    }

    @Test fun scriptLineIsShownVerbatimInBothLanguages() {
        show()
        rule.onNodeWithText("आप अब हमारे मेंबर हो गए। अगली बार आते ही हम आपको पहचान के वेलकम करेंगे, और 5% ऑफ मिलेगा।").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("You're a member now. Next time, we'll recognise you as you walk in and welcome you, and you get 5% off.").performScrollTo().assertIsDisplayed()
        // One button, no separate consent control anywhere.
        assertEquals(1, rule.onAllNodesWithTag("make_member").fetchSemanticsNodes().size)
        assertTrue(rule.onAllNodes(androidx.compose.ui.test.hasText("consent", substring = true, ignoreCase = true)).fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodes(androidx.compose.ui.test.isToggleable()).fetchSemanticsNodes().size == 1) // the Household switch only
    }

    @Test fun makeMemberSuccessSendsTheContractPayload() {
        w.hub.on("POST /enrol", 201, """{"phone_member":false,"household_id":null,"members":[{"face_index":0,"member_id":"m_1","status":"pending_notice","consent_record_id":"c_1"}],"refused":[]}""")
        show()
        fill()
        rule.onNodeWithTag("make_member").performClick()
        rule.waitForTag("outcome_success")
        rule.onNodeWithText("Member added. Notice pending on WhatsApp.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Notice pending").assertIsDisplayed()
        val b = enrolBody()
        assertEquals("s_ravi", b["staff_id"]!!.jsonPrimitive.content)
        assertEquals("+919876543210", b["phone"]!!.jsonPrimitive.content)
        assertEquals("INV-2026-10-18-0417", b["bill_no"]!!.jsonPrimitive.content)
        assertEquals("Diwali", b["occasion"]!!.jsonPrimitive.content)
        val faces = b["faces"]!!.jsonArray
        assertEquals(1, faces.size)
        assertEquals(5, faces[0].jsonObject["landmarks"]!!.jsonArray.size)
        assertEquals(listOf(2), w.camera.captures.single())
        // The form is cleared for the next customer.
        assertEquals("", vm.state.value.form.phone)
    }

    @Test fun under18ShowsTheLabelAndNoOverride() {
        w.hub.on("POST /enrol", 422, """{"error":"under_18","refused":[{"face_index":0,"reason":"under_18"}]}""")
        show()
        fill()
        rule.onNodeWithTag("make_member").performClick()
        rule.waitForTag("outcome_under18")
        rule.onNodeWithTag("under18_2").assertIsDisplayed()
        rule.onNodeWithText("Under 18. This customer cannot become a member.").performScrollTo().assertIsDisplayed()
        assertTrue(rule.onAllNodes(androidx.compose.ui.test.hasText("Retry")).fetchSemanticsNodes().isEmpty())
    }

    @Test fun hubErrorShowsRetryAndRetryWorks() {
        w.hub.on("POST /enrol", 500, """{"error":"internal"}""")
        show()
        fill()
        rule.onNodeWithTag("make_member").performClick()
        rule.waitForText("Hub did not reply. Check the hub light and the WiFi, then try again.")
        // The camera stays live and the form is kept.
        rule.onNodeWithTag("fake_preview").assertIsDisplayed()
        assertEquals("9876543210", vm.state.value.form.phone)
        w.hub.on("POST /enrol", 201, """{"members":[{"face_index":0,"member_id":"m_1","status":"pending_notice"}],"refused":[]}""")
        rule.onNodeWithText("Retry").performScrollTo().performClick()
        rule.waitForTag("outcome_success")
        assertEquals(2, w.hub.requestsTo("POST", "/enrol").size)
    }

    @Test fun validationErrorsShowInlineAndNothingIsSent() {
        show()
        rule.onNodeWithTag("field_phone").performScrollTo().performTextInput("12345")
        rule.onNodeWithTag("make_member").performClick()
        rule.waitForText("Enter a 10-digit mobile number.")
        rule.onNodeWithText("Enter the bill amount.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Enter the customer's name.").performScrollTo().assertIsDisplayed()
        assertTrue(w.hub.requestsTo("POST", "/enrol").isEmpty())
    }

    @Test fun householdOfTwoWithOneRefused() {
        w.hub.on("POST /enrol", 201, """{"phone_member":false,"household_id":"h_1","members":[{"face_index":0,"member_id":"m_1","status":"pending_notice"}],"refused":[{"face_index":1,"reason":"under_18"}]}""")
        show()
        rule.onNodeWithTag("household_toggle").performScrollTo().performClick()
        rule.onNodeWithTag("face_1").performClick()
        rule.onNodeWithTag("face_2").assertContentDescriptionEquals("Face 2, selected")
        rule.onNodeWithTag("face_1").assertContentDescriptionEquals("Face 1, selected")
        rule.onNodeWithTag("chip_2").assertIsDisplayed()
        rule.onNodeWithTag("chip_1").assertIsDisplayed()
        fill()
        rule.onNodeWithTag("field_household_label").performScrollTo()
        rule.onNodeWithText("Sharma family").assertIsDisplayed()
        rule.onNodeWithTag("payer_1").performScrollTo().performClick()
        rule.onNodeWithTag("field_adult_name_1").performScrollTo().performTextInput("Sunita Sharma")
        rule.onNodeWithTag("make_member").performClick()
        rule.waitForTag("outcome_partial")
        rule.onNodeWithText("One face was refused (under 18). The others are members.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("under18_1").assertIsDisplayed()
        val b = enrolBody()
        assertEquals("Sharma family", b["household_label"]!!.jsonPrimitive.content)
        assertEquals(1, b["payer_index"]!!.jsonPrimitive.content.toInt())
        assertEquals(2, b["faces"]!!.jsonArray.size)
        assertEquals("Sunita Sharma", b["faces"]!!.jsonArray[1].jsonObject["display_name"]!!.jsonPrimitive.content)
        assertEquals(listOf(2, 1), w.camera.captures.single())
    }

    @Test fun chipRemovesAnAdult() {
        show()
        rule.onNodeWithTag("household_toggle").performScrollTo().performClick()
        rule.onNodeWithTag("face_1").performClick()
        rule.onNodeWithTag("chip_1").performClick()
        rule.onNodeWithTag("face_1").assertContentDescriptionEquals("Face 1")
    }

    @Test fun skipFaceSavesBillOnlyAndCounts() {
        w.hub.on("POST /enrol", 201, """{"phone_member":true,"household_id":null,"members":[{"member_id":"m_9","status":"pending_notice"}],"refused":[]}""")
        show()
        fill(name = "")
        rule.onNodeWithTag("skip_face").performClick()
        rule.waitForTag("outcome_phone_member")
        rule.onNodeWithText("Saved without face membership").performScrollTo().assertIsDisplayed()
        assertEquals(0, enrolBody()["faces"]!!.jsonArray.size)
        assertTrue(w.camera.captures.isEmpty())
        rule.waitFor { w.c.session.current.skipFaceCount == 1 }
    }

    @Test fun conflictOpensTheMember() {
        w.hub.on("POST /enrol", 409, """{"error":"conflict","member_id":"m_7"}""")
        show()
        fill()
        rule.onNodeWithTag("make_member").performClick()
        rule.waitFor { opened == "m_7" }
    }

    @Test fun faceLeftViewBeforeCapture() {
        show()
        fill()
        w.camera.flow.value = null
        // Selection stays manual-free; the fake camera reports the face gone.
        vm.tapFace(2)
        w.camera.flow.value = recordedFrame(1)
        rule.waitForIdle()
        vm.tapFace(9)
        rule.onNodeWithTag("make_member").performClick()
        rule.waitForText("Keep the selected faces in view, then try again.")
        assertTrue(w.hub.requestsTo("POST", "/enrol").isEmpty())
    }

    @Test fun hubOfflineBannerDisablesMakeMemberAndClears() {
        show()
        rule.onNodeWithTag("make_member").assertIsEnabled()
        w.hubDown()
        rule.waitForTag("banner_hub_unreachable")
        rule.onNodeWithText("Hub not reachable. Make member is paused. Billing continues.").assertIsDisplayed()
        rule.onNodeWithTag("make_member").assertIsNotEnabled()
        rule.onNodeWithTag("fake_preview").assertIsDisplayed()
        rule.onNodeWithTag("hub_chip_disconnected").performScrollTo().assertIsDisplayed()
        w.hubUp()
        rule.waitFor { rule.onAllNodesWithTag("banner_hub_unreachable").fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithTag("make_member").assertIsEnabled()
    }

    @Test fun reconnectsOnItsOwnWithinThirtySeconds() = runBlocking {
        val fast = com.remu.app.data.state.HubStatusMonitor(w.c.hub, w.scope, okIntervalMs = 200, downIntervalMs = 100)
        w.hub.down = true
        fast.start()
        rule.waitFor { fast.status.value.reachable == false }
        w.hub.down = false
        rule.waitFor { fast.status.value.reachable == true }
        fast.stop()
    }

    @Test fun internetDownIsToldApartFromHubDown() {
        w.hub.on("GET /health", body = com.remu.app.testsupport.MockServer.HEALTH_NO_INTERNET)
        runBlocking { w.c.hubStatus.check() }
        show()
        rule.waitForTag("banner_no_internet")
        rule.onNodeWithText("No internet. Billing continues. Notices will go when internet returns.").assertIsDisplayed()
        assertTrue(rule.onAllNodesWithTag("banner_hub_unreachable").fetchSemanticsNodes().isEmpty())
        rule.onNodeWithTag("make_member").assertIsEnabled()
    }

    @Test fun noImageRemainsInTheAppCacheAfterARequest() {
        w.hub.on("POST /enrol", 201, """{"members":[{"face_index":0,"member_id":"m_1","status":"pending_notice"}],"refused":[]}""")
        show()
        fill()
        rule.onNodeWithTag("make_member").performClick()
        rule.waitForTag("outcome_success")
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dirs = listOfNotNull(ctx.cacheDir, ctx.codeCacheDir, ctx.externalCacheDir, ctx.filesDir, ctx.noBackupFilesDir)
        val imageLike = dirs.flatMap { d -> d.walkTopDown().filter { it.isFile }.filter(::looksLikeImage).toList() }
        assertTrue("image-like files left behind: $imageLike", imageLike.isEmpty())
        // And the crop bytes handed to the request were wiped in memory.
        assertFalse(vm.state.value.sending != null)
    }

    private fun looksLikeImage(f: File): Boolean {
        val ext = f.extension.lowercase()
        if (ext in setOf("jpg", "jpeg", "png", "webp", "heic", "bmp", "gif", "yuv")) return true
        val head = f.inputStream().use { s -> ByteArray(4).also { s.read(it) } }
        return (head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte()) || (head[0] == 0x89.toByte() && head[1] == 'P'.code.toByte())
    }
}
