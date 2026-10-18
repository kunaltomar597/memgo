package com.remu.app.feature.enrol

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.remu.app.AppContainer
import com.remu.app.data.state.HubStatus
import com.remu.app.network.ApiResult
import com.remu.app.network.RemuJson
import com.remu.app.network.hub.EnrolErrorBody
import com.remu.app.network.hub.EnrolResponse
import com.remu.app.network.hub.HouseholdPatch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the cashier sees after Make member or Skip face. Strings live in the screen. */
sealed interface EnrolOutcome {
    data object Success : EnrolOutcome
    data object PartlyRefused : EnrolOutcome
    data object AllUnder18 : EnrolOutcome
    data object NoFace : EnrolOutcome
    data object FacesNotInView : EnrolOutcome
    data object SelectFace : EnrolOutcome
    data object PhoneMemberSaved : EnrolOutcome
    data object AddedToHousehold : EnrolOutcome
    data object HubError : EnrolOutcome
    data object CertificateChanged : EnrolOutcome
    data object NotLoggedIn : EnrolOutcome
}

enum class SendKind { MAKE_MEMBER, SKIP_FACE }

data class EnrolUiState(
    val frames: FrameFaces? = null,
    val selection: FaceSelection = FaceSelection(),
    val form: BillingForm = BillingForm(),
    val errors: FormErrors = FormErrors(),
    val sending: SendKind? = null,
    val outcome: EnrolOutcome? = null,
    val refusedTracking: Set<Int> = emptySet(),
    val hub: HubStatus = HubStatus(),
    val householdTarget: HouseholdTarget? = null,
    val openMemberId: String? = null,
    val lastKind: SendKind? = null,
) {
    /** Make member waits for the hub: face crops are never queued on the phone. */
    val hubReady: Boolean get() = hub.reachable == true && !hub.locked
}

/** Set when the cashier came from "Add adult at counter" on a household. */
data class HouseholdTarget(val id: String, val label: String)

/**
 * Cashier mode home (TASK-042 to TASK-048, TASK-051). The tap on Make member is the consent time;
 * there is no other consent control. Crops are dropped and wiped as soon as the hub replies.
 */
class EnrolViewModel(
    private val saved: SavedStateHandle,
    private val c: AppContainer,
    val camera: CounterCamera,
    householdTarget: HouseholdTarget? = null,
    private val resetAfterMs: Long = 5_000,
) : ViewModel() {
    private val _state = MutableStateFlow(
        EnrolUiState(form = restoreForm(), householdTarget = householdTarget),
    )
    val state: StateFlow<EnrolUiState> = _state.asStateFlow()
    private var resetJob: Job? = null

    init {
        viewModelScope.launch {
            camera.frames.collect { f ->
                _state.update { s -> s.copy(frames = f, selection = s.selection.onFaces(f?.faces.orEmpty())) }
            }
        }
        viewModelScope.launch { c.hubStatus.status.collect { h -> _state.update { it.copy(hub = h) } } }
    }

    // ---- Form ------------------------------------------------------------------------------

    private var labelFor: ((String) -> String)? = null

    fun onForm(input: BillingForm) {
        val old = _state.value.form
        val makeLabel = labelFor
        // The household label follows the name ("<surname> family") until the cashier edits it.
        val form = if (_state.value.selection.household && makeLabel != null &&
            (input.householdLabel.isBlank() || input.householdLabel == makeLabel(old.name)) && input.householdLabel == old.householdLabel
        ) input.copy(householdLabel = makeLabel(input.name)) else input
        persist(form)
        _state.update { s ->
            s.copy(form = form, errors = if (s.errors.ok) s.errors else EnrolPayloadBuilder.validate(form, s.selection.selected.size))
        }
    }

    private fun persist(f: BillingForm) {
        saved["name"] = f.name; saved["phone"] = f.phone; saved["billNo"] = f.billNo; saved["amount"] = f.amount
        saved["items"] = f.items; saved["occasion"] = f.occasion; saved["notes"] = f.notes
        saved["householdLabel"] = f.householdLabel; saved["payerIndex"] = f.payerIndex
        saved["adultNames"] = ArrayList(f.adultNames); saved["adultPhones"] = ArrayList(f.adultPhones)
    }

    private fun restoreForm() = BillingForm(
        name = saved["name"] ?: "", phone = saved["phone"] ?: "", billNo = saved["billNo"] ?: "",
        amount = saved["amount"] ?: "", items = saved["items"] ?: "", occasion = saved["occasion"] ?: "",
        notes = saved["notes"] ?: "", householdLabel = saved["householdLabel"] ?: "", payerIndex = saved["payerIndex"] ?: 0,
        adultNames = saved.get<ArrayList<String>>("adultNames") ?: emptyList(),
        adultPhones = saved.get<ArrayList<String>>("adultPhones") ?: emptyList(),
    )

    // ---- Faces -----------------------------------------------------------------------------

    fun tapFace(trackingId: Int) = _state.update { s ->
        s.copy(selection = s.selection.tap(trackingId), refusedTracking = s.refusedTracking - trackingId, outcome = s.outcome.takeUnlessTransient())
    }

    fun removeFace(trackingId: Int) = _state.update { it.copy(selection = it.selection.remove(trackingId)) }

    fun setHousehold(on: Boolean, defaultLabel: (String) -> String) = _state.update { s ->
        labelFor = defaultLabel
        val form = if (on && s.form.householdLabel.isBlank()) s.form.copy(householdLabel = defaultLabel(s.form.name)) else s.form
        persist(form)
        s.copy(selection = s.selection.setHousehold(on), form = form)
    }

    private fun EnrolOutcome?.takeUnlessTransient(): EnrolOutcome? =
        if (this == EnrolOutcome.SelectFace || this == EnrolOutcome.FacesNotInView) null else this

    // ---- Send ------------------------------------------------------------------------------

    fun makeMember() = send(SendKind.MAKE_MEMBER)

    fun skipFace() = send(SendKind.SKIP_FACE)

    fun dismissOutcome() = _state.update { it.copy(outcome = null) }

    fun consumeOpenMember() = _state.update { it.copy(openMemberId = null) }

    private fun send(kind: SendKind) {
        val s = _state.value
        if (s.sending != null) return
        val staff = c.appState.staff.value
        if (staff == null) { _state.update { it.copy(outcome = EnrolOutcome.NotLoggedIn) }; return }
        val ids = if (kind == SendKind.MAKE_MEMBER) s.selection.selected else emptyList()
        if (kind == SendKind.MAKE_MEMBER && ids.isEmpty()) { _state.update { it.copy(outcome = EnrolOutcome.SelectFace) }; return }
        val errors = EnrolPayloadBuilder.validate(s.form, ids.size)
        if (!errors.ok) { _state.update { it.copy(errors = errors) }; return }
        c.appState.touch()
        resetJob?.cancel()
        _state.update { it.copy(sending = kind, lastKind = kind, errors = FormErrors(), outcome = null) }
        viewModelScope.launch {
            var crops: List<FaceCrop> = emptyList()
            try {
                if (ids.isNotEmpty()) {
                    val captured = camera.capture(ids)
                    if (captured == null) { finish(EnrolOutcome.FacesNotInView); return@launch }
                    crops = captured
                }
                val request = EnrolPayloadBuilder.build(s.form, crops, staff.staffId)
                val result = c.hub.enrol(request)
                handle(kind, ids, result)
            } finally {
                crops.forEach { it.wipe() }
            }
        }
    }

    private suspend fun handle(kind: SendKind, ids: List<Int>, result: ApiResult<EnrolResponse>) {
        when (result) {
            is ApiResult.Ok -> {
                val r = result.value
                val refused = r.refused.mapNotNull { ids.getOrNull(it.faceIndex) }.toSet()
                val target = _state.value.householdTarget
                val outcome = when {
                    kind == SendKind.SKIP_FACE || r.phoneMember -> {
                        c.session.update { it.copy(skipFaceCount = it.skipFaceCount + 1) }
                        EnrolOutcome.PhoneMemberSaved
                    }
                    target != null && r.members.isNotEmpty() -> {
                        val add = c.hub.patchHousehold(target.id, HouseholdPatch(addMemberIds = r.members.map { it.memberId }))
                        if (add is ApiResult.Ok) EnrolOutcome.AddedToHousehold else EnrolOutcome.HubError
                    }
                    refused.isNotEmpty() -> EnrolOutcome.PartlyRefused
                    else -> EnrolOutcome.Success
                }
                clearForm()
                _state.update { it.copy(refusedTracking = refused, selection = FaceSelection(), householdTarget = if (outcome == EnrolOutcome.AddedToHousehold) null else it.householdTarget) }
                finish(outcome)
                scheduleReset()
            }
            is ApiResult.HttpError -> {
                val body = try { result.body?.let { RemuJson.decodeFromString(EnrolErrorBody.serializer(), it) } } catch (_: Exception) { null }
                when {
                    result.status == 409 && body?.memberId != null -> {
                        _state.update { it.copy(openMemberId = body.memberId) }
                        finish(null)
                    }
                    result.status == 422 && (body?.error ?: result.error) == "under_18" -> {
                        val refused = body?.refused?.mapNotNull { ids.getOrNull(it.faceIndex) }?.toSet()?.ifEmpty { null } ?: ids.toSet()
                        _state.update { it.copy(refusedTracking = refused, selection = FaceSelection()) }
                        finish(EnrolOutcome.AllUnder18)
                    }
                    result.status == 422 -> finish(EnrolOutcome.NoFace)
                    result.status == 423 || result.error == "locked" -> { c.hubStatus.reportFailure(); finish(EnrolOutcome.HubError) }
                    else -> finish(EnrolOutcome.HubError)
                }
            }
            ApiResult.CertificateChanged -> finish(EnrolOutcome.CertificateChanged)
            ApiResult.Unreachable, ApiResult.NotConfigured -> { c.hubStatus.reportFailure(); finish(EnrolOutcome.HubError) }
        }
    }

    private fun finish(outcome: EnrolOutcome?) = _state.update { it.copy(sending = null, outcome = outcome) }

    private fun clearForm() {
        val empty = BillingForm()
        persist(empty)
        _state.update { it.copy(form = empty) }
    }

    /** Back to a clean camera screen 5 seconds after a success (docs/DESIGN.md section 6). */
    private fun scheduleReset() {
        resetJob?.cancel()
        resetJob = viewModelScope.launch {
            delay(resetAfterMs)
            _state.update { it.copy(outcome = null, refusedTracking = emptySet()) }
        }
    }

    /** Retry after a hub error keeps the form and the selection. */
    fun retry(kind: SendKind) = send(kind)
}
