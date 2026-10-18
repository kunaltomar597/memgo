package com.remu.app.feature.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.remu.app.AppContainer
import com.remu.app.network.hub.HubDiscovery
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class PairKind { OWNER, COUNTER }

data class PairingUiState(
    val host: String = "",
    val code: String = "",
    val working: Boolean = false,
    val error: PairError? = null,
    val found: String? = null,
    val searching: Boolean = false,
    val scanning: Boolean = false,
    val done: Boolean = false,
    /** The certificate fingerprint from a scanned invite QR; the hub must present exactly this one. */
    val expectedFingerprint: String? = null,
)

/** The pairing screen (TASK-041): hub address (found by mDNS or typed) and the code or invite. */
class PairingViewModel(private val c: AppContainer, val kind: PairKind, discovery: HubDiscovery?) : ViewModel() {
    private val _state = MutableStateFlow(PairingUiState(host = hostOf(c.session.current.hubBaseUrl)))
    val state: StateFlow<PairingUiState> = _state.asStateFlow()

    init {
        if (discovery != null) {
            _state.update { it.copy(searching = true) }
            viewModelScope.launch {
                try {
                    discovery.find().take(1).collect { host ->
                        _state.update { s -> s.copy(found = host, searching = false, host = s.host.ifBlank { host }) }
                    }
                } catch (_: Exception) {
                    _state.update { it.copy(searching = false) }
                }
            }
        }
    }

    fun onHost(v: String) = _state.update { it.copy(host = v.trim(), error = null) }
    fun onCode(v: String) = _state.update { it.copy(code = v.trim(), error = null) }
    fun scan(on: Boolean) = _state.update { it.copy(scanning = on) }

    /** A scanned invite QR fills both fields and pairs at once. */
    fun onScanned(text: String) {
        val link = PairLink.parse(text) ?: return
        _state.update { it.copy(host = link.host, code = link.code, scanning = false, expectedFingerprint = link.fp) }
        pair()
    }

    fun pair() {
        val s = _state.value
        if (s.working) return
        _state.update { it.copy(working = true, error = null) }
        viewModelScope.launch {
            val err = HubPairing.pair(c, s.host, s.code, s.expectedFingerprint)
            _state.update { it.copy(working = false, error = err, done = err == null) }
        }
    }

    companion object {
        fun hostOf(baseUrl: String?): String {
            if (baseUrl.isNullOrBlank()) return ""
            return baseUrl.removePrefix("https://").removeSuffix("/").removeSuffix(":8443")
        }
    }
}
