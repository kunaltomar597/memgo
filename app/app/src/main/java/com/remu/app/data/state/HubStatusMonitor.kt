package com.remu.app.data.state

import com.remu.app.network.ApiResult
import com.remu.app.network.hub.HubClient
import com.remu.app.network.hub.HubHealth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What the app knows about the hub on the LAN. `reachable == null` means not checked yet. */
data class HubStatus(
    val reachable: Boolean? = null,
    val health: HubHealth? = null,
    val certificateChanged: Boolean = false,
    /**
     * A hub route answered 423 `locked`: the hub is revoked or its data directory is locked, so
     * every route but `GET /health` refuses (docs/API_CONTRACT.md 7.7). Hub actions are disabled.
     */
    val lockedOut: Boolean = false,
) {
    /**
     * The hub answered `GET /health` recently and takes requests: the phone is on the shop WiFi.
     * False while [lockedOut], so every LAN-only action is disabled and Owner mode uses the cloud.
     */
    val onLan: Boolean get() = reachable == true && !lockedOut
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

    /**
     * One check now; returns true when the hub answered. While [HubStatus.lockedOut], `GET /health`
     * still answers, so one authenticated read probes whether the lock has lifted.
     */
    suspend fun check(): Boolean {
        if (!hub.isConfigured()) {
            _status.value = HubStatus(reachable = false)
            return false
        }
        val wasLocked = _status.value.lockedOut
        return when (val r = hub.health()) {
            is ApiResult.Ok -> {
                _status.update { HubStatus(true, r.value, lockedOut = it.lockedOut) }
                if (wasLocked) hub.listStaff() // the reply flips lockedOut through setLockedOut
                true
            }
            ApiResult.CertificateChanged -> { _status.value = HubStatus(false, null, certificateChanged = true); false }
            else -> { _status.update { HubStatus(false, it.health, lockedOut = it.lockedOut) }; false }
        }
    }

    /** A hub route answered 423 `locked` (true) or answered anything else (false). */
    fun setLockedOut(locked: Boolean) {
        if (_status.value.lockedOut != locked) _status.update { it.copy(lockedOut = locked) }
    }

    /** A failed request elsewhere is a hint the hub went away; re-check soon. */
    fun reportFailure() { scope.launch { check() } }
}
