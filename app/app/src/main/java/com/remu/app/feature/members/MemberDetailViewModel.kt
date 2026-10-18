package com.remu.app.feature.members

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.remu.app.AppContainer
import com.remu.app.core.format.Formatters
import com.remu.app.data.state.HubStatus
import com.remu.app.network.ApiResult
import com.remu.app.network.hub.MemberCard
import com.remu.app.network.hub.MemberPatch
import com.remu.app.network.hub.Purchase
import com.remu.app.network.hub.Visit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

/** One row of the member timeline: visits and purchases mixed, newest first (docs/DESIGN.md 5). */
sealed interface TimelineEntry {
    val at: Instant
    data class VisitEntry(val visit: Visit, override val at: Instant) : TimelineEntry
    data class PurchaseEntry(val purchase: Purchase, override val at: Instant) : TimelineEntry

    companion object {
        fun merge(visits: List<Visit>, purchases: List<Purchase>): List<TimelineEntry> =
            (visits.map { VisitEntry(it, Formatters.parseInstant(it.ts) ?: Instant.EPOCH) } +
                purchases.map { PurchaseEntry(it, Formatters.parseInstant(it.ts) ?: Instant.EPOCH) })
                .sortedByDescending { it.at }
    }
}

sealed interface ClipState {
    data object Idle : ClipState
    data class Loading(val visitId: String) : ClipState
    /** Clip bytes held in memory for the player; never written to storage or shared. */
    class Ready(val visitId: String, val bytes: ByteArray) : ClipState
    data class Failed(val visitId: String) : ClipState
}

data class MemberDetailUiState(
    val loading: Boolean = true,
    val error: LoadError? = null,
    val card: MemberCard? = null,
    val editingNotes: Boolean = false,
    val notesDraft: String = "",
    val savingNotes: Boolean = false,
    val notesError: Boolean = false,
    val confirmRemove: Boolean = false,
    val removing: Boolean = false,
    val removeError: Boolean = false,
    val removed: Boolean = false,
    val visits: List<Visit> = emptyList(),
    val visitsError: Boolean = false,
    val clip: ClipState = ClipState.Idle,
    val hub: HubStatus = HubStatus(),
    val message: Message? = null,
    val billOpen: Boolean = false,
    val bill: BillDraft = BillDraft(),
    val billTried: Boolean = false,
    val billSending: Boolean = false,
    val billError: Boolean = false,
    val staffNames: Map<String, String> = emptyMap(),
) {
    val timeline: List<TimelineEntry> get() = TimelineEntry.merge(visits, card?.purchases.orEmpty())
}

/**
 * Member detail for both modes (TASK-049, TASK-080, TASK-081, TASK-087): card, notes, purchases,
 * household, Remove member, and in Owner mode the timeline with clips over the LAN only.
 */
class MemberDetailViewModel(private val c: AppContainer, val memberId: String, val ownerMode: Boolean) : ViewModel() {
    private val _state = MutableStateFlow(MemberDetailUiState())
    val state: StateFlow<MemberDetailUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { c.hubStatus.status.collect { h -> _state.update { it.copy(hub = h) } } }
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val r = c.hub.member(memberId)
            when (r) {
                is ApiResult.Ok -> _state.update { it.copy(loading = false, card = r.value, notesDraft = r.value.notes.orEmpty()) }
                else -> {
                    if (r is ApiResult.Unreachable) c.hubStatus.reportFailure()
                    _state.update { it.copy(loading = false, error = r.loadError()) }
                }
            }
            if (ownerMode && r is ApiResult.Ok) loadVisits()
        }
    }

    private suspend fun loadVisits() {
        when (val v = c.hub.visits(memberId)) {
            is ApiResult.Ok -> _state.update { it.copy(visits = v.value, visitsError = false) }
            else -> _state.update { it.copy(visitsError = true) }
        }
        val staff = c.hub.listStaff()
        if (staff is ApiResult.Ok) _state.update { s -> s.copy(staffNames = staff.value.associate { it.id to it.name }) }
    }

    // ---- Notes -----------------------------------------------------------------------------

    fun editNotes() = _state.update { it.copy(editingNotes = true, notesDraft = it.card?.notes.orEmpty(), notesError = false) }
    fun onNotes(text: String) = _state.update { it.copy(notesDraft = text.take(com.remu.app.core.format.Validation.MAX_NOTES)) }
    fun cancelNotes() = _state.update { it.copy(editingNotes = false) }

    /** PATCH /members/{id} with notes only; the hub mirrors it to the cloud. */
    fun saveNotes() {
        val draft = _state.value.notesDraft.trim()
        _state.update { it.copy(savingNotes = true, notesError = false) }
        viewModelScope.launch {
            c.appState.touch()
            when (c.hub.patchMember(memberId, MemberPatch(notes = draft))) {
                is ApiResult.Ok -> _state.update {
                    it.copy(savingNotes = false, editingNotes = false, card = it.card?.copy(notes = draft), message = Message.NOTES_SAVED)
                }
                else -> _state.update { it.copy(savingNotes = false, notesError = true) }
            }
        }
    }

    // ---- Bill ------------------------------------------------------------------------------

    fun openBill() = _state.update { it.copy(billOpen = true, bill = BillDraft(), billTried = false, billError = false) }
    fun onBill(d: BillDraft) = _state.update { it.copy(bill = d) }
    fun closeBill() = _state.update { it.copy(billOpen = false) }

    fun sendBill() {
        val s = _state.value
        if (!s.bill.billNoOk || !s.bill.amountOk) { _state.update { it.copy(billTried = true) }; return }
        val staff = c.appState.staff.value ?: return
        _state.update { it.copy(billSending = true, billError = false) }
        viewModelScope.launch {
            when (val r = c.hub.addPurchase(memberId, s.bill.toRequest(staff.staffId))) {
                is ApiResult.Ok -> _state.update {
                    it.copy(billSending = false, billOpen = false, message = Message.BILL_ADDED,
                        card = it.card?.copy(purchases = listOf(r.value) + it.card.purchases))
                }
                else -> _state.update { it.copy(billSending = false, billError = true) }
            }
        }
    }

    // ---- Remove ----------------------------------------------------------------------------

    fun askRemove() = _state.update { it.copy(confirmRemove = true, removeError = false) }
    fun cancelRemove() = _state.update { it.copy(confirmRemove = false) }

    /**
     * On the shop WiFi: `POST /members/{id}/remove` and the hub deletes the template at once.
     * Owner mode off the WiFi: `POST /api/members/{id}/withdraw`; the hub applies it from its inbox.
     */
    fun remove() {
        val staff = c.appState.staff.value
        _state.update { it.copy(removing = true, removeError = false) }
        viewModelScope.launch {
            c.appState.touch()
            val onLan = _state.value.hub.onLan
            val ok = if (onLan && staff != null) {
                c.hub.removeMember(memberId, staff.staffId) is ApiResult.Ok
            } else if (ownerMode) {
                c.cloud.withdrawMember(memberId) is ApiResult.Ok
            } else false
            _state.update {
                if (ok) it.copy(removing = false, confirmRemove = false, removed = true, message = Message.REMOVED,
                    card = it.card?.copy(status = "withdrawn", hasTemplate = false))
                else it.copy(removing = false, removeError = true)
            }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    // ---- Clips -----------------------------------------------------------------------------

    /** A clip plays only on the shop WiFi; off it no clip request is made at all (TASK-081). */
    fun canPlay(visit: Visit): Boolean = ownerMode && visit.hasClip && _state.value.hub.onLan && withinRetention(visit)

    fun playClip(visit: Visit) {
        if (!canPlay(visit)) return
        _state.update { it.copy(clip = ClipState.Loading(visit.id)) }
        viewModelScope.launch {
            when (val r = c.hub.clip(visit.clipId ?: visit.id)) {
                is ApiResult.Ok -> _state.update { it.copy(clip = ClipState.Ready(visit.id, r.value)) }
                else -> _state.update { it.copy(clip = ClipState.Failed(visit.id)) }
            }
        }
    }

    fun closeClip() {
        (_state.value.clip as? ClipState.Ready)?.bytes?.fill(0)
        _state.update { it.copy(clip = ClipState.Idle) }
    }

    override fun onCleared() {
        (_state.value.clip as? ClipState.Ready)?.bytes?.fill(0)
        super.onCleared()
    }

    companion object {
        const val CLIP_RETENTION_DAYS = 30L

        fun withinRetention(visit: Visit, now: Instant = Instant.now()): Boolean {
            val at = Formatters.parseInstant(visit.ts) ?: return false
            return now.toEpochMilli() - at.toEpochMilli() < CLIP_RETENTION_DAYS * 24 * 3600 * 1000
        }
    }
}
