package com.remu.app

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.remu.app.core.theme.RemuTheme
import com.remu.app.feature.login.LoginDestination
import com.remu.app.feature.login.LoginScreen
import com.remu.app.feature.login.LoginViewModel
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** TASK-040 on a device: wrong PIN shows an error, right PIN opens Cashier mode. */
@RunWith(AndroidJUnit4::class)
class LoginInstrumentedTest {
    @get:Rule val rule = createComposeRule()
    private val hub = MockWebServer()
    @After fun tearDown() = hub.shutdown()

    @Test fun wrongThenRightPin() {
        hub.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                val body = request.body.readUtf8()
                return when {
                    path == "/health" -> MockResponse().setBody("""{"ok":true}""")
                    path == "/staff/verify-pin" && body.contains("4821") -> MockResponse().setBody("""{"staff_id":"s_1","name":"Ravi","role":"cashier"}""")
                    path == "/staff/verify-pin" -> MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        hub.start()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val c = instrumentedContainer(ctx, hub.url("/").toString(), hub.url("/").toString())
        var dest: LoginDestination? = null
        rule.setContent { RemuTheme { LoginScreen(LoginViewModel(c), c.language, { dest = it }, {}) } }
        "1111".forEach { rule.onNodeWithTag("pin_key_$it").performScrollTo().performClick() }
        rule.onNodeWithTag("pin_unlock").performClick()
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("Wrong PIN. Try again.")).fetchSemanticsNodes().isNotEmpty() }
        "4821".forEach { rule.onNodeWithTag("pin_key_$it").performScrollTo().performClick() }
        rule.onNodeWithTag("pin_unlock").performClick()
        rule.waitUntil(10_000) { rule.waitForIdle(); dest != null }
        assertEquals(LoginDestination.CASHIER, dest)
    }
}
