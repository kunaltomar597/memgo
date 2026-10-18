package com.remu.app

import android.content.Context
import com.remu.app.core.locale.LanguageManager
import com.remu.app.data.alerts.AlertIngest
import com.remu.app.data.alerts.AlertNotifier
import com.remu.app.data.alerts.AlertStore
import com.remu.app.data.session.OwnerSessionManager
import com.remu.app.data.session.SessionRepository
import com.remu.app.data.session.SessionStore
import com.remu.app.data.state.AppState
import com.remu.app.data.state.HubStatusMonitor
import com.remu.app.data.state.InternetState
import com.remu.app.data.state.Mode
import com.remu.app.feature.enrol.CounterCameraFactory
import com.remu.app.feature.export.ExportShare
import com.remu.app.network.cloud.CloudClient
import com.remu.app.network.cloud.OwnerAuth
import com.remu.app.network.cloud.SupabaseOwnerAuth
import com.remu.app.network.fcm.FcmRegistrar
import com.remu.app.network.hub.HubClient
import com.remu.app.network.hub.HubConfig
import com.remu.app.network.hub.HubEventSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Hand-wired dependencies. One instance per process in the app; tests build their own with
 * fakes (an in-memory session store, a fake owner auth, a fake camera, MockWebServer URLs).
 */
class AppContainer(
    val appContext: Context,
    val scope: CoroutineScope,
    sessionStore: SessionStore,
    cloudBaseUrl: String,
    supabaseUrl: String,
    supabaseAnonKey: String,
    val internet: InternetState,
    val cameraFactory: CounterCameraFactory,
    notifier: AlertNotifier,
    ownerAuth: OwnerAuth? = null,
    hubOkIntervalMs: Long = 30_000,
    hubDownIntervalMs: Long = 10_000,
    val language: LanguageManager = LanguageManager(),
    val exportShare: ExportShare? = null,
    val firebaseEnabled: Boolean = false,
) {
    /** No cache: request and response bodies (face crops, clips) never touch the disk. */
    val http: OkHttpClient = OkHttpClient.Builder()
        .cache(null)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    val session = SessionRepository(sessionStore, scope)
    val appState = AppState()

    val hub = HubClient(
        http,
        config = {
            val s = session.current
            s.hubBaseUrl?.let { HubConfig(it, s.hubToken, s.certSha256) }
        },
        staffSession = { appState.staff.value?.sessionToken },
        ownerDataAllowed = { ownerDataAllowed() },
        onAnswer = { path, status, error -> onHubAnswer(path, status, error) },
    )

    val ownerAuth: OwnerAuth = ownerAuth ?: SupabaseOwnerAuth(http, supabaseUrl, supabaseAnonKey)
    val owner = OwnerSessionManager(this.ownerAuth, session)
    val cloud = CloudClient(http, cloudBaseUrl, { owner.accessToken() }, supabaseUrl, supabaseAnonKey)

    val hubStatus = HubStatusMonitor(hub, scope, hubOkIntervalMs, hubDownIntervalMs)
    val alerts = AlertStore()
    val events = HubEventSocket(hub, scope, tokenProvider = { session.current.hubToken }, onRejected = { code ->
        if (code == 423) hubStatus.setLockedOut(true)
    })
    val ingest = AlertIngest(alerts, hub, cloud, { hubStatus.status.value }, { session.current.shopId }, notifier)
    val fcm = FcmRegistrar(this)

    init {
        scope.launch { events.events.collect { ingest.onSocketEvent(it) } }
        // Cashier mode never keeps /ws open, even on the owner's phone (docs/API_CONTRACT.md 7.2).
        scope.launch { appState.staff.collect { if (it?.mode != Mode.OWNER) events.stop() } }
        // A new pairing or a cleared one changes what the monitor talks to: check at once.
        scope.launch {
            session.state.map { it.hubBaseUrl to it.hubToken }.distinctUntilChanged().collect { hubStatus.check() }
        }
    }

    /** Owner mode on the shop WiFi keeps the WebSocket open; anything else closes it. */
    fun startOwnerRealtime() {
        if (ownerDataAllowed()) events.start()
        fcm.registerIfReady()
    }

    /** Clips and `/ws` are for Owner mode on the owner-paired phone only (docs/API_CONTRACT.md 7.2). */
    fun ownerDataAllowed(): Boolean = appState.staff.value?.mode == Mode.OWNER && session.current.hubRole == "owner"

    /** Every hub reply: 423 locks hub actions, `staff_session_required` returns to the PIN screen. */
    private fun onHubAnswer(path: String, status: Int, error: String?) {
        when {
            status == 423 -> hubStatus.setLockedOut(true)
            path != "health" -> hubStatus.setLockedOut(false)
        }
        if (status == 401 && error == HubClient.ERROR_STAFF_SESSION_REQUIRED) appState.endSession()
    }

    fun stopOwnerRealtime() { events.stop() }
}
