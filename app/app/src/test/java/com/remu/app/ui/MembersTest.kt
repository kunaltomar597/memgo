package com.remu.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.remu.app.core.theme.RemuTheme
import com.remu.app.feature.members.HouseholdScreen
import com.remu.app.feature.members.HouseholdViewModel
import com.remu.app.feature.members.MemberDetailScreen
import com.remu.app.feature.members.MemberDetailViewModel
import com.remu.app.feature.members.MemberSearchScreen
import com.remu.app.feature.members.MembersViewModel
import com.remu.app.testsupport.PHONE
import com.remu.app.testsupport.TestWorld
import com.remu.app.testsupport.waitFor
import com.remu.app.testsupport.waitForTag
import com.remu.app.testsupport.waitForText
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

const val MEMBER_LIST = """[{"id":"m_1","display_name":"Rajesh Sharma","status":"pending_notice","member_since":"2026-10-18","last_seen_at":null,"household_label":"Sharma family","has_template":true,"phone_masked":"+91******3210"}]"""
const val MEMBER_CARD = """{"id":"m_1","display_name":"Rajesh Sharma","status":"active","member_since":"2025-10-12","last_seen_at":"2026-10-16T11:00:00+05:30","household_label":"Sharma family","has_template":true,"phone_masked":"+91******3210","phone":"+919876543210","notes":"Asked about matching earrings","discount_flag":true,"household":{"id":"h_1","label":"Sharma family","payer_member_id":"m_1","member_ids":["m_1","m_2"]},"purchases":[{"id":"p_1","bill_no":"INV-1","amount":20000,"items":"Gold necklace","occasion":"Diwali","ts":"2025-10-12T17:42:11+05:30"}],"visits_count":3}"""
const val MEMBER_2 = """{"id":"m_2","display_name":"Sunita Sharma","status":"active","has_template":true,"household":{"id":"h_1","label":"Sharma family","payer_member_id":"m_1","member_ids":["m_1","m_2"]},"purchases":[]}"""

/** TASK-049 and TASK-050: search, add bill, remove member, household editing. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = PHONE)
class MembersTest {
    @get:Rule val rule = createComposeRule()
    private val w = TestWorld()

    @Before fun setUp() {
        w.loginCashier()
        w.hub.on("GET /members", body = MEMBER_LIST)
        w.hub.on("GET /members/m_1", body = MEMBER_CARD)
        w.hub.on("GET /members/m_2", body = MEMBER_2)
    }

    @After fun tearDown() = w.close()

    @Test fun searchByPhoneShowsRows() {
        val vm = MembersViewModel(w.c, ownerMode = false)
        rule.setContent { RemuTheme(forceLight = true) { MemberSearchScreen(vm, {}, {}) } }
        rule.waitForTag("member_row_m_1")
        rule.onNodeWithTag("member_query").performTextInput("98765 43210")
        rule.onNodeWithTag("member_search").performClick()
        rule.waitFor { w.hub.requests.any { it.path == "/members" && it.query == "phone=%2B919876543210" } }
        rule.onNodeWithText("Rajesh Sharma").assertIsDisplayed()
        rule.onNodeWithText("Notice pending").assertIsDisplayed()
        rule.onNodeWithText("Member since 18 Oct 2026").assertIsDisplayed()
        rule.onNodeWithText("Sharma family").assertIsDisplayed()
        rule.onNodeWithText("+91 •••• 3210").assertIsDisplayed()
        rule.onNodeWithTag("member_query").performTextReplacement("Rajesh")
        rule.onNodeWithTag("member_search").performClick()
        rule.waitFor { w.hub.requests.any { it.path == "/members" && it.query == "q=Rajesh" } }
    }

    @Test fun emptyAndErrorStates() {
        w.hub.on("GET /members", body = "[]")
        val vm = MembersViewModel(w.c, ownerMode = false)
        rule.setContent { RemuTheme(forceLight = true) { MemberSearchScreen(vm, {}, {}) } }
        rule.waitForText("No members yet. Make the first one at billing.")
        w.hub.down = true
        rule.onNodeWithTag("member_search").performClick()
        rule.waitForText("Hub did not reply. Check the hub light and the WiFi, then try again.")
    }

    @Test fun addBillFromARow() {
        w.hub.on("POST /members/m_1/purchases", 201, """{"id":"p_2","bill_no":"INV-2","amount":4500,"items":"Silver anklet","occasion":null,"ts":"2026-10-18T12:00:00+05:30"}""")
        val vm = MembersViewModel(w.c, ownerMode = false)
        rule.setContent { RemuTheme(forceLight = true) { MemberSearchScreen(vm, {}, {}) } }
        rule.waitForTag("add_bill_m_1")
        rule.onNodeWithTag("add_bill_m_1").performClick()
        rule.onNodeWithTag("bill_no").performTextInput("INV-2")
        rule.onNodeWithTag("bill_amount").performTextInput("4,500")
        rule.onNodeWithTag("bill_items").performTextInput("Silver anklet")
        rule.onNodeWithTag("bill_send").performScrollTo().performClick()
        rule.waitForText("Bill added.")
        assertEquals("""{"staff_id":"s_ravi","bill_no":"INV-2","amount":4500.0,"items":"Silver anklet"}""", w.hub.requestsTo("POST", "/members/m_1/purchases").single().body)
    }

    @Test fun removeMemberAsksWithTheDesignWordsThenRemoves() {
        w.hub.on("POST /members/m_1/remove", body = """{"status":"withdrawn","template_deleted_at":"2026-10-18T12:00:00+05:30"}""")
        val vm = MemberDetailViewModel(w.c, "m_1", ownerMode = false)
        rule.setContent { RemuTheme(forceLight = true) { MemberDetailScreen(vm, true, {}, {}) } }
        rule.waitForTag("member_card")
        rule.onNodeWithText("5% off next visit").assertIsDisplayed()
        rule.onNodeWithText("Phone: +91 98765 43210").assertIsDisplayed()
        rule.onNodeWithTag("remove_member").performScrollTo().performClick()
        rule.onNodeWithText("Remove Rajesh Sharma? Face data is deleted within one hour. The consent record stays, as proof.").assertIsDisplayed()
        rule.onNodeWithTag("remove_confirm").performClick()
        rule.waitForText("Removed. Face data is deleted within 1 hour.")
        assertEquals("""{"staff_id":"s_ravi"}""", w.hub.requestsTo("POST", "/members/m_1/remove").single().body)
        assertEquals("withdrawn", vm.state.value.card!!.status)
    }

    @Test fun cancelRemoveSendsNothing() {
        val vm = MemberDetailViewModel(w.c, "m_1", ownerMode = false)
        rule.setContent { RemuTheme(forceLight = true) { MemberDetailScreen(vm, true, {}, {}) } }
        rule.waitForTag("member_card")
        rule.onNodeWithTag("remove_member").performScrollTo().performClick()
        rule.onNodeWithText("Cancel").performClick()
        rule.waitForIdle()
        assertEquals(0, w.hub.requestsTo("POST", "/members/m_1/remove").size)
    }

    @Test fun householdRenameAndPayerChange() {
        w.hub.on("PATCH /households/h_1", 200, "{}")
        val vm = HouseholdViewModel(w.c, "m_1")
        var addAdult: Pair<String, String>? = null
        rule.setContent { RemuTheme(forceLight = true) { HouseholdScreen(vm, {}, onAddAdult = { id, l -> addAdult = id to l }) } }
        rule.waitForTag("household_member_m_2")
        rule.onNodeWithText("Sunita Sharma").assertIsDisplayed()
        rule.onNodeWithTag("household_label").performTextReplacement("Sharma parivar")
        rule.onNodeWithTag("payer_pick_m_2").performClick()
        rule.onNodeWithTag("household_save").performClick()
        rule.waitForTag("household_saved")
        assertEquals("""{"label":"Sharma parivar","payer_member_id":"m_2"}""", w.hub.requestsTo("PATCH", "/households/h_1").single().body)
        rule.onNodeWithTag("household_add_adult").performScrollTo().performClick()
        // After saving, the screen reloads from the hub (the mock still answers the old label).
        assertEquals("h_1", addAdult!!.first)
    }

    @Test fun householdRemoveMemberAndConflict() {
        w.hub.on("PATCH /households/h_1", 409, """{"error":"conflict"}""")
        val vm = HouseholdViewModel(w.c, "m_1")
        rule.setContent { RemuTheme(forceLight = true) { HouseholdScreen(vm, {}, { _, _ -> }) } }
        rule.waitForTag("household_remove_m_2")
        rule.onNodeWithTag("household_remove_m_2").performClick()
        rule.onNodeWithTag("household_save").performClick()
        rule.waitForText("This member is already in another household.")
        assertEquals("""{"label":"Sharma family","payer_member_id":"m_1","remove_member_ids":["m_2"]}""", w.hub.requestsTo("PATCH", "/households/h_1").single().body)
    }

    @Test fun memberWithoutHouseholdCreatesOne() {
        w.hub.on("GET /members/m_1", body = MEMBER_CARD.replace(""""household":{"id":"h_1","label":"Sharma family","payer_member_id":"m_1","member_ids":["m_1","m_2"]}""", """"household":null"""))
        w.hub.on("POST /households", 201, """{"id":"h_9"}""")
        val vm = HouseholdViewModel(w.c, "m_1")
        rule.setContent { RemuTheme(forceLight = true) { HouseholdScreen(vm, {}, { _, _ -> }) } }
        rule.waitForTag("household_member_m_1")
        rule.onNodeWithText("Sharma family").assertIsDisplayed()
        rule.onNodeWithTag("household_save").performClick()
        rule.waitForTag("household_saved")
        assertEquals("""{"label":"Sharma family","member_ids":["m_1"],"payer_member_id":"m_1"}""", w.hub.requestsTo("POST", "/households").single().body)
    }
}
