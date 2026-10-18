package com.remu.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.remu.app.core.theme.RemuTheme
import com.remu.app.feature.enrol.CashierHomeScreen
import com.remu.app.feature.enrol.EnrolOutcome
import com.remu.app.feature.enrol.EnrolViewModel
import com.remu.app.feature.enrol.FieldError
import com.remu.app.network.RemuJson
import com.remu.app.testsupport.PHONE
import com.remu.app.testsupport.TestWorld
import com.remu.app.testsupport.waitFor
import com.remu.app.testsupport.waitForTag
import com.remu.app.testsupport.waitForText
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** docs/API_CONTRACT.md 7.5: every adult other than the payer gives their own phone. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = PHONE)
class HouseholdPhonesTest {
    @get:Rule val rule = createComposeRule()
    private val w = TestWorld()
    private lateinit var vm: EnrolViewModel

    @Before fun setUp() { w.loginCashier() }
    @After fun tearDown() = w.close()

    private fun showHouseholdOfTwo() {
        vm = EnrolViewModel(SavedStateHandle(), w.c, w.camera, resetAfterMs = 60_000)
        rule.setContent { RemuTheme(forceLight = true) { CashierHomeScreen(vm, {}, {}, onOpenMember = {}, onPairAgain = {}) } }
        rule.waitForIdle()
        rule.onNodeWithTag("household_toggle").performScrollTo().performClick()
        rule.onNodeWithTag("face_1").performClick()
        rule.onNodeWithTag("field_name").performScrollTo().performTextInput("Rajesh Sharma")
        rule.onNodeWithTag("field_phone").performScrollTo().performTextInput("9876543210")
        rule.onNodeWithTag("field_bill_no").performScrollTo().performTextInput("INV-2026-10-18-0417")
        rule.onNodeWithTag("field_amount").performScrollTo().performTextInput("20000")
    }

    private fun enrolBody() = RemuJson.parseToJsonElement(w.hub.requestsTo("POST", "/enrol").last().body).jsonObject

    @Test fun theNonPayerPhoneIsRequiredAndMustDiffer() {
        w.hub.on("POST /enrol", 201, """{"phone_member":false,"household_id":"h_1","members":[{"face_index":0,"member_id":"m_1","status":"pending_notice"},{"face_index":1,"member_id":"m_2","status":"pending_notice"}],"refused":[]}""")
        showHouseholdOfTwo()
        rule.onNodeWithTag("household_payer_hint").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("make_member").performClick()
        rule.waitForText("Enter this adult's own phone number.")
        assertTrue(w.hub.requestsTo("POST", "/enrol").isEmpty())

        rule.onNodeWithTag("field_adult_phone_1").performScrollTo().performTextInput("98765 43210")
        rule.onNodeWithTag("make_member").performClick()
        rule.waitForText("Each adult needs their own phone number.")
        assertTrue(w.hub.requestsTo("POST", "/enrol").isEmpty())

        rule.onNodeWithTag("field_adult_phone_1").performScrollTo().performTextReplacement("12345")
        rule.onNodeWithTag("make_member").performClick()
        rule.waitForText("Enter a 10-digit mobile number.")

        rule.onNodeWithTag("field_adult_phone_1").performScrollTo().performTextReplacement("9123456780")
        rule.onNodeWithTag("make_member").performClick()
        rule.waitForTag("outcome_success")
        val faces = enrolBody()["faces"]!!.jsonArray
        assertEquals(0, enrolBody()["payer_index"]!!.jsonPrimitive.content.toInt())
        assertEquals("+919876543210", faces[0].jsonObject["phone"]!!.jsonPrimitive.content)
        assertEquals("+919123456780", faces[1].jsonObject["phone"]!!.jsonPrimitive.content)
    }

    @Test fun choosingAnotherPayerMovesTheExtraPhoneField() {
        showHouseholdOfTwo()
        rule.onNodeWithTag("field_adult_phone_1").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("payer_1").performScrollTo().performClick()
        rule.onNodeWithTag("field_adult_phone_0").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("The name and phone above are for adult 2, who pays.").performScrollTo().assertIsDisplayed()
    }

    @Test fun hubPhoneRequiredHighlightsThatFace() {
        w.hub.on("POST /enrol", 422, """{"error":"phone_required","face_index":1}""")
        showHouseholdOfTwo()
        rule.onNodeWithTag("field_adult_phone_1").performScrollTo().performTextInput("9123456780")
        rule.onNodeWithTag("make_member").performClick()
        rule.waitForTag("outcome_phone_required")
        assertEquals(EnrolOutcome.AdultPhoneNeeded, vm.state.value.outcome)
        assertEquals(FieldError.ADULT_PHONE_REQUIRED, vm.state.value.errors["adultPhone1"])
        rule.onNodeWithTag("field_adult_phone_1").performScrollTo().assertIsDisplayed()
        rule.waitFor { rule.onAllNodes(androidx.compose.ui.test.hasText("Enter this adult's own phone number.")).fetchSemanticsNodes().size >= 2 }
        // The form and the selection stay, so the cashier fixes one field and taps again.
        assertEquals(2, vm.state.value.selection.selected.size)
        assertEquals("9876543210", vm.state.value.form.phone)
    }
}
