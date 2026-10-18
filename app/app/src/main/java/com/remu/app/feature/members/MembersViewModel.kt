package com.remu.app.feature.members

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.remu.app.AppContainer
import com.remu.app.core.format.Formatters
import com.remu.app.core.format.Validation
import com.remu.app.network.ApiResult
import com.remu.app.network.hub.MemberSummary
import com.remu.app.network.hub.PurchaseCreate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

enum class LoadError { HUB_UNREACHABLE, CERT_CHANGED, CLOUD_UNREACHABLE, HUB_LOCKED, OTHER }

fun <T> ApiResult<T>.loadError(): LoadError? = when (this) {
    is ApiResult.Ok -> null
    ApiResult.Unreachable, ApiResult.NotConfigured -> LoadError.HUB_UNREACHABLE
    ApiResult.CertificateChanged -> LoadError.CERT_CHANGED
    is ApiResult.HttpError -> if (status == 423) LoadError.HUB_LOCKED else LoadError.OTHER
}

/** Add-bill fields, shared by the member list and member detail. */
data class BillDraft(val billNo: String = "", val amount: String = "", val items: String = "", val occasion: String = "") {
    val billNoOk: Boolean get() = Validation.isValidBillNo(billNo)
    val amountOk: Boolean get() = Validation.parseAmount(amount) != null
}

data class MembersUiState(
    val query: String = "",
    val status: String? = null,
    val loading: Boolean = false,
    val error: LoadError? = null,
    val results: List<MemberSummary>? = null,
    val billFor: MemberSummary? = null,
    val bill: BillDraft = BillDraft(),
    val billTried: Boolean = false,
    val billSending: Boolean = false,
    val billError: Boolean = false,
    val message: Message? = null,
)

enum class Message { BILL_ADDED, NOTES_SAVED, REMOVED, HOUSEHOLD_SAVED }

/** Member search by phone or name (TASK-049, TASK-080), with Add bill on each row. */
class MembersViewModel(private val c: AppContainer, val ownerMode: Boolean) : ViewModel() {
    private val _state = MutableStateFlow(MembersUiState())
    val state: StateFlow<MembersUiState> = _state.asStateFlow()

    init { search() }

    fun onQuery(q: String) = _state.update { it.copy(query = q) }

    fun onStatus(status: String?) { _state.update { it.copy(status = status) }; search() }

    private var searchJob: kotlinx.coroutines.Job? = null

    fun search() {
        searchJob?.cancel() // the newest query wins
        val s = _state.value
        val q = s.query.trim()
        val phone = Validation.toE164(q)
        _state.update { it.copy(loading = true, error = null) }
        searchJob = viewModelScope.launch {
            val r = when {
                phone != null -> c.hub.members(phone = phone, status = s.status)
                q.isNotEmpty() -> c.hub.members(q = q, status = s.status)
                else -> c.hub.members(status = s.status)
            }
            c.appState.touch()
            when (r) {
                is ApiResult.Ok -> _state.update { it.copy(loading = false, results = sortByLastSeen(r.value)) }
                else -> {
                    if (r is ApiResult.Unreachable) c.hubStatus.reportFailure()
                    _state.update { it.copy(loading = false, error = r.loadError()) }
                }
            }
        }
    }

    fun openBill(member: MemberSummary) = _state.update { it.copy(billFor = member, bill = BillDraft(), billTried = false, billError = false) }
    fun onBill(d: BillDraft) = _state.update { it.copy(bill = d) }
    fun closeBill() = _state.update { it.copy(billFor = null) }
    fun consumeMessage() = _state.update { it.copy(message = null) }

    fun sendBill() {
        val s = _state.value
        val member = s.billFor ?: return
        if (!s.bill.billNoOk || !s.bill.amountOk) { _state.update { it.copy(billTried = true) }; return }
        val staff = c.appState.staff.value ?: return
        _state.update { it.copy(billSending = true, billError = false) }
        viewModelScope.launch {
            val r = c.hub.addPurchase(member.id, s.bill.toRequest(staff.staffId))
            _state.update {
                if (r is ApiResult.Ok) it.copy(billSending = false, billFor = null, message = Message.BILL_ADDED)
                else it.copy(billSending = false, billError = true)
            }
        }
    }

    companion object {
        fun sortByLastSeen(list: List<MemberSummary>): List<MemberSummary> =
            list.sortedByDescending { Formatters.parseInstant(it.lastSeenAt) ?: Instant.EPOCH }
    }
}

fun BillDraft.toRequest(staffId: String) = PurchaseCreate(
    staffId = staffId,
    billNo = billNo.trim(),
    amount = Validation.parseAmount(amount) ?: 0.0,
    items = items.trim().ifEmpty { null },
    occasion = occasion.trim().ifEmpty { null },
)
