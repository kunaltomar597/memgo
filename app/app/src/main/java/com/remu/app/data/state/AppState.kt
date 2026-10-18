package com.remu.app.data.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class Mode { CASHIER, OWNER }

/** Who unlocked the app with a PIN. Held in memory only; the PIN itself is never stored. */
data class StaffSession(val staffId: String, val name: String, val role: String, val mode: Mode)

/** In-memory app state shared by screens. Lost on process death by design. */
class AppState(private val clock: () -> Long = System::currentTimeMillis) {
    private val _staff = MutableStateFlow<StaffSession?>(null)
    val staff: StateFlow<StaffSession?> = _staff

    @Volatile private var lastActiveMs: Long = clock()

    fun login(session: StaffSession) {
        _staff.value = session
        touch()
    }

    fun lock() { _staff.value = null }

    fun touch() { lastActiveMs = clock() }

    /** Owner mode asks for the PIN again after 15 minutes idle (docs/SECURITY.md 3.2). */
    fun lockIfIdle(limitMs: Long = OWNER_IDLE_MS) {
        val s = _staff.value ?: return
        if (s.mode == Mode.OWNER && clock() - lastActiveMs > limitMs) lock()
    }

    companion object { const val OWNER_IDLE_MS = 15 * 60 * 1000L }
}
