package com.remu.app.feature.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.remu.app.AppContainer
import com.remu.app.data.state.Mode
import com.remu.app.data.state.StaffSession
import com.remu.app.network.ApiResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LoginError { WRONG_PIN, HUB_UNREACHABLE, CERT_CHANGED, NEEDS_OWNER_PIN, NEEDS_OWNER_PHONE, SESSION_ENDED, HUB_LOCKED, OTHER }

/** Where to go once the PIN is accepted. */
enum class LoginDestination { CASHIER, OWNER_HOME, OWNER_SIGN_IN, SETUP }

data class LoginUiState(
    val mode: Mode = Mode.CASHIER,
    val pin: String = "",
    val working: Boolean = false,
    val error: LoginError? = null,
    val waitSeconds: Int = 0,
    val destination: LoginDestination? = null,
    val canContinueOffline: Boolean = false,
)

/**
 * Staff PIN login and the mode choice (TASK-040). The PIN goes to `POST /staff/verify-pin` and is
 * never stored; only the in-memory staff session is kept. Wrong tries add a growing wait.
 */
class LoginViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(
        LoginUiState(
            mode = if (c.session.current.lastMode == Mode.OWNER.name) Mode.OWNER else Mode.CASHIER,
            error = if (c.appState.sessionEnded.value) LoginError.SESSION_ENDED else null,
        ),
    )
    val state: StateFlow<LoginUiState> = _state.asStateFlow()
    private var wrongTries = 0
    private var countdown: Job? = null

    fun setMode(mode: Mode) = _state.update { it.copy(mode = mode, error = null) }

    fun digit(d: Char) {
        if (!d.isDigit()) return
        _state.update { if (it.pin.length >= 6) it else it.copy(pin = it.pin + d, error = null) }
    }

    fun delete() = _state.update { it.copy(pin = it.pin.dropLast(1)) }

    fun consumeDestination() = _state.update { it.copy(destination = null) }

    fun submit() {
        val s = _state.value
        if (s.working || s.waitSeconds > 0 || s.pin.length < 4) return
        _state.update { it.copy(working = true, error = null) }
        viewModelScope.launch {
            val r = c.hub.verifyPin(s.pin)
            when (r) {
                is ApiResult.Ok -> {
                    wrongTries = 0
                    val staff = r.value
                    if (s.mode == Mode.OWNER) {
                        if (staff.role != "owner") return@launch fail(LoginError.NEEDS_OWNER_PIN)
                        if (c.session.current.hubRole != "owner") return@launch fail(LoginError.NEEDS_OWNER_PHONE)
                    }
                    // The staff session stays in memory only; it is never written to the DataStore.
                    c.appState.login(StaffSession(staff.staffId, staff.name, staff.role, s.mode, staff.staffSession))
                    val sess = c.session.update {
                        if (s.mode == Mode.OWNER) it.copy(lastMode = s.mode.name, ownerStaffId = staff.staffId, ownerStaffName = staff.name)
                        else it.copy(lastMode = s.mode.name)
                    }
                    _state.update { it.copy(working = false, pin = "", destination = destinationFor(s.mode, sess)) }
                }
                is ApiResult.HttpError -> when (r.status) {
                    401 -> { wrongTries++; fail(LoginError.WRONG_PIN); startWaitIfNeeded() }
                    429 -> { fail(LoginError.WRONG_PIN); startWait(60) }
                    423 -> fail(LoginError.HUB_LOCKED)
                    else -> fail(LoginError.OTHER)
                }
                ApiResult.CertificateChanged -> fail(LoginError.CERT_CHANGED)
                else -> {
                    c.hubStatus.reportFailure()
                    val sess = c.session.current
                    _state.update {
                        it.copy(
                            working = false, pin = "", error = LoginError.HUB_UNREACHABLE,
                            canContinueOffline = s.mode == Mode.OWNER && sess.ownerSignedIn && sess.ownerStaffId != null && sess.hubRole == "owner",
                        )
                    }
                }
            }
        }
    }

    /**
     * Owner mode away from the shop WiFi: the hub cannot check a PIN, so the owner's cloud
     * sign-in (password-protected, on this owner-paired phone) admits them with the last owner
     * staff id for `greeted_by`. Cashier mode never has this path.
     */
    fun continueOffline() {
        val sess = c.session.current
        val id = sess.ownerStaffId ?: return
        if (!sess.ownerSignedIn || sess.hubRole != "owner") return
        c.appState.login(StaffSession(id, sess.ownerStaffName.orEmpty(), "owner", Mode.OWNER))
        _state.update { it.copy(destination = destinationFor(Mode.OWNER, sess)) }
    }

    private fun destinationFor(mode: Mode, sess: com.remu.app.data.session.StoredSession): LoginDestination = when {
        mode == Mode.CASHIER -> LoginDestination.CASHIER
        sess.wizard?.let { !it.completed } == true -> LoginDestination.SETUP
        !sess.ownerSignedIn -> LoginDestination.OWNER_SIGN_IN
        else -> LoginDestination.OWNER_HOME
    }

    private fun fail(e: LoginError) = _state.update { it.copy(working = false, pin = "", error = e) }

    private fun startWaitIfNeeded() {
        if (wrongTries >= 3) startWait((5 shl (wrongTries - 3).coerceAtMost(4)).coerceAtMost(60))
    }

    private fun startWait(seconds: Int) {
        countdown?.cancel()
        countdown = viewModelScope.launch {
            for (left in seconds downTo 1) {
                _state.update { it.copy(waitSeconds = left) }
                delay(1_000)
            }
            _state.update { it.copy(waitSeconds = 0) }
        }
    }
}
