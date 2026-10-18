package com.remu.app.ui

import android.Manifest
import android.app.NotificationManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.remu.app.core.theme.RemuTheme
import com.remu.app.data.alerts.AlertItem
import com.remu.app.data.alerts.AlertSource
import com.remu.app.feature.alerts.AndroidAlertNotifier
import com.remu.app.feature.enrol.CashierHomeScreen
import com.remu.app.feature.enrol.EnrolViewModel
import com.remu.app.feature.login.LoginScreen
import com.remu.app.feature.login.LoginViewModel
import com.remu.app.network.hub.AlertPayload
import com.remu.app.network.hub.LastPurchase
import com.remu.app.testsupport.FakeLanguageManager
import com.remu.app.testsupport.TestWorld
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant

/** TASK-052 and TASK-083: screens in Hindi; the notification text is built on the phone. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "hi-w411dp-h891dp-port-xxhdpi")
class LanguageAndNotificationTest {
    @get:Rule val rule = createComposeRule()
    private val w = TestWorld()

    @After fun tearDown() = w.close()

    @Test fun loginInHindi() {
        rule.setContent { RemuTheme { LoginScreen(LoginViewModel(w.c), w.language, {}, {}) } }
        rule.onNodeWithText("स्टाफ़ लॉगिन").assertIsDisplayed()
        rule.onNodeWithText("कैशियर मोड").assertIsDisplayed()
        rule.onNodeWithText("मालिक मोड").assertIsDisplayed()
        rule.onNodeWithText("अपना PIN डालें").assertIsDisplayed()
        rule.onNodeWithText("खोलें").assertIsDisplayed()
    }

    @Test fun cashierHomeInHindiKeepsBothScriptLines() {
        w.loginCashier()
        val vm = EnrolViewModel(SavedStateHandle(), w.c, w.camera)
        rule.setContent { RemuTheme(forceLight = true) { CashierHomeScreen(vm, {}, {}, {}, {}) } }
        rule.onNodeWithText("मेंबर बनाएँ").assertIsDisplayed()
        rule.onNodeWithText("चेहरा छोड़ें, सिर्फ़ बिल सेव करें").assertIsDisplayed()
        rule.onNodeWithText("ग्राहक से यह कहें").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("आप अब हमारे मेंबर हो गए। अगली बार आते ही हम आपको पहचान के वेलकम करेंगे, और 5% ऑफ मिलेगा।").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("You're a member now. Next time, we'll recognise you as you walk in and welcome you, and you get 5% off.").performScrollTo().assertIsDisplayed()
    }

    @Test fun notificationIsBuiltLocallyWithAPrivateLockScreenVersion() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val n = AndroidAlertNotifier(app, FakeLanguageManager()) { false }
        n.notify(AlertItem(AlertPayload("v_1", "m_1", "Rajesh Sharma", "Sharma family", "2025-10-12", LastPurchase(20000.0, "Gold necklace"), "Kundan"), Instant.now(), AlertSource.FCM))
        val nm = app.getSystemService(NotificationManager::class.java)
        val posted = shadowOf(nm).allNotifications.single()
        assertEquals("Rajesh Sharma", posted.extras.getString("android.title"))
        assertTrue(posted.extras.getCharSequence("android.text").toString().startsWith("Sharma family · Last visit 12 Oct 2025 · ₹20,000, Gold necklace"))
        assertEquals("Member at the gate", posted.publicVersion.extras.getString("android.title"))
        assertEquals(android.app.Notification.VISIBILITY_PRIVATE, posted.visibility)
        assertEquals(AndroidAlertNotifier.CHANNEL_VIBRATE, posted.channelId)
        assertEquals(null, nm.getNotificationChannel(AndroidAlertNotifier.CHANNEL_VIBRATE).sound)
    }
}
