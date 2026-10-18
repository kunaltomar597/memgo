package com.remu.app.ui

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.remu.app.core.theme.RemuTheme
import com.remu.app.feature.enrol.CashierHomeScreen
import com.remu.app.feature.enrol.EnrolViewModel
import com.remu.app.feature.export.AndroidExportShare
import com.remu.app.feature.export.ExportFiles
import com.remu.app.feature.export.ExportedFile
import com.remu.app.feature.members.ClipState
import com.remu.app.feature.members.MemberDetailScreen
import com.remu.app.feature.members.MemberDetailViewModel
import com.remu.app.network.ApiResult
import com.remu.app.testsupport.PHONE
import com.remu.app.testsupport.TestWorld
import com.remu.app.testsupport.waitFor
import com.remu.app.testsupport.waitForTag
import com.remu.app.testsupport.waitForText
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant

/**
 * docs/API_CONTRACT.md 7.2 (clips and /ws for Owner mode only), FLAG_SECURE on the Cashier
 * camera and enrol screen, and consent export files deleted after sharing.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = PHONE)
class OwnerOnlyDataTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private var world: TestWorld? = null
    private fun world(w: TestWorld) = w.also { world = it }

    @After fun tearDown() { world?.close() }

    private fun visits() = """[{"id":"v_1","member_id":"m_1","ts":"${Instant.now().minusSeconds(3600)}","greeted":false,"not_them":false,"has_clip":true}]"""

    @Test fun cashierModeOnTheOwnerPhoneNeverOpensTheSocketOrAClip() {
        val w = world(TestWorld(role = "owner", ownerSignedIn = true).also { it.loginCashier() })
        w.c.startOwnerRealtime()
        Thread.sleep(300)
        assertEquals(0, w.c.events.connectionCount)
        assertEquals(ApiResult.NotConfigured, runBlocking { w.c.hub.clip("v_1") })
        assertTrue(w.hub.requests.none { it.path == "/ws" || it.path.startsWith("/clips") })
        assertFalse(w.c.ownerDataAllowed())
    }

    @Test fun switchingToCashierClosesTheSocket() {
        val w = world(TestWorld(role = "owner", ownerSignedIn = true).also { it.loginOwner() })
        assertTrue(w.c.ownerDataAllowed())
        w.c.startOwnerRealtime()
        w.loginCashier()
        rule.waitFor { !w.c.events.connected.value }
        Thread.sleep(300)
        val opened = w.c.events.connectionCount
        Thread.sleep(1_500)
        assertEquals("no reconnect in Cashier mode", opened, w.c.events.connectionCount)
    }

    @Test fun aCounterTokenNeverAsksForClips() {
        val w = world(TestWorld(role = "counter", ownerSignedIn = true).also { it.loginOwner() })
        assertFalse(w.c.ownerDataAllowed())
        w.hub.on("GET /members/m_1", body = MEMBER_CARD)
        w.hub.on("GET /visits", body = visits())
        val vm = MemberDetailViewModel(w.c, "m_1", ownerMode = true)
        rule.waitFor { vm.state.value.visits.isNotEmpty() }
        vm.playClip(vm.state.value.visits.first())
        rule.waitForIdle()
        assertTrue(w.hub.requests.none { it.path.startsWith("/clips") })
    }

    @Test fun clip403IsShownPlainly() {
        val w = world(TestWorld(role = "owner", ownerSignedIn = true).also { it.loginOwner() })
        w.hub.on("GET /members/m_1", body = MEMBER_CARD)
        w.hub.on("GET /staff", body = "[]")
        w.hub.on("GET /visits", body = visits())
        w.hub.on("GET /clips/v_1", 403, """{"error":"forbidden"}""")
        val vm = MemberDetailViewModel(w.c, "m_1", ownerMode = true)
        rule.setContent { RemuTheme { MemberDetailScreen(vm, true, {}, {}) } }
        rule.waitForTag("play_clip_v_1")
        rule.onNodeWithTag("play_clip_v_1").performScrollTo().performClick()
        rule.waitForTag("clip_forbidden_v_1")
        rule.waitForText("Clips play only in Owner mode on the owner's phone.")
        assertTrue(vm.state.value.clip is ClipState.Forbidden)
    }

    @Test fun ws403StopsRetrying() {
        val server = okhttp3.mockwebserver.MockWebServer()
        repeat(3) { server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(403).setBody("""{"error":"forbidden"}""")) }
        server.start()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        val rejected = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val client = com.remu.app.network.hub.HubClient(okhttp3.OkHttpClient(), { com.remu.app.network.hub.HubConfig(server.url("/").toString(), "tok", null) })
        val socket = com.remu.app.network.hub.HubEventSocket(client, scope, com.remu.app.network.hub.Backoff(10, 10), tokenProvider = { "tok" }, onRejected = { rejected += it })
        try {
            socket.start()
            rule.waitFor(5_000) { socket.forbidden }
            Thread.sleep(300)
            assertEquals(1, server.requestCount)
            assertEquals(listOf(403), rejected.toList())
        } finally {
            socket.stop(); scope.coroutineContext[kotlinx.coroutines.Job]?.cancel(); server.shutdown()
        }
    }

    // ---- FLAG_SECURE on the Cashier camera and enrol screen -----------------------------------

    @Test fun cashierCameraScreenIsSecure() {
        val w = world(TestWorld().also { it.loginCashier() })
        val vm = EnrolViewModel(SavedStateHandle(), w.c, w.camera, resetAfterMs = 60_000)
        rule.setContent { RemuTheme(forceLight = true) { CashierHomeScreen(vm, {}, {}, onOpenMember = {}, onPairAgain = {}) } }
        rule.waitForTag("make_member")
        val flags = rule.activity.window.attributes.flags
        assertTrue("FLAG_SECURE must be set", flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
    }

    // ---- Export files ---------------------------------------------------------------------------

    @Test fun exportFilesAreDeletedWhenTheShareSheetReturns() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val share = AndroidExportShare(ctx)
        var launched: android.content.Intent? = null
        share.launcher = { launched = it }
        share.share(listOf(ExportedFile("remu-consent-2026-10-18.csv", "text/csv", "id,shop_id\n".toByteArray())), "Share consent records")
        assertNotNull(launched)
        val dir = ExportFiles.dir(ctx)
        assertEquals(1, dir.listFiles()!!.size)
        share.onShareReturned()
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test fun exportFilesOlderThanAnHourAreDeletedOnStart() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dir = ExportFiles.dir(ctx).apply { mkdirs() }
        val now = System.currentTimeMillis()
        val old = File(dir, "old.pdf").apply { writeText("x"); setLastModified(now - 2 * 3600_000) }
        val fresh = File(dir, "fresh.pdf").apply { writeText("x"); setLastModified(now - 10 * 60_000) }
        assertEquals(1, ExportFiles.deleteOlderThan(dir, now))
        assertFalse(old.exists())
        assertTrue(fresh.exists())
        ExportFiles.clear(dir)
    }
}
