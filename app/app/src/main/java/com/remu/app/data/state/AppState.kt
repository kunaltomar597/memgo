package com.remu.app.data.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class Mode { CASHIER, OWNER }

/**
 * Who unlocked the app with a PIN. Held in memory only; the PIN itself is never stored, and the
 * hub's `staff_session` ([sessionToken]) is never written to disk (docs/API_CONTRACT.md 7.1).
 */
data class StaffSession(val staffId: String, val name: String, val role: String, val mode: Mode, val sessionToken: String? = null) {
    override fun toString(): String = "StaffSession(staffId=$staffId, role=$role, mode=$mode)"
}

/** In-memory app state shared by screens. Lost on process death by design. */
class AppState(private val clock: () -> Long = System::currentTimeMillis) {
    private val _staff = MutableStateFlow<StaffSession?>(null)
    val staff: StateFlow<StaffSession?> = _staff

    @Volatile private var lastActiveMs: Long = clock()

    fun login(session: StaffSession) {
        _staff.value = session
        _sessionEnded.value = false
        touch()
    }

    fun lock() { _staff.value = null }

    private val _sessionEnded = MutableStateFlow(false)
    /** True after the hub refused the staff session (401 `staff_session_required`), until the next login. */
    val sessionEnded: StateFlow<Boolean> = _sessionEnded

    /** The hub no longer accepts this staff session: back to the PIN screen. */
    fun endSession() {
        if (_staff.value != null) _sessionEnded.value = true
        _staff.value = null
    }

    fun touch() { lastActiveMs = clock() }

    private val _hubIdentity = MutableStateFlow<HubIdentityProblem?>(null)
    /** Set when Owner mode unpaired this phone from a hub that is not the shop's; cleared on a good pairing. */
    val hubIdentity: StateFlow<HubIdentityProblem?> = _hubIdentity

    fun setHubIdentity(problem: HubIdentityProblem?) { _hubIdentity.value = problem }

    /** Owner mode asks for the PIN again after 15 minutes idle (docs/SECURITY.md 3.2). */
    fun lockIfIdle(limitMs: Long = OWNER_IDLE_MS) {
        val s = _staff.value ?: return
        if (s.mode == Mode.OWNER && clock() - lastActiveMs > limitMs) lock()
    }

    companion object { const val OWNER_IDLE_MS = 15 * 60 * 1000L }
}
