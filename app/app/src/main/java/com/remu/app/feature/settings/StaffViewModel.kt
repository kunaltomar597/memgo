package com.remu.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.remu.app.AppContainer
import com.remu.app.core.format.Validation
import com.remu.app.data.state.HubStatus
import com.remu.app.feature.members.LoadError
import com.remu.app.feature.members.loadError
import com.remu.app.network.ApiResult
import com.remu.app.network.hub.Staff
import com.remu.app.network.hub.StaffCreate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StaffDraft(val name: String = "", val role: String = "cashier", val pin: String = "", val pin2: String = "")

enum class StaffFormError { NAME, PIN_INVALID, PIN_MISMATCH }

data class StaffUiState(
    val loading: Boolean = true,
    val error: LoadError? = null,
    val staff: List<Staff> = emptyList(),
    val hub: HubStatus = HubStatus(),
    val adding: Boolean = false,
    val draft: StaffDraft = StaffDraft(),
    val formError: StaffFormError? = null,
    val saving: Boolean = false,
    val saveFailed: Boolean = false,
    val added: Boolean = false,
)

/**
 * Staff list and PIN creation (TASK-082). The hub authors staff rows: `POST /staff` stores only
 * `pin_hash` and mirrors the row to the cloud as `staff_upsert`. Needs the shop WiFi.
 */
class StaffViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(StaffUiState())
    val state: StateFlow<StaffUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { c.hubStatus.status.collect { h -> _state.update { it.copy(hub = h) } } }
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val r = c.hub.listStaff()) {
                is ApiResult.Ok -> _state.update { it.copy(loading = false, staff = r.value) }
                else -> _state.update { it.copy(loading = false, error = r.loadError()) }
            }
        }
    }

    fun startAdd() = _state.update { it.copy(adding = true, draft = StaffDraft(), formError = null, added = false, saveFailed = false) }
    fun cancelAdd() = _state.update { it.copy(adding = false) }
    fun onDraft(d: StaffDraft) = _state.update { it.copy(draft = d.copy(pin = d.pin.filter(Char::isDigit).take(6), pin2 = d.pin2.filter(Char::isDigit).take(6)), formError = null) }

    fun save() {
        val d = _state.value.draft
        val err = when {
            d.name.isBlank() || d.name.length > Validation.MAX_NAME -> StaffFormError.NAME
            !Validation.isValidPin(d.pin) -> StaffFormError.PIN_INVALID
            d.pin != d.pin2 -> StaffFormError.PIN_MISMATCH
            else -> null
        }
        if (err != null) { _state.update { it.copy(formError = err) }; return }
        if (!_state.value.hub.onLan) return
        _state.update { it.copy(saving = true, saveFailed = false) }
        viewModelScope.launch {
            c.appState.touch()
            val r = c.hub.createStaff(StaffCreate(d.name.trim(), d.role, d.pin))
            if (r is ApiResult.Ok) {
                _state.update { it.copy(saving = false, adding = false, added = true, draft = StaffDraft()) }
                load()
            } else {
                _state.update { it.copy(saving = false, saveFailed = true) }
            }
        }
    }
}
