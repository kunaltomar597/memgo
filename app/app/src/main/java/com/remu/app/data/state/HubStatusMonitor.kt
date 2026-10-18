package com.remu.app.data.state

import com.remu.app.network.ApiResult
import com.remu.app.network.hub.HubClient
import com.remu.app.network.hub.HubHealth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What the app knows about the hub on the LAN. `reachable == null` means not checked yet. */
data class HubStatus(
    val reachable: Boolean? = null,
    val health: HubHealth? = null,
    val certificateChanged: Boolean = false,
) {
    /** The hub answered `GET /health` recently: the phone is on the shop WiFi. */
    val onLan: Boolean get() = reachable == true
    val internetDown: Boolean get() = reachable == true && health?.internet == false
    val locked: Boolean get() = reachable == true && health?.locked == true
}

/**
 * Polls `GET /health` every 30 seconds (TASK-041), every 10 seconds while the hub is down so the
 * "Hub not reachable" banner clears within 30 seconds of the hub coming back (TASK-051).
 */
class HubStatusMonitor(
    private val hub: HubClient,
    private val scope: CoroutineScope,
    private val okIntervalMs: Long = 30_000,
    private val downIntervalMs: Long = 10_000,
) {
    private val _status = MutableStateFlow(HubStatus())
    val status: StateFlow<HubStatus> = _status
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                val ok = check()
                delay(if (ok) okIntervalMs else downIntervalMs)
            }
        }
    }

    fun stop() { job?.cancel(); job = null }

    /** One check now; returns true when the hub answered. */
    suspend fun check(): Boolean {
        if (!hub.isConfigured()) {
            _status.value = HubStatus(reachable = false)
            return false
        }
        return when (val r = hub.health()) {
            is ApiResult.Ok -> { _status.value = HubStatus(true, r.value); true }
            ApiResult.CertificateChanged -> { _status.value = HubStatus(false, null, certificateChanged = true); false }
            else -> { _status.value = HubStatus(false, _status.value.health); false }
        }
    }

    /** A failed request elsewhere is a hint the hub went away; re-check soon. */
    fun reportFailure() { scope.launch { check() } }
}
