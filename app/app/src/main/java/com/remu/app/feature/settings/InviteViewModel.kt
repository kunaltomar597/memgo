package com.remu.app.feature.settings

import android.graphics.Bitmap
import android.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.remu.app.AppContainer
import com.remu.app.feature.pairing.PairLink
import com.remu.app.feature.pairing.PairingViewModel
import com.remu.app.network.ApiResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class InviteUiState(
    val loading: Boolean = false,
    val code: String? = null,
    val link: String? = null,
    val failed: Boolean = false,
)

/** "Add the counter phone": a one-time invite (10 minutes) shown as a QR code (TASK-028, TASK-106). */
class InviteViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(InviteUiState())
    val state: StateFlow<InviteUiState> = _state.asStateFlow()

    init { newCode() }

    fun newCode() {
        _state.update { it.copy(loading = true, failed = false) }
        viewModelScope.launch {
            when (val r = c.hub.createInvite()) {
                is ApiResult.Ok -> {
                    val host = PairingViewModel.hostOf(c.session.current.hubBaseUrl)
                    val link = PairLink(host, r.value.inviteCode).toUri()
                    _state.update { it.copy(loading = false, code = r.value.inviteCode, link = link) }
                }
                else -> _state.update { it.copy(loading = false, failed = true) }
            }
        }
    }

    companion object {
        /** A QR bitmap in memory; it holds only the hub address and the one-time invite code. */
        fun qr(text: String, size: Int = 640): Bitmap {
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            for (x in 0 until size) for (y in 0 until size) bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            return bmp
        }
    }
}
