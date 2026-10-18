package com.remu.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.remu.app.AppContainer
import com.remu.app.data.state.HubIdentityProblem
import com.remu.app.data.state.HubStatus
import com.remu.app.feature.pairing.HubIdentity
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
    val identity: HubIdentityProblem? = null,
    /** The owner's cloud shop id, to compare with `cloud_shop_id` in `GET /health` (contract 7.6). */
    val shopId: String? = null,
) {
    val otherShop: Boolean get() = HubIdentity.claimedByOtherShop(hub.health, shopId)
}

/** Device status (TASK-082): LAN health from the hub, last seen and version from the cloud. */
class DeviceStatusViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(DeviceUiState())
    val state: StateFlow<DeviceUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { c.hubStatus.status.collect { h -> _state.update { it.copy(hub = h) } } }
        viewModelScope.launch { c.appState.hubIdentity.collect { p -> _state.update { it.copy(identity = p) } } }
        viewModelScope.launch { c.session.state.collect { s -> _state.update { it.copy(shopId = s.shopId) } } }
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            c.hubStatus.check()
            val shop = c.session.current.shopId
            val r = if (shop != null) c.cloud.devices(shop) else ApiResult.NotConfigured
            if (r is ApiResult.Ok) HubIdentity.checkDevices(c, r.value)
            _state.update {
                if (r is ApiResult.Ok) it.copy(refreshing = false, device = r.value.firstOrNull(), cloudFailed = false)
                else it.copy(refreshing = false, cloudFailed = true)
            }
        }
    }
}
