package com.remu.app.feature

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.remu.app.feature.enrol.Box
import com.remu.app.feature.enrol.DetectedFace
import com.remu.app.feature.enrol.FaceCropper
import com.remu.app.feature.enrol.Pt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.random.Random

/** TASK-044: a real JPEG under 300 KB, landmarks in crop pixels, nothing written to disk. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FaceCropperTest {
    private fun noisy(w: Int, h: Int): Bitmap {
        val r = Random(7)
        val px = IntArray(w * h) { 0xFF000000.toInt() or r.nextInt(0xFFFFFF) }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    @Test fun cropIsJpegUnderLimitWithLandmarksInCropPixels() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val before = ctx.cacheDir.walkTopDown().filter { it.isFile }.count()
        // Worst case for JPEG size: a big face full of noise.
        val img = noisy(1920, 1440)
        val face = DetectedFace(
            1, Box(400f, 300f, 1400f, 1300f),
            leftEye = Pt(1100f, 700f), rightEye = Pt(700f, 700f), nose = Pt(900f, 900f),
            mouthLeft = Pt(1050f, 1100f), mouthRight = Pt(750f, 1100f),
        )
        val crop = FaceCropper.crop(img, face)
        assertTrue("crop is ${crop.jpeg.size} bytes", crop.jpeg.size < FaceCropper.MAX_BYTES)
        assertEquals(0xFF.toByte(), crop.jpeg[0]); assertEquals(0xD8.toByte(), crop.jpeg[1])
        val decoded = BitmapFactory.decodeByteArray(crop.jpeg, 0, crop.jpeg.size)
        assertTrue(maxOf(decoded.width, decoded.height) <= FaceCropper.MAX_SIDE)
        assertEquals(5, crop.landmarks.size)
        // Image-left eye first, image-left mouth corner fourth.
        assertTrue(crop.landmarks[0][0] < crop.landmarks[1][0])
        assertTrue(crop.landmarks[3][0] < crop.landmarks[4][0])
        crop.landmarks.forEach { (x, y) ->
            assertTrue(x in 0f..decoded.width.toFloat()); assertTrue(y in 0f..decoded.height.toFloat())
        }
        val after = ctx.cacheDir.walkTopDown().filter { it.isFile }.count()
        assertEquals(before, after)
        crop.wipe()
        assertTrue(crop.jpeg.all { it == 0.toByte() })
    }

    @Test fun cropRectIsClampedToTheImage() {
        val r = FaceCropper.cropRect(Box(-50f, -50f, 100f, 100f), 640, 480)
        assertEquals(0, r[0]); assertEquals(0, r[1])
        val e = FaceCropper.cropRect(Box(600f, 450f, 700f, 520f), 640, 480)
        assertEquals(640, e[2]); assertEquals(480, e[3])
    }

    @Test fun missingLandmarksAreEstimated() {
        val pts = FaceCropper.landmarks(DetectedFace(1, Box(0f, 0f, 100f, 100f)))
        assertEquals(5, pts.size)
        assertEquals(30f, pts[0].x, 0.01f)
        assertEquals(70f, pts[1].x, 0.01f)
        File("unused").exists()
    }
}
