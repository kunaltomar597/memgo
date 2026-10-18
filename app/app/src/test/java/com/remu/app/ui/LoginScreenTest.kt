package com.remu.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.remu.app.core.theme.RemuTheme
import com.remu.app.data.state.Mode
import com.remu.app.feature.login.LoginDestination
import com.remu.app.feature.login.LoginScreen
import com.remu.app.feature.login.LoginViewModel
import com.remu.app.testsupport.PHONE
import com.remu.app.testsupport.TestWorld
import com.remu.app.testsupport.waitFor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** TASK-040: a wrong PIN shows an error; a right PIN opens the chosen mode. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = PHONE)
class LoginScreenTest {
    @get:Rule val rule = createComposeRule()
    private val w = TestWorld(role = "owner", ownerSignedIn = true)
    private var dest: LoginDestination? = null

    @After fun tearDown() = w.close()

    lateinit var vm: LoginViewModel
    private fun show() {
        vm = LoginViewModel(w.c)
        rule.setContent { RemuTheme { LoginScreen(vm, w.language, onDestination = { dest = it }, onPairAgain = {}) } }
    }

    private fun type(pin: String) = pin.forEach { rule.onNodeWithTag("pin_key_$it").performScrollTo().performClick() }

    @Test fun wrongPinShowsAnError() {
        w.hub.on("POST /staff/verify-pin", 401, """{"error":"unauthorized"}""")
        show()
        type("1111")
        rule.onNodeWithTag("pin_unlock").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Wrong PIN. Try again.").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Wrong PIN. Try again.").assertIsDisplayed()
        assertEquals(null, dest)
        assertEquals("""{"pin":"1111"}""", w.hub.requestsTo("POST", "/staff/verify-pin").single().body)
    }

    @Test fun rightPinOpensCashierMode() {
        w.hub.on("POST /staff/verify-pin", body = """{"staff_id":"s_ravi","name":"Ravi","role":"cashier"}""")
        show()
        rule.onNodeWithTag("mode_cashier").performClick()
        type("4821")
        rule.onNodeWithTag("pin_unlock").performClick()
        rule.waitFor { dest != null }
        assertEquals(LoginDestination.CASHIER, dest)
        assertEquals(Mode.CASHIER, w.c.appState.staff.value!!.mode)
        assertEquals("s_ravi", w.c.appState.staff.value!!.staffId)
    }

    @Test fun rightOwnerPinOpensOwnerMode() {
        w.hub.on("POST /staff/verify-pin", body = """{"staff_id":"s_owner","name":"Rajesh","role":"owner"}""")
        show()
        rule.onNodeWithTag("mode_owner").performClick()
        type("482199")
        rule.onNodeWithTag("pin_unlock").performClick()
        rule.waitFor { dest != null }
        assertEquals(LoginDestination.OWNER_HOME, dest)
        assertEquals("s_owner", w.c.session.current.ownerStaffId)
    }

    @Test fun cashierPinCannotOpenOwnerMode() {
        w.hub.on("POST /staff/verify-pin", body = """{"staff_id":"s_ravi","name":"Ravi","role":"cashier"}""")
        show()
        rule.onNodeWithTag("mode_owner").performClick()
        type("4821")
        rule.onNodeWithTag("pin_unlock").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Owner mode needs an owner PIN.").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(null, dest)
        assertEquals(null, w.c.appState.staff.value)
    }

    @Test fun hubDownOffersOwnerCloudPath() {
        w.hubDown()
        show()
        rule.onNodeWithTag("mode_owner").performClick()
        type("4821")
        rule.onNodeWithTag("pin_unlock").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("login_offline").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Hub did not reply. Check the hub light and the WiFi, then try again.").assertIsDisplayed()
        rule.onNodeWithTag("login_offline").performClick()
        rule.waitFor { dest != null }
        assertEquals(LoginDestination.OWNER_HOME, dest)
    }
}
