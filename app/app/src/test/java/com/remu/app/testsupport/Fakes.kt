package com.remu.app.testsupport

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import com.remu.app.core.locale.AppLanguage
import com.remu.app.core.locale.LanguageManager
import com.remu.app.data.alerts.AlertItem
import com.remu.app.data.alerts.AlertNotifier
import com.remu.app.feature.enrol.Box as FaceBox
import com.remu.app.feature.enrol.CounterCamera
import com.remu.app.feature.enrol.DetectedFace
import com.remu.app.feature.enrol.FaceCrop
import com.remu.app.feature.enrol.FrameFaces
import com.remu.app.feature.enrol.Pt
import com.remu.app.network.ApiResult
import com.remu.app.network.cloud.OwnerAuth
import com.remu.app.network.cloud.OwnerTokens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.CopyOnWriteArrayList

/** A minimal valid JPEG (SOI, a comment, EOI) standing in for a face crop in UI tests. */
val FAKE_JPEG: ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xFE.toByte(), 0x00, 0x04, 0x52, 0x4D, 0xFF.toByte(), 0xD9.toByte())

/** Recorded ML Kit detections: two faces, face 2 the larger one. */
fun recordedFrame(vararg ids: Int = intArrayOf(1, 2)): FrameFaces {
    val faces = ids.mapIndexed { i, id ->
        val size = if (id == 2) 200f else 120f
        val left = 40f + i * 260f
        DetectedFace(
            trackingId = id,
            box = FaceBox(left, 100f, left + size, 100f + size),
            leftEye = Pt(left + size * 0.3f, 100f + size * 0.4f),
            rightEye = Pt(left + size * 0.7f, 100f + size * 0.4f),
            nose = Pt(left + size * 0.5f, 100f + size * 0.6f),
            mouthLeft = Pt(left + size * 0.35f, 100f + size * 0.8f),
            mouthRight = Pt(left + size * 0.65f, 100f + size * 0.8f),
        )
    }
    return FrameFaces(640, 480, faces)
}

/** The counter camera on recorded detection results; captures return tiny in-memory JPEGs. */
class FakeCamera(initial: FrameFaces? = recordedFrame()) : CounterCamera {
    val flow = MutableStateFlow(initial)
    override val frames: StateFlow<FrameFaces?> = flow
    val captures = CopyOnWriteArrayList<List<Int>>()

    override suspend fun capture(trackingIds: List<Int>): List<FaceCrop>? {
        captures += trackingIds
        val visible = flow.value?.faces?.map { it.trackingId }.orEmpty()
        if (!visible.containsAll(trackingIds)) return null
        return trackingIds.map { FaceCrop(FAKE_JPEG.copyOf(), List(5) { i -> listOf(10f + i, 20f + i) }) }
    }

    @Composable
    override fun Preview(modifier: Modifier) {
        Box(modifier.background(Color.DarkGray).testTag("fake_preview"))
    }
}

class FakeOwnerAuth(var ok: Boolean = true, var expiresIn: Long = 3600) : OwnerAuth {
    override val configured: Boolean = true
    val signIns = CopyOnWriteArrayList<String>()
    var refreshes = 0
    var signOuts = 0

    override suspend fun signIn(email: String, password: String): ApiResult<OwnerTokens> {
        signIns += email
        return if (ok) ApiResult.Ok(tokens()) else ApiResult.HttpError(400, null, null)
    }

    override suspend fun refresh(refreshToken: String): ApiResult<OwnerTokens> {
        refreshes++
        return if (ok) ApiResult.Ok(tokens()) else ApiResult.HttpError(401, null, null)
    }

    override suspend fun signOut(accessToken: String) { signOuts++ }

    private fun tokens() = OwnerTokens("access-1", "refresh-1", System.currentTimeMillis() / 1000 + expiresIn, "u_1", "owner@example.com")
}

class RecordingNotifier : AlertNotifier {
    val shown = CopyOnWriteArrayList<AlertItem>()
    override fun notify(item: AlertItem) { shown += item }
}

class FakeLanguageManager(var lang: AppLanguage = AppLanguage.ENGLISH) : LanguageManager() {
    val sets = CopyOnWriteArrayList<AppLanguage>()
    override fun current(): AppLanguage = lang
    override fun set(language: AppLanguage) { lang = language; sets += language }
}

