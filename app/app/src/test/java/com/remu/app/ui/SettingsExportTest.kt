package com.remu.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.remu.app.core.locale.AppLanguage
import com.remu.app.core.theme.RemuTheme
import com.remu.app.feature.export.ExportScreen
import com.remu.app.feature.export.ExportShare
import com.remu.app.feature.export.ExportViewModel
import com.remu.app.feature.export.ExportedFile
import com.remu.app.feature.settings.CashierSettingsScreen
import com.remu.app.feature.settings.DeviceStatusScreen
import com.remu.app.feature.settings.DeviceStatusViewModel
import com.remu.app.feature.settings.OwnerSettingsScreen
import com.remu.app.feature.settings.StaffScreen
import com.remu.app.feature.settings.StaffViewModel
import com.remu.app.testsupport.PHONE
import com.remu.app.testsupport.TestWorld
import com.remu.app.testsupport.waitFor
import com.remu.app.testsupport.waitForTag
import com.remu.app.testsupport.waitForText
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/** TASK-082 owner settings and TASK-094 consent export. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = PHONE)
class SettingsExportTest {
    @get:Rule val rule = createComposeRule()
    private val shared = CopyOnWriteArrayList<List<ExportedFile>>()
    private val w = TestWorld(role = "owner", ownerSignedIn = true, exportShare = ExportShare { files, _ -> shared += files }).also { it.loginOwner() }

    @After fun tearDown() = w.close()

    @Test fun staffListAndPinCreation() {
        w.hub.on("GET /staff", body = """[{"id":"s_owner","name":"Rajesh","role":"owner","created_at":"2026-10-18T10:00:00+05:30"}]""")
        w.hub.on("POST /staff", 201, """{"staff_id":"s_ravi"}""")
        val vm = StaffViewModel(w.c)
        rule.setContent { RemuTheme { StaffScreen(vm, {}) } }
        rule.waitForTag("staff_s_owner")
        rule.onNodeWithTag("staff_add").performClick()
        rule.onNodeWithTag("staff_name").performTextInput("Ravi")
        rule.onNodeWithTag("staff_pin").performTextInput("4821")
        rule.onNodeWithTag("staff_pin2").performTextInput("4822")
        rule.onNodeWithTag("staff_save").performScrollTo().performClick()
        rule.waitForText("The two PINs are not the same.")
        assertTrue(w.hub.requestsTo("POST", "/staff").isEmpty())
        rule.onNodeWithTag("staff_pin2").performTextInput("") // keep focus; replace below
        vm.onDraft(vm.state.value.draft.copy(pin2 = "4821"))
        rule.onNodeWithTag("staff_save").performScrollTo().performClick()
        rule.waitForTag("staff_added")
        assertEquals("""{"name":"Ravi","role":"cashier","pin":"4821"}""", w.hub.requestsTo("POST", "/staff").single().body)
    }

    @Test fun shortPinIsRefused() {
        w.hub.on("GET /staff", body = "[]")
        val vm = StaffViewModel(w.c)
        rule.setContent { RemuTheme { StaffScreen(vm, {}) } }
        rule.onNodeWithTag("staff_add").performClick()
        rule.onNodeWithTag("staff_name").performTextInput("Ravi")
        rule.onNodeWithTag("staff_pin").performTextInput("482")
        rule.onNodeWithTag("staff_pin2").performTextInput("482")
        rule.onNodeWithTag("staff_save").performScrollTo().performClick()
        rule.waitForText("The PIN must be 4 to 6 digits.")
    }

    @Test fun pinCreationNeedsTheShopWifi() {
        w.hubDown()
        val vm = StaffViewModel(w.c)
        rule.setContent { RemuTheme { StaffScreen(vm, {}) } }
        rule.waitForTag("staff_needs_lan")
        rule.onNodeWithText("Adding staff needs the shop WiFi. Connect to the shop WiFi to create a PIN.").assertIsDisplayed()
        rule.onNodeWithTag("staff_add").assertIsNotEnabled()
    }

    @Test fun languageSwitchInOwnerSettings() {
        rule.setContent { RemuTheme { OwnerSettingsScreen(w.c, {}, {}, {}, {}, {}, {}) } }
        rule.onNodeWithTag("lang_hi").performScrollTo().performClick()
        assertEquals(listOf(AppLanguage.HINDI), w.language.sets)
        rule.onNodeWithText("Script hi-IN-v1.0 · Notice wa-v1.0").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Alert cooldown: 10 minutes").performScrollTo().assertIsDisplayed()
    }

    @Test fun signOutClearsTheSession() {
        var out = false
        rule.setContent { RemuTheme { OwnerSettingsScreen(w.c, {}, {}, {}, {}, {}, { out = true }) } }
        rule.onNodeWithTag("sign_out").performClick()
        rule.waitFor { out }
        assertEquals(null, w.c.session.current.ownerAccessToken)
        assertEquals(null, w.c.appState.staff.value)
    }

    @Test fun cashierSettingsLanguageCameraAndLock() {
        w.loginCashier()
        var locked = false
        rule.setContent { RemuTheme(forceLight = true) { CashierSettingsScreen(w.c, {}, {}, { locked = true }) } }
        rule.onNodeWithTag("lang_hi").performScrollTo().performClick()
        assertEquals(listOf(AppLanguage.HINDI), w.language.sets)
        rule.onNodeWithTag("camera_front").performScrollTo().performClick()
        rule.waitFor { w.c.session.current.useFrontCamera }
        rule.onNodeWithTag("lock").performClick()
        assertTrue(locked)
        assertEquals(null, w.c.appState.staff.value)
    }

    @Test fun deviceStatusShowsHubAndCloud() {
        w.cloud.on("GET /api/shops/sharma-jewellers-jaipur/devices", body = """[{"id":"d_1","hub_version":"0.1.0","last_seen_at":"2026-10-18T11:00:00+05:30","online":true,"locked":false,"chain_break":true}]""")
        val vm = DeviceStatusViewModel(w.c)
        rule.setContent { RemuTheme { DeviceStatusScreen(vm, {}, {}) } }
        rule.waitForText("The consent record chain needs checking. Contact Remu support.")
        rule.onNodeWithText("Face members on the hub: 42", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("Gate camera: working", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun exportSharesSignedPdfAndCsv() {
        w.cloud.on("POST /api/shops/sharma-jewellers-jaipur/consent-export", 200, "%PDF-1.4", mapOf("X-Remu-Signature" to "c2lnbmF0dXJl"))
        val vm = ExportViewModel(w.c, w.c.exportShare!!, "Share consent records")
        rule.setContent { RemuTheme { ExportScreen(vm, {}) } }
        rule.onNodeWithText("No export yet.").assertIsDisplayed()
        rule.onNodeWithTag("export_button").performClick()
        rule.waitForTag("export_done")
        val files = shared.single()
        assertEquals(4, files.size)
        assertTrue(files.any { it.name.endsWith(".pdf") && it.mime == "application/pdf" })
        assertTrue(files.any { it.name.endsWith(".csv") && it.mime == "text/csv" })
        assertTrue(files.any { it.name.endsWith(".pdf.sig") && String(it.bytes) == "c2lnbmF0dXJl" })
        val queries = w.cloud.requestsTo("POST", "/api/shops/sharma-jewellers-jaipur/consent-export").map { it.query }.toSet()
        assertEquals(setOf("format=pdf", "format=csv"), queries)
        rule.onNodeWithTag("export_last").assertIsDisplayed()
        assertTrue(w.c.session.current.lastExportAt != null)
    }

    @Test fun exportFailureShowsRetry() {
        w.cloud.on("POST /api/shops/sharma-jewellers-jaipur/consent-export", 500, "{}")
        val vm = ExportViewModel(w.c, w.c.exportShare!!, "Share consent records")
        rule.setContent { RemuTheme { ExportScreen(vm, {}) } }
        rule.onNodeWithTag("export_button").performClick()
        rule.waitForText("The export did not finish. Check the internet, then try again.")
        w.cloud.on("POST /api/shops/sharma-jewellers-jaipur/consent-export", 200, "x", mapOf("X-Remu-Signature" to "s"))
        rule.onNodeWithText("Retry").performClick()
        rule.waitForTag("export_done")
        assertEquals(1, shared.size)
    }
}
