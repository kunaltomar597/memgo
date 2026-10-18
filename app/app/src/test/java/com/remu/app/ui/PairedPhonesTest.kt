package com.remu.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.remu.app.core.locale.AppLanguage
import com.remu.app.core.theme.RemuTheme
import com.remu.app.feature.settings.OwnerSettingsScreen
import com.remu.app.feature.settings.TokensScreen
import com.remu.app.feature.settings.TokensViewModel
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

/** docs/API_CONTRACT.md 7.3: "Paired phones" in Owner settings, with Revoke behind a confirmation. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = PHONE)
class PairedPhonesTest {
    @get:Rule val rule = createComposeRule()
    private val w = TestWorld(role = "owner", ownerSignedIn = true).also { it.loginOwner() }
    private val mine = TokensViewModel.tokenId("app-token-1")

    @After fun tearDown() = w.close()

    private fun tokens(counterRevoked: String? = null) = """[
        {"id":"$mine","role":"owner","created_at":"2026-10-18T10:00:00+05:30","revoked_at":null},
        {"id":"0f0f0f0f0f0f","role":"counter","created_at":"2026-10-18T11:00:00+05:30","revoked_at":${counterRevoked?.let { "\"$it\"" } ?: "null"}},
        {"id":"aaaaaaaaaaaa","role":"counter","created_at":"2026-10-01T11:00:00+05:30","revoked_at":"2026-10-02T09:00:00+05:30"}]"""

    private fun show() {
        w.hub.on("GET /tokens", body = tokens())
        rule.setContent { RemuTheme { TokensScreen(TokensViewModel(w.c), {}) } }
        rule.waitForTag("token_0f0f0f0f0f0f")
    }

    @Test fun ownerSettingsLinksToPairedPhones() {
        var opened = false
        rule.setContent { RemuTheme { OwnerSettingsScreen(w.c, {}, {}, {}, {}, {}, {}, onPairedPhones = { opened = true }) } }
        rule.onNodeWithTag("settings_paired_phones").performScrollTo().performClick()
        assertTrue(opened)
    }

    @Test fun listsPhonesAndMarksThisOne() {
        show()
        rule.onNodeWithText("Paired phones").assertIsDisplayed()
        rule.onNodeWithTag("token_this_$mine").assertIsDisplayed()
        // This phone and an already revoked phone offer no Revoke.
        assertTrue(rule.onAllNodesWithTag("token_revoke_$mine").fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodesWithTag("token_revoke_aaaaaaaaaaaa").fetchSemanticsNodes().isEmpty())
        rule.onNodeWithTag("token_revoked_aaaaaaaaaaaa").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("token_revoke_0f0f0f0f0f0f").performScrollTo().assertIsDisplayed()
        assertEquals("Bearer app-token-1", w.hub.requestsTo("GET", "/tokens").first().headers["Authorization"])
    }

    @Test fun revokeAsksFirstAndCancelSendsNothing() {
        show()
        rule.onNodeWithTag("token_revoke_0f0f0f0f0f0f").performScrollTo().performClick()
        rule.waitForTag("revoke_sheet")
        rule.onNodeWithText("Revoke this phone (Counter phone)? It stops working with the hub at once. Pairing it again needs a new code.").assertIsDisplayed()
        rule.onNodeWithTag("revoke_cancel").performClick()
        rule.waitFor { rule.onAllNodesWithTag("revoke_sheet").fetchSemanticsNodes().isEmpty() }
        assertTrue(w.hub.requests.none { it.path.endsWith("/revoke") })
    }

    @Test fun confirmedRevokeCallsTheHubAndReloads() {
        w.hub.on("POST /tokens/0f0f0f0f0f0f/revoke", 204, "")
        show()
        rule.onNodeWithTag("token_revoke_0f0f0f0f0f0f").performScrollTo().performClick()
        rule.waitForTag("revoke_confirm")
        w.hub.on("GET /tokens", body = tokens(counterRevoked = "2026-10-18T12:00:00+05:30"))
        rule.onNodeWithTag("revoke_confirm").performClick()
        rule.waitForText("Phone revoked.")
        assertEquals(1, w.hub.requestsTo("POST", "/tokens/0f0f0f0f0f0f/revoke").size)
        rule.waitForTag("token_revoked_0f0f0f0f0f0f")
        assertEquals(2, w.hub.requestsTo("GET", "/tokens").size)
    }

    @Test fun revokeFailureStaysInTheSheet() {
        w.hub.on("POST /tokens/0f0f0f0f0f0f/revoke", 500, """{"error":"internal"}""")
        show()
        rule.onNodeWithTag("token_revoke_0f0f0f0f0f0f").performScrollTo().performClick()
        rule.waitForTag("revoke_confirm")
        rule.onNodeWithTag("revoke_confirm").performClick()
        rule.waitForText("Hub did not reply. Check the hub light and the WiFi, then try again.")
        rule.onNodeWithTag("revoke_sheet").assertIsDisplayed()
    }

    @Test fun hindiLabels() {
        w.language.lang = AppLanguage.HINDI
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val hi = ctx.createConfigurationContext(android.content.res.Configuration(ctx.resources.configuration).apply { setLocale(java.util.Locale("hi")) })
        assertEquals("जुड़े हुए फ़ोन", hi.getString(com.remu.app.R.string.paired_phones))
        assertEquals("पहुँच हटाएँ", hi.getString(com.remu.app.R.string.paired_phone_revoke))
    }
}
