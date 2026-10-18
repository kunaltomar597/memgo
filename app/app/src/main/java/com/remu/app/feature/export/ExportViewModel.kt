package com.remu.app.feature.export

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.remu.app.AppContainer
import com.remu.app.network.ApiResult
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import java.time.ZoneId

data class ExportUiState(
    val working: Boolean = false,
    val failed: Boolean = false,
    val done: Boolean = false,
    val lastExportAt: String? = null,
)

/**
 * "Export consent records" (TASK-094): one tap asks the cloud for the signed PDF and CSV, then
 * opens the share sheet with both files and their signatures. A failure shows Retry.
 */
class ExportViewModel(private val c: AppContainer, private val share: ExportShare, private val shareTitle: String) : ViewModel() {
    private val _state = MutableStateFlow(ExportUiState(lastExportAt = c.session.current.lastExportAt))
    val state: StateFlow<ExportUiState> = _state.asStateFlow()

    fun export() {
        val shop = c.session.current.shopId ?: run { _state.update { it.copy(failed = true) }; return }
        if (_state.value.working) return
        _state.update { it.copy(working = true, failed = false, done = false) }
        viewModelScope.launch {
            c.appState.touch()
            val pdf = async { c.cloud.consentExport(shop, "pdf") }
            val csv = async { c.cloud.consentExport(shop, "csv") }
            val p = pdf.await()
            val s = csv.await()
            if (p !is ApiResult.Ok || s !is ApiResult.Ok) {
                _state.update { it.copy(working = false, failed = true) }
                return@launch
            }
            val day = OffsetDateTime.now(ZoneId.of("Asia/Kolkata")).toLocalDate().toString()
            val files = buildList {
                add(ExportedFile("remu-consent-$day.pdf", "application/pdf", p.value.bytes))
                add(ExportedFile("remu-consent-$day.csv", "text/csv", s.value.bytes))
                p.value.signature?.let { add(ExportedFile("remu-consent-$day.pdf.sig", "text/plain", it.toByteArray())) }
                s.value.signature?.let { add(ExportedFile("remu-consent-$day.csv.sig", "text/plain", it.toByteArray())) }
            }
            share.share(files, shareTitle)
            val now = OffsetDateTime.now(ZoneId.of("Asia/Kolkata")).toString()
            c.session.update { it.copy(lastExportAt = now) }
            _state.update { it.copy(working = false, done = true, lastExportAt = now) }
        }
    }
}
