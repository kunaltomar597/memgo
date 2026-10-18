package com.remu.app.feature.members

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.remu.app.AppContainer
import com.remu.app.core.format.Validation
import com.remu.app.network.ApiResult
import com.remu.app.network.hub.HouseholdCreate
import com.remu.app.network.hub.HouseholdPatch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HouseholdMember(val id: String, val name: String)

data class HouseholdUiState(
    val loading: Boolean = true,
    val error: LoadError? = null,
    val householdId: String? = null,
    val label: String = "",
    val payerId: String? = null,
    val members: List<HouseholdMember> = emptyList(),
    val removeIds: Set<String> = emptySet(),
    val saving: Boolean = false,
    val saveError: SaveError? = null,
    val saved: Boolean = false,
    val labelTried: Boolean = false,
) {
    val visibleMembers: List<HouseholdMember> get() = members.filterNot { it.id in removeIds }
}

enum class SaveError { CONFLICT, HUB }

/**
 * Household editing from a member (TASK-050): rename, change the payer, remove a member, or add
 * an adult through a new enrolment at the counter. Saves with `PATCH /households/{id}`; a member
 * with no household gets one with `POST /households`. Names only; no photos exist.
 */
class HouseholdViewModel(private val c: AppContainer, private val memberId: String) : ViewModel() {
    private val _state = MutableStateFlow(HouseholdUiState())
    val state: StateFlow<HouseholdUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val r = c.hub.member(memberId)
            if (r !is ApiResult.Ok) { _state.update { it.copy(loading = false, error = r.loadError()) }; return@launch }
            val card = r.value
            val h = card.household
            if (h == null) {
                _state.update {
                    it.copy(loading = false, householdId = null, label = Validation.defaultHouseholdLabel(card.displayName),
                        payerId = card.id, members = listOf(HouseholdMember(card.id, card.displayName)))
                }
                return@launch
            }
            val names = h.memberIds.map { id ->
                async { if (id == card.id) HouseholdMember(id, card.displayName) else HouseholdMember(id, (c.hub.member(id) as? ApiResult.Ok)?.value?.displayName ?: id) }
            }.awaitAll()
            _state.update { it.copy(loading = false, householdId = h.id, label = h.label, payerId = h.payerMemberId, members = names) }
        }
    }

    fun onLabel(v: String) = _state.update { it.copy(label = v.take(Validation.MAX_LABEL), saved = false) }
    fun onPayer(id: String) = _state.update { it.copy(payerId = id, saved = false) }
    fun removeFromHousehold(id: String) = _state.update {
        it.copy(removeIds = it.removeIds + id, payerId = if (it.payerId == id) null else it.payerId, saved = false)
    }

    fun save() {
        val s = _state.value
        if (s.label.isBlank()) { _state.update { it.copy(labelTried = true) }; return }
        _state.update { it.copy(saving = true, saveError = null) }
        viewModelScope.launch {
            c.appState.touch()
            val r: ApiResult<*> = if (s.householdId == null) {
                c.hub.createHousehold(HouseholdCreate(s.label.trim(), s.members.map { it.id }, s.payerId))
            } else {
                c.hub.patchHousehold(
                    s.householdId,
                    HouseholdPatch(
                        label = s.label.trim(),
                        payerMemberId = s.payerId,
                        removeMemberIds = s.removeIds.toList().ifEmpty { null },
                    ),
                )
            }
            when {
                r is ApiResult.Ok -> {
                    val newId = (r.value as? com.remu.app.network.hub.IdResponse)?.id ?: s.householdId
                    _state.update { it.copy(saving = false, saved = true, householdId = newId) }
                    load()
                }
                r is ApiResult.HttpError && r.status == 409 -> _state.update { it.copy(saving = false, saveError = SaveError.CONFLICT) }
                else -> _state.update { it.copy(saving = false, saveError = SaveError.HUB) }
            }
        }
    }
}
