package com.remu.app

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.remu.app.core.theme.RemuTheme
import com.remu.app.data.state.Mode
import com.remu.app.data.state.StaffSession
import com.remu.app.feature.enrol.CashierHomeScreen
import com.remu.app.feature.enrol.EnrolViewModel
import com.remu.app.feature.pairing.HubPairing
import com.remu.app.network.ApiResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TASK-053 against a real local hub. Needs instrumentation arguments, otherwise it is skipped:
 *   ./gradlew connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.hubHost=10.0.2.2:8443 \
 *     -Pandroid.testInstrumentationRunnerArguments.inviteCode=483920 \
 *     -Pandroid.testInstrumentationRunnerArguments.staffPin=4821
 * Pairs with the invite code, verifies the PIN, enrols the still's face and asserts the hub
 * holds one `pending_notice` member for the test phone number. The Maestro flow
 * app/maestro/cashier_enrol.yaml drives the same path through the real camera.
 */
@RunWith(AndroidJUnit4::class)
class CashierEndToEndTest {
    @get:Rule val rule = createComposeRule()

    @Test fun enrolOnePendingNoticeMember() {
        val args = InstrumentationRegistry.getArguments()
        val host = args.getString("hubHost")
        val code = args.getString("inviteCode")
        val pin = args.getString("staffPin")
        assumeTrue("hubHost, inviteCode and staffPin instrumentation arguments are required", host != null && code != null && pin != null)
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val c = instrumentedContainer(ctx, "http://unused/", "http://unused/")
        runBlocking { c.session.update { it.copy(hubBaseUrl = null, hubToken = null, certSha256 = null) } }
        assertNull(runBlocking { HubPairing.pair(c, host!!, code!!) })
        val staff = (runBlocking { c.hub.verifyPin(pin!!) } as ApiResult.Ok).value
        c.appState.login(StaffSession(staff.staffId, staff.name, staff.role, Mode.CASHIER, staff.staffSession))
        val phone = "98" + (10_000_000 + (System.currentTimeMillis() % 89_999_999)).toString().take(8)
        val vm = EnrolViewModel(SavedStateHandle(), c, c.cameraFactory.create(ctx, false))
        rule.setContent { RemuTheme(forceLight = true) { CashierHomeScreen(vm, {}, {}, {}, {}) } }
        rule.onNodeWithTag("field_name").performScrollTo().performTextInput("E2E Test")
        rule.onNodeWithTag("field_phone").performScrollTo().performTextInput(phone)
        rule.onNodeWithTag("field_bill_no").performScrollTo().performTextInput("E2E-1")
        rule.onNodeWithTag("field_amount").performScrollTo().performTextInput("1")
        rule.onNodeWithTag("make_member").performClick()
        rule.waitUntil(15_000) { rule.onAllNodes(hasTestTag("outcome_success"), true).fetchSemanticsNodes().isNotEmpty() }
        val members = (runBlocking { c.hub.members(phone = "+91$phone") } as ApiResult.Ok).value
        assertEquals(1, members.size)
        assertEquals("pending_notice", members.single().status)
    }
}
