package com.remu.app

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import com.remu.app.core.locale.LanguageManager
import com.remu.app.data.alerts.AlertNotifier
import com.remu.app.data.session.InMemorySessionStore
import com.remu.app.data.session.StoredSession
import com.remu.app.data.state.FixedInternetState
import com.remu.app.feature.enrol.Box as FaceBox
import com.remu.app.feature.enrol.CounterCamera
import com.remu.app.feature.enrol.CounterCameraFactory
import com.remu.app.feature.enrol.DetectedFace
import com.remu.app.feature.enrol.FaceCrop
import com.remu.app.feature.enrol.FaceCropper
import com.remu.app.feature.enrol.FrameFaces
import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking

/** A camera on a synthetic still: one face, cropped for real with FaceCropper on the device. */
class StillCamera : CounterCamera {
    private val face = DetectedFace(1, FaceBox(160f, 120f, 480f, 440f))
    override val frames: StateFlow<FrameFaces?> = MutableStateFlow(FrameFaces(640, 480, listOf(face)))
    override suspend fun capture(trackingIds: List<Int>): List<FaceCrop>? {
        val bmp = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(200, 160, 130)) }
        return listOf(FaceCropper.crop(bmp, face)).also { bmp.recycle() }
    }
    @Composable override fun Preview(modifier: Modifier) { Box(modifier.background(Color.DarkGray).testTag("still_preview")) }
}

fun instrumentedContainer(context: Context, hubUrl: String, cloudUrl: String, camera: CounterCamera = StillCamera()): AppContainer {
    val c = AppContainer(
        appContext = context,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        sessionStore = InMemorySessionStore(StoredSession(hubBaseUrl = hubUrl, hubToken = "t", certSha256 = "AB", hubRole = "counter", shopId = "shop")),
        cloudBaseUrl = cloudUrl,
        supabaseUrl = cloudUrl,
        supabaseAnonKey = "anon",
        internet = FixedInternetState(true),
        cameraFactory = CounterCameraFactory { _, _ -> camera },
        notifier = AlertNotifier { },
        language = LanguageManager(),
    )
    runBlocking { c.session.awaitLoaded(); c.hubStatus.check() }
    return c
}
