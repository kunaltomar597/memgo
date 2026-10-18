package com.remu.app.feature.owner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.remu.app.AppContainer
import com.remu.app.network.ApiResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SignInError { WRONG, UNREACHABLE, NOT_CONFIGURED }

/** Where the owner goes after signing in: the shop's home, or the setup wizard for a new shop. */
enum class SignInNext { HOME, SETUP }

data class OwnerSignInUiState(
    val email: String = "",
    val password: String = "",
    val working: Boolean = false,
    val error: SignInError? = null,
    val next: SignInNext? = null,
)

/** Owner sign-in with Supabase Auth email and password (TASK-075). */
class OwnerSignInViewModel(private val c: AppContainer, private val forSetup: Boolean) : ViewModel() {
    private val _state = MutableStateFlow(OwnerSignInUiState(email = c.session.current.ownerEmail.orEmpty()))
    val state: StateFlow<OwnerSignInUiState> = _state.asStateFlow()

    fun onEmail(v: String) = _state.update { it.copy(email = v.trim(), error = null) }
    fun onPassword(v: String) = _state.update { it.copy(password = v, error = null) }
    fun consumeNext() = _state.update { it.copy(next = null) }

    fun signIn() {
        val s = _state.value
        if (s.working || s.email.isBlank() || s.password.isEmpty()) return
        if (!c.owner.configured) { _state.update { it.copy(error = SignInError.NOT_CONFIGURED) }; return }
        _state.update { it.copy(working = true, error = null) }
        viewModelScope.launch {
            when (val r = c.owner.signIn(s.email, s.password)) {
                is ApiResult.Ok -> {
                    // A phone with a cloud session but no shop id yet looks it up (RLS scopes it).
                    if (c.session.current.shopId == null) {
                        (c.cloud.myShops() as? ApiResult.Ok)?.value?.firstOrNull()?.let { shop ->
                            c.session.update { it.copy(shopId = shop.id, shopName = shop.name, shopCity = shop.city) }
                        }
                    }
                    c.fcm.registerIfReady()
                    val sess = c.session.current
                    val next = if (forSetup || sess.shopId == null || sess.wizard?.completed == false) SignInNext.SETUP else SignInNext.HOME
                    _state.update { it.copy(working = false, password = "", next = next) }
                }
                is ApiResult.HttpError -> _state.update { it.copy(working = false, error = if (r.status in 400..499) SignInError.WRONG else SignInError.UNREACHABLE) }
                ApiResult.NotConfigured -> _state.update { it.copy(working = false, error = SignInError.NOT_CONFIGURED) }
                else -> _state.update { it.copy(working = false, error = SignInError.UNREACHABLE) }
            }
        }
    }
}
