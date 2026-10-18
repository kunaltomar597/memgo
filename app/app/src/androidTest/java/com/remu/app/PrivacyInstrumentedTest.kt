package com.remu.app

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasTestTag
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.remu.app.core.theme.RemuTheme
import com.remu.app.data.state.Mode
import com.remu.app.data.state.StaffSession
import com.remu.app.feature.enrol.CashierHomeScreen
import com.remu.app.feature.enrol.EnrolViewModel
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On a device: Make member with a real JPEG crop, then no image-like file anywhere in the app's
 * storage (docs/RULES.md App rules, privacy invariant 1).
 */
@RunWith(AndroidJUnit4::class)
class PrivacyInstrumentedTest {
    @get:Rule val rule = createComposeRule()
    private val hub = MockWebServer()

    @After fun tearDown() = hub.shutdown()

    @Test fun noImageLeftOnThePhoneAfterEnrolment() {
        hub.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
                "/health" -> MockResponse().setBody("""{"ok":true,"internet":true}""")
                "/enrol" -> MockResponse().setResponseCode(201).setBody("""{"members":[{"face_index":0,"member_id":"m_1","status":"pending_notice"}],"refused":[]}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        hub.start()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val c = instrumentedContainer(ctx, hub.url("/").toString(), hub.url("/").toString())
        c.appState.login(StaffSession("s_1", "Ravi", "cashier", Mode.CASHIER))
        val vm = EnrolViewModel(SavedStateHandle(), c, c.cameraFactory.create(ctx, false))
        rule.setContent { RemuTheme(forceLight = true) { CashierHomeScreen(vm, {}, {}, {}, {}) } }
        rule.onNodeWithTag("field_name").performScrollTo().performTextInput("Test Member")
        rule.onNodeWithTag("field_phone").performScrollTo().performTextInput("9876543210")
        rule.onNodeWithTag("field_bill_no").performScrollTo().performTextInput("INV-1")
        rule.onNodeWithTag("field_amount").performScrollTo().performTextInput("100")
        rule.onNodeWithTag("make_member").performClick()
        rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag("outcome_success"), true).fetchSemanticsNodes().isNotEmpty() }
        val dirs = listOfNotNull(ctx.cacheDir, ctx.codeCacheDir, ctx.externalCacheDir, ctx.filesDir, ctx.noBackupFilesDir, ctx.getExternalFilesDir(null))
        val images = dirs.flatMap { d -> d.walkTopDown().filter { it.isFile && looksLikeImage(it) }.toList() }
        assertTrue("image-like files: $images", images.isEmpty())
    }

    private fun looksLikeImage(f: File): Boolean {
        if (f.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp", "heic", "bmp", "gif")) return true
        if (f.length() < 4) return false
        val h = f.inputStream().use { s -> ByteArray(4).also { s.read(it) } }
        return (h[0] == 0xFF.toByte() && h[1] == 0xD8.toByte()) || (h[0] == 0x89.toByte() && h[1] == 'P'.code.toByte())
    }
}
