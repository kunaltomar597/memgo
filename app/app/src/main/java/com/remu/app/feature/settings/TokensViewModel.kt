package com.remu.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.remu.app.AppContainer
import com.remu.app.data.state.HubStatus
import com.remu.app.feature.members.LoadError
import com.remu.app.feature.members.loadError
import com.remu.app.network.ApiResult
import com.remu.app.network.hub.HubToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.security.MessageDigest

data class TokensUiState(
    val loading: Boolean = true,
    val error: LoadError? = null,
    val tokens: List<HubToken> = emptyList(),
    val hub: HubStatus = HubStatus(),
    /** The token row this phone holds, so it is labelled and not offered for revoking. */
    val thisPhoneId: String? = null,
    val confirm: HubToken? = null,
    val revoking: Boolean = false,
    val revokeFailed: Boolean = false,
    val revoked: Boolean = false,
)

/**
 * "Paired phones" in Owner settings (docs/API_CONTRACT.md 7.3): `GET /tokens` lists every app
 * token of the hub, and Revoke calls `POST /tokens/{id}/revoke` after a confirmation. Needs the
 * shop WiFi; the hub answers owner tokens only.
 */
class TokensViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(TokensUiState(thisPhoneId = c.session.current.hubToken?.let(::tokenId)))
    val state: StateFlow<TokensUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { c.hubStatus.status.collect { h -> _state.update { it.copy(hub = h) } } }
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val r = c.hub.tokens()) {
                is ApiResult.Ok -> _state.update { it.copy(loading = false, tokens = r.value) }
                else -> _state.update { it.copy(loading = false, error = r.loadError()) }
            }
        }
    }

    fun askRevoke(token: HubToken) = _state.update { it.copy(confirm = token, revokeFailed = false, revoked = false) }
    fun cancelRevoke() = _state.update { it.copy(confirm = null, revokeFailed = false) }

    fun revoke() {
        val t = _state.value.confirm ?: return
        if (_state.value.revoking) return
        _state.update { it.copy(revoking = true, revokeFailed = false) }
        viewModelScope.launch {
            c.appState.touch()
            when (c.hub.revokeToken(t.id)) {
                is ApiResult.Ok -> {
                    _state.update { it.copy(revoking = false, confirm = null, revoked = true) }
                    load()
                }
                else -> _state.update { it.copy(revoking = false, revokeFailed = true) }
            }
        }
    }

    companion object {
        /** The hub's token id: the first 12 hex characters of the SHA-256 of the token. */
        fun tokenId(token: String): String =
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }.take(12)
    }
}
