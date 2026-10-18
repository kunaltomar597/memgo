package com.remu.app.feature.enrol

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import com.remu.app.R
import com.remu.app.core.ui.PrimaryButton
import com.remu.app.core.ui.RemuText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors

/**
 * The counter camera: a live preview, the latest ML Kit detections, and a still capture of the
 * selected faces. Behind an interface so UI tests run on recorded detections without a camera.
 */
interface CounterCamera {
    val frames: StateFlow<FrameFaces?>

    /**
     * Takes the next frame and crops the faces with these tracking ids, in that order. Returns
     * null when any of them is no longer in view. The crops exist in memory only.
     */
    suspend fun capture(trackingIds: List<Int>): List<FaceCrop>?

    @Composable
    fun Preview(modifier: Modifier)
}

fun interface CounterCameraFactory {
    fun create(context: Context, front: Boolean): CounterCamera
}

/** CameraX preview plus an ML Kit analyser (landmarks on, tracking on), about 10 frames a second. */
class CameraXCounterCamera(private val front: Boolean) : CounterCamera {
    private val _frames = MutableStateFlow<FrameFaces?>(null)
    override val frames: StateFlow<FrameFaces?> = _frames

    @Volatile private var pendingCapture: Pair<List<Int>, CompletableDeferred<List<FaceCrop>?>>? = null

    private val detector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setMinFaceSize(0.12f)
                .enableTracking()
                .build(),
        )
    }

    override suspend fun capture(trackingIds: List<Int>): List<FaceCrop>? {
        val d = CompletableDeferred<List<FaceCrop>?>()
        pendingCapture = trackingIds to d
        return withTimeoutOrNull(3_000) { d.await() }.also { pendingCapture = null }
    }

    private fun toFace(f: Face, id: Int): DetectedFace {
        fun lm(type: Int) = f.getLandmark(type)?.position?.let { Pt(it.x, it.y) }
        val b = f.boundingBox
        return DetectedFace(
            trackingId = id,
            box = Box(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat()),
            leftEye = lm(FaceLandmark.LEFT_EYE),
            rightEye = lm(FaceLandmark.RIGHT_EYE),
            nose = lm(FaceLandmark.NOSE_BASE),
            mouthLeft = lm(FaceLandmark.MOUTH_LEFT),
            mouthRight = lm(FaceLandmark.MOUTH_RIGHT),
        )
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        if (media == null) { proxy.close(); return }
        val rotation = proxy.imageInfo.rotationDegrees
        val upright = rotation == 0 || rotation == 180
        val w = if (upright) proxy.width else proxy.height
        val h = if (upright) proxy.height else proxy.width
        val input = InputImage.fromMediaImage(media, rotation)
        detector.process(input)
            .addOnSuccessListener { faces ->
                var nextId = -1
                val mapped = faces.map { f -> toFace(f, f.trackingId ?: nextId--) }
                _frames.value = FrameFaces(w, h, mapped, mirrored = front)
                val pending = pendingCapture
                if (pending != null) {
                    pendingCapture = null
                    val byId = mapped.associateBy { it.trackingId }
                    val wanted = pending.first.map { byId[it] }
                    if (wanted.any { it == null }) {
                        pending.second.complete(null)
                    } else {
                        // The still lives in memory only and is recycled as soon as the crops exist.
                        val still = uprightBitmap(proxy, rotation)
                        val crops = wanted.filterNotNull().map { FaceCropper.crop(still, it) }
                        still.recycle()
                        pending.second.complete(crops)
                    }
                }
            }
            .addOnCompleteListener { proxy.close() }
    }

    private fun uprightBitmap(proxy: ImageProxy, rotation: Int): Bitmap {
        val raw = proxy.toBitmap()
        if (rotation == 0) return raw
        val m = Matrix().apply { postRotate(rotation.toFloat()) }
        val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        raw.recycle()
        return rotated
    }

    @Composable
    override fun Preview(modifier: Modifier) {
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current
        var granted by remember {
            mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
        }
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
        if (!granted) {
            Column(
                modifier.background(Color(0xFF1C1917)).padding(16.dp).testTag("camera_permission"),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                RemuText(stringResource(R.string.camera_permission_needed), color = Color.White)
                PrimaryButton(stringResource(R.string.camera_allow), onClick = { launcher.launch(Manifest.permission.CAMERA) })
            }
            return
        }
        var started by remember { mutableStateOf(false) }
        Box(modifier.clipToBounds().background(Color(0xFF1C1917))) {
            val previewView = remember {
                PreviewView(context).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                }
            }
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            if (!started) {
                RemuText(stringResource(R.string.camera_starting), color = Color.White, modifier = Modifier.align(Alignment.Center))
            }
            DisposableEffect(lifecycleOwner, front) {
                val executor = Executors.newSingleThreadExecutor()
                val providerFuture = ProcessCameraProvider.getInstance(context)
                var provider: ProcessCameraProvider? = null
                providerFuture.addListener({
                    val p = providerFuture.get()
                    provider = p
                    val ratio = ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build()
                    val preview = Preview.Builder().setResolutionSelector(ratio).build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    val analysis = ImageAnalysis.Builder()
                        .setResolutionSelector(ratio)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                        .build()
                    analysis.setAnalyzer(executor) { analyze(it) }
                    val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                    try {
                        p.unbindAll()
                        p.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
                        started = true
                    } catch (_: Exception) {
                        started = false
                    }
                }, ContextCompat.getMainExecutor(context))
                onDispose {
                    provider?.unbindAll()
                    executor.shutdown()
                    _frames.value = null
                }
            }
        }
    }
}
