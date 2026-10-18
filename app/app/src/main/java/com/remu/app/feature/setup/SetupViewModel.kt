package com.remu.app.feature.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.remu.app.AppContainer
import com.remu.app.core.format.Validation
import com.remu.app.data.session.WizardProgress
import com.remu.app.data.state.HubIdentityProblem
import com.remu.app.data.state.Mode
import com.remu.app.data.state.StaffSession
import com.remu.app.feature.pairing.HubIdentity
import com.remu.app.feature.pairing.HubPairing
import com.remu.app.feature.pairing.PairError
import com.remu.app.feature.pairing.PairingViewModel
import com.remu.app.network.ApiResult
import com.remu.app.network.cloud.ShopCreate
import com.remu.app.network.hub.DiscoveredStream
import com.remu.app.network.hub.HubClient
import com.remu.app.network.hub.StaffCreate
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class SetupStep { SHOP, PAIR, STREAM, AIM, COUNTER, STANDEE }

enum class SetupError { FIELDS, CLOUD, PAIR_CODE, HUB, PIN_INVALID, PIN_MISMATCH, NAME, NO_STREAMS, PIN_WRONG, NOT_OWNER_PIN, HUB_LOCKED }

/** What `cloud_shop_id` in `GET /health` says about the paired hub (docs/API_CONTRACT.md 7.6). */
enum class HubClaim { THIS_SHOP, UNCLAIMED, OTHER_SHOP }

data class SetupUiState(
    val step: SetupStep = SetupStep.SHOP,
    val elapsedMin: Long = 0,
    val working: Boolean = false,
    val error: SetupError? = null,
    val pairError: PairError? = null,
    // Shop
    val shopName: String = "",
    val city: String = "",
    val noticeLanguage: String = "hi",
    // Pair
    val host: String = "",
    val code: String = "",
    val needsOwnerPin: Boolean = false,
    /** The hub writes of the later steps need a staff session: ask for the owner PIN (contract 7.1). */
    val needsOwnerLogin: Boolean = false,
    val claim: HubClaim? = null,
    val ownerName: String = "",
    val pin: String = "",
    val pin2: String = "",
    // Stream
    val streams: List<DiscoveredStream>? = null,
    val chosenStream: String? = null,
    val finished: Boolean = false,
)

/**
 * The Owner mode setup wizard (TASK-106): create shop, pair hub, pick the gate stream, aim the
 * camera, add the counter phone, print the standee. Each step is saved so setup can resume, and
 * the elapsed time is shown against the 60-minute target.
 */
class SetupViewModel(private val c: AppContainer, private val clock: () -> Long = System::currentTimeMillis) : ViewModel() {
    private val _state = MutableStateFlow(SetupUiState())
    val state: StateFlow<SetupUiState> = _state.asStateFlow()

    init {
        // A refused or missing staff session mid-setup asks for the owner PIN again, in the wizard.
        viewModelScope.launch {
            c.appState.staff.collect { st ->
                if (st == null && needsSession(_state.value.step) && c.session.current.paired) {
                    _state.update { it.copy(needsOwnerLogin = true, working = false) }
                }
            }
        }
        viewModelScope.launch { c.hubStatus.status.collect { h -> onHubHealth(h.health) } }
        viewModelScope.launch {
            val s = c.session.update { it.copy(wizard = it.wizard ?: WizardProgress(startedAtEpochMs = clock())) }
            val w = s.wizard!!
            _state.update {
                it.copy(
                    step = SetupStep.entries[w.step.coerceIn(0, SetupStep.entries.size - 1)],
                    shopName = s.shopName.orEmpty(), city = s.shopCity.orEmpty(), noticeLanguage = s.noticeLanguage ?: "hi",
                    host = PairingViewModel.hostOf(s.hubBaseUrl), chosenStream = w.chosenStreamUrl,
                )
            }
            if (_state.value.step == SetupStep.PAIR && s.paired) checkOwnerStaff()
            else if (needsSession(_state.value.step) && c.appState.staff.value == null) _state.update { it.copy(needsOwnerLogin = true) }
            else if (_state.value.step == SetupStep.STREAM) discover()
            while (isActive) {
                val started = c.session.current.wizard?.startedAtEpochMs ?: clock()
                _state.update { it.copy(elapsedMin = (clock() - started) / 60_000) }
                delay(15_000)
            }
        }
    }

    private fun needsSession(step: SetupStep) = step.ordinal >= SetupStep.STREAM.ordinal

    /** Shows whom the hub is claimed by; a hub claimed by another shop is unpaired at once. */
    private fun onHubHealth(health: com.remu.app.network.hub.HubHealth?) {
        val sess = c.session.current
        if (health == null || !sess.paired) { _state.update { it.copy(claim = null) }; return }
        val claim = when {
            health.cloudShopId == null -> HubClaim.UNCLAIMED
            HubIdentity.claimedByOtherShop(health, sess.shopId) -> HubClaim.OTHER_SHOP
            else -> HubClaim.THIS_SHOP
        }
        _state.update { it.copy(claim = claim) }
        if (claim == HubClaim.OTHER_SHOP && sess.hubRole == "owner" && _state.value.step.ordinal >= SetupStep.PAIR.ordinal) {
            viewModelScope.launch {
                HubIdentity.unpair(c, HubIdentityProblem.OTHER_SHOP)
                goTo(SetupStep.PAIR)
                _state.update { it.copy(pairError = PairError.OTHER_SHOP, needsOwnerPin = false, needsOwnerLogin = false, claim = null) }
            }
        }
    }

    private suspend fun goTo(step: SetupStep) {
        c.session.update { it.copy(wizard = (it.wizard ?: WizardProgress(clock())).copy(step = step.ordinal)) }
        _state.update { it.copy(step = step, error = null, pairError = null, working = false) }
        if (needsSession(step) && c.appState.staff.value == null) { _state.update { it.copy(needsOwnerLogin = true) }; return }
        if (step == SetupStep.STREAM) discover()
    }

    fun back() {
        val i = _state.value.step.ordinal
        if (i > 0) viewModelScope.launch { goTo(SetupStep.entries[i - 1]) }
    }

    fun update(f: (SetupUiState) -> SetupUiState) = _state.update { f(it).copy(error = null, pairError = null) }

    // Step 1
    fun createShop() {
        val s = _state.value
        if (c.session.current.shopId != null && s.shopName.isBlank()) { viewModelScope.launch { goTo(SetupStep.PAIR) }; return }
        if (s.shopName.isBlank() || s.city.isBlank()) { _state.update { it.copy(error = SetupError.FIELDS) }; return }
        _state.update { it.copy(working = true) }
        viewModelScope.launch {
            val existing = c.session.current.shopId
            if (existing != null) {
                c.session.update { it.copy(shopName = s.shopName.trim(), shopCity = s.city.trim(), noticeLanguage = s.noticeLanguage) }
                goTo(SetupStep.PAIR); return@launch
            }
            when (val r = c.cloud.createShop(ShopCreate(s.shopName.trim(), s.city.trim(), s.noticeLanguage))) {
                is ApiResult.Ok -> {
                    c.session.update { it.copy(shopId = r.value.id, shopName = s.shopName.trim(), shopCity = s.city.trim(), noticeLanguage = s.noticeLanguage) }
                    goTo(SetupStep.PAIR)
                }
                else -> _state.update { it.copy(working = false, error = SetupError.CLOUD) }
            }
        }
    }

    // Step 2
    fun pairHub() {
        val s = _state.value
        val shop = c.session.current.shopId ?: return
        if (s.code.isBlank()) { _state.update { it.copy(pairError = PairError.CODE_EMPTY) }; return }
        _state.update { it.copy(working = true) }
        viewModelScope.launch {
            // The cloud links the hub's pairing code to the shop; a repeat after a LAN failure is 409.
            val cloud = c.cloud.pairDevice(shop, s.code)
            val cloudOk = cloud is ApiResult.Ok || (cloud is ApiResult.HttpError && cloud.status == 409)
            if (!cloudOk) {
                val e = if (cloud is ApiResult.HttpError && cloud.status in 400..499) SetupError.PAIR_CODE else SetupError.CLOUD
                _state.update { it.copy(working = false, error = e) }
                return@launch
            }
            val err = HubPairing.pair(c, s.host, s.code)
            if (err != null) { _state.update { it.copy(working = false, pairError = err) }; return@launch }
            checkOwnerStaff()
        }
    }

    private suspend fun checkOwnerStaff() {
        when (val r = c.hub.listStaff()) {
            is ApiResult.Ok -> {
                if (r.value.any { it.role == "owner" }) goTo(SetupStep.STREAM)
                else _state.update { it.copy(working = false, needsOwnerPin = true) }
            }
            else -> _state.update { it.copy(working = false, error = hubError(r)) }
        }
    }

    private fun hubError(r: ApiResult<*>): SetupError =
        if (r is ApiResult.HttpError && r.status == 423) SetupError.HUB_LOCKED else SetupError.HUB

    /**
     * `POST /staff/verify-pin` for the owner, keeping the `staff_session` in memory for the hub
     * writes that follow. Returns null on success, else the error to show.
     */
    private suspend fun ownerLogin(pin: String): SetupError? = when (val r = c.hub.verifyPin(pin)) {
        is ApiResult.Ok -> {
            val v = r.value
            if (v.role != "owner") SetupError.NOT_OWNER_PIN
            else {
                c.appState.login(StaffSession(v.staffId, v.name, v.role, Mode.OWNER, v.staffSession))
                c.session.update { it.copy(lastMode = Mode.OWNER.name, ownerStaffId = v.staffId, ownerStaffName = v.name) }
                null
            }
        }
        is ApiResult.HttpError -> if (r.status == 401 || r.status == 429) SetupError.PIN_WRONG else hubError(r)
        else -> SetupError.HUB
    }

    /** The hub already has an owner, or the staff session ended: the owner types their PIN. */
    fun unlockWithOwnerPin() {
        val s = _state.value
        if (!Validation.isValidPin(s.pin)) { _state.update { it.copy(error = SetupError.PIN_INVALID) }; return }
        _state.update { it.copy(working = true) }
        viewModelScope.launch {
            val e = ownerLogin(s.pin)
            if (e != null) { _state.update { it.copy(working = false, error = e, pin = "") }; return@launch }
            _state.update { it.copy(working = false, pin = "", needsOwnerLogin = false) }
            when (_state.value.step) {
                SetupStep.PAIR -> goTo(SetupStep.STREAM)
                SetupStep.STREAM -> discover()
                else -> Unit
            }
        }
    }

    fun createOwnerPin() {
        val s = _state.value
        val e = when {
            s.ownerName.isBlank() -> SetupError.NAME
            !Validation.isValidPin(s.pin) -> SetupError.PIN_INVALID
            s.pin != s.pin2 -> SetupError.PIN_MISMATCH
            else -> null
        }
        if (e != null) { _state.update { it.copy(error = e) }; return }
        _state.update { it.copy(working = true) }
        viewModelScope.launch {
            // The first owner is created before any staff session exists; the hub allows that only
            // while it has no staff at all. Then the same PIN opens the session (contract 7.1).
            when (val r = c.hub.createStaff(StaffCreate(s.ownerName.trim(), "owner", s.pin))) {
                is ApiResult.Ok -> {
                    c.session.update { it.copy(ownerStaffId = r.value.staffId, ownerStaffName = s.ownerName.trim()) }
                    val e = ownerLogin(s.pin)
                    _state.update { it.copy(pin = "", pin2 = "", needsOwnerPin = false, needsOwnerLogin = e != null, error = e) }
                    if (e == null) goTo(SetupStep.STREAM) else _state.update { it.copy(working = false) }
                }
                is ApiResult.HttpError -> if (r.status == 401 && r.error == HubClient.ERROR_STAFF_SESSION_REQUIRED) {
                    // Someone created staff meanwhile: sign in with an existing owner PIN instead.
                    _state.update { it.copy(working = false, pin = "", pin2 = "", needsOwnerPin = false, needsOwnerLogin = true) }
                } else _state.update { it.copy(working = false, error = hubError(r)) }
                else -> _state.update { it.copy(working = false, error = SetupError.HUB) }
            }
        }
    }

    // Step 3
    fun discover() {
        _state.update { it.copy(working = true, streams = null) }
        viewModelScope.launch {
            when (val r = c.hub.discoverStreams()) {
                is ApiResult.Ok -> _state.update { it.copy(working = false, streams = r.value.streams) }
                else -> _state.update { it.copy(working = false, error = hubError(r), streams = emptyList()) }
            }
        }
    }

    fun chooseStream(url: String) = _state.update { it.copy(chosenStream = url, error = null) }

    fun saveStream() {
        val url = _state.value.chosenStream ?: return
        _state.update { it.copy(working = true) }
        viewModelScope.launch {
            when (val r = c.hub.saveGateStream(url)) {
                is ApiResult.Ok -> {
                    c.session.update { it.copy(wizard = it.wizard?.copy(chosenStreamUrl = url)) }
                    goTo(SetupStep.AIM)
                }
                else -> _state.update { it.copy(working = false, error = hubError(r)) }
            }
        }
    }

    // Steps 4 to 6
    fun aimDone() = viewModelScope.launch { goTo(SetupStep.COUNTER) }
    fun counterDone() = viewModelScope.launch { goTo(SetupStep.STANDEE) }

    fun finish() = viewModelScope.launch {
        c.session.update { it.copy(wizard = it.wizard?.copy(completed = true, step = SetupStep.STANDEE.ordinal)) }
        _state.update { it.copy(finished = true) }
    }
}
