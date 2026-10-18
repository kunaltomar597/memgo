package com.remu.app.testsupport

import androidx.test.core.app.ApplicationProvider
import com.remu.app.AppContainer
import com.remu.app.data.session.InMemorySessionStore
import com.remu.app.data.session.StoredSession
import com.remu.app.data.state.FixedInternetState
import com.remu.app.data.state.Mode
import com.remu.app.data.state.StaffSession
import com.remu.app.feature.enrol.CounterCameraFactory
import com.remu.app.feature.export.ExportShare
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking

/** Robolectric qualifiers for a common 411dp by 891dp phone (docs/DESIGN.md section 9). */
const val PHONE = "w411dp-h891dp-port-xxhdpi"

/** One test's world: a mock hub, a mock cloud, fakes, and an AppContainer wired to them. */
class TestWorld(
    paired: Boolean = true,
    role: String = "counter",
    ownerSignedIn: Boolean = false,
    session: StoredSession? = null,
    val camera: FakeCamera = FakeCamera(),
    val auth: FakeOwnerAuth = FakeOwnerAuth(),
    val language: FakeLanguageManager = FakeLanguageManager(),
    exportShare: ExportShare? = null,
) {
    val hub = MockServer()
    val cloud = MockServer()
    val notifier = RecordingNotifier()
    val internet = FixedInternetState(true)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init { hub.on("GET /health", body = MockServer.HEALTH_OK) }

    val initial: StoredSession = session ?: StoredSession(
        hubBaseUrl = if (paired) hub.url() else null,
        hubToken = if (paired) "app-token-1" else null,
        certSha256 = if (paired) "AB:CD" else null,
        hubRole = if (paired) role else null,
        shopId = "sharma-jewellers-jaipur",
        shopName = "Sharma Jewellers",
        shopCity = "Jaipur",
        ownerAccessToken = if (ownerSignedIn) "access-0" else null,
        ownerRefreshToken = if (ownerSignedIn) "refresh-0" else null,
        ownerExpiresAtEpochS = if (ownerSignedIn) System.currentTimeMillis() / 1000 + 3600 else null,
        ownerStaffId = if (role == "owner") "s_owner" else null,
        ownerStaffName = if (role == "owner") "Rajesh" else null,
    )

    val c = AppContainer(
        appContext = ApplicationProvider.getApplicationContext(),
        scope = scope,
        sessionStore = InMemorySessionStore(initial),
        cloudBaseUrl = cloud.url(),
        supabaseUrl = cloud.url(),
        supabaseAnonKey = "anon",
        internet = internet,
        cameraFactory = CounterCameraFactory { _, _ -> camera },
        notifier = notifier,
        ownerAuth = auth,
        language = language,
        exportShare = exportShare,
    )

    init { runBlocking { c.session.awaitLoaded(); c.hubStatus.check() } }

    fun loginCashier() = c.appState.login(StaffSession("s_ravi", "Ravi", "cashier", Mode.CASHIER))
    fun loginOwner() = c.appState.login(StaffSession("s_owner", "Rajesh", "owner", Mode.OWNER))

    fun hubDown() { hub.down = true; runBlocking { c.hubStatus.check() } }
    fun hubUp() { hub.down = false; runBlocking { c.hubStatus.check() } }

    fun close() {
        c.events.stop()
        hub.shutdown()
        cloud.shutdown()
    }
}

/** waitUntil that idles Robolectric's main looper on each check, so coroutine resumes run. */
fun androidx.compose.ui.test.junit4.ComposeContentTestRule.waitFor(timeoutMs: Long = 15_000, condition: () -> Boolean) =
    waitUntil(timeoutMs) { waitForIdle(); condition() }

/** Waits until a node with this tag exists. */
fun androidx.compose.ui.test.junit4.ComposeContentTestRule.waitForTag(tag: String, timeoutMs: Long = 15_000) =
    waitUntil(timeoutMs) { onAllNodes(androidx.compose.ui.test.hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

/** Waits until a node with exactly this text exists. */
fun androidx.compose.ui.test.junit4.ComposeContentTestRule.waitForText(text: String, timeoutMs: Long = 15_000) =
    waitUntil(timeoutMs) { onAllNodes(androidx.compose.ui.test.hasText(text), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
