package com.remu.app.feature.alerts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.remu.app.AppContainer
import com.remu.app.data.alerts.AlertItem
import com.remu.app.data.alerts.AlertStore
import com.remu.app.data.alerts.Feedback
import com.remu.app.data.state.HubStatus
import com.remu.app.network.ApiResult
import com.remu.app.network.cloud.CloudDevice
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant

data class OwnerHomeUiState(
    val alerts: List<AlertItem> = emptyList(),
    val today: AlertStore.Today = AlertStore.Today(0, 0),
    val hub: HubStatus = HubStatus(),
    val phoneOnline: Boolean = true,
    val device: CloudDevice? = null,
    val deviceError: Boolean = false,
    val sending: Set<String> = emptySet(),
    val failed: String? = null,
    val now: Instant = Instant.now(),
)

/** Owner mode home (TASK-075, TASK-077 to TASK-079): today's cards, counts, hub status. */
class OwnerHomeViewModel(private val c: AppContainer, private val tickMs: Long = 30_000) : ViewModel() {
    private val _state = MutableStateFlow(OwnerHomeUiState())
    val state: StateFlow<OwnerHomeUiState> = _state.asStateFlow()
    private val feedback = FeedbackSender(c)

    init {
        viewModelScope.launch {
            c.alerts.alerts.collect { list -> _state.update { it.copy(alerts = list, today = c.alerts.today()) } }
        }
        viewModelScope.launch { c.hubStatus.status.collect { h -> _state.update { it.copy(hub = h) } } }
        viewModelScope.launch { c.internet.online.collect { o -> _state.update { it.copy(phoneOnline = o) } } }
        viewModelScope.launch {
            while (isActive) {
                _state.update { it.copy(now = Instant.now(), today = c.alerts.today()) }
                delay(tickMs)
            }
        }
        refreshDevice()
    }

    /** Hub status as the cloud sees it: `GET /api/shops/{id}/devices`. */
    fun refreshDevice() {
        val shop = c.session.current.shopId ?: return
        viewModelScope.launch {
            when (val r = c.cloud.devices(shop)) {
                is ApiResult.Ok -> _state.update { it.copy(device = r.value.firstOrNull(), deviceError = false) }
                else -> _state.update { it.copy(deviceError = true) }
            }
        }
    }

    fun greeted(visitId: String) = send(visitId, Feedback.GREETED)
    fun notThem(visitId: String) = send(visitId, Feedback.NOT_THEM)

    private fun send(visitId: String, f: Feedback) {
        if (visitId in _state.value.sending) return
        c.appState.touch()
        _state.update { it.copy(sending = it.sending + visitId, failed = null) }
        viewModelScope.launch {
            val route = feedback.send(visitId, f)
            _state.update { it.copy(sending = it.sending - visitId, failed = if (route == null) visitId else null) }
        }
    }

    fun isStale(item: AlertItem): Boolean = c.alerts.isStale(item, _state.value.now)
}
