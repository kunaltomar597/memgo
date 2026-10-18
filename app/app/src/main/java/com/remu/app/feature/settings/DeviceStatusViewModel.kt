package com.remu.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.remu.app.AppContainer
import com.remu.app.data.state.HubStatus
import com.remu.app.network.ApiResult
import com.remu.app.network.cloud.CloudDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DeviceUiState(
    val hub: HubStatus = HubStatus(),
    val device: CloudDevice? = null,
    val cloudFailed: Boolean = false,
    val refreshing: Boolean = false,
)

/** Device status (TASK-082): LAN health from the hub, last seen and version from the cloud. */
class DeviceStatusViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(DeviceUiState())
    val state: StateFlow<DeviceUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { c.hubStatus.status.collect { h -> _state.update { it.copy(hub = h) } } }
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            c.hubStatus.check()
            val shop = c.session.current.shopId
            val r = if (shop != null) c.cloud.devices(shop) else ApiResult.NotConfigured
            _state.update {
                if (r is ApiResult.Ok) it.copy(refreshing = false, device = r.value.firstOrNull(), cloudFailed = false)
                else it.copy(refreshing = false, cloudFailed = true)
            }
        }
    }
}
