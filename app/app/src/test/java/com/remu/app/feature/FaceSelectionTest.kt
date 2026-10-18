package com.remu.app.feature

import com.remu.app.feature.enrol.FaceSelection
import com.remu.app.feature.enrol.OverlayTransform
import com.remu.app.testsupport.recordedFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TASK-042 and TASK-043 on recorded detection results. */
class FaceSelectionTest {
    private val two = recordedFrame(1, 2).faces

    @Test fun largestFaceIsPreselected() {
        val s = FaceSelection().onFaces(two)
        assertEquals(listOf(2), s.selected)
    }

    @Test fun tapMovesTheSelectionAndSticks() {
        val s = FaceSelection().onFaces(two).tap(1).onFaces(two).onFaces(two)
        assertEquals(listOf(1), s.selected)
    }

    @Test fun manualChoiceFallsBackWhenThatFaceLeaves() {
        val s = FaceSelection().onFaces(two).tap(1).onFaces(recordedFrame(2).faces)
        assertEquals(listOf(2), s.selected)
    }

    @Test fun noFacesClearsAutomaticSelection() {
        assertTrue(FaceSelection().onFaces(two).onFaces(emptyList()).selected.isEmpty())
    }

    @Test fun householdAddsUpToSix() {
        var s = FaceSelection().onFaces(two).setHousehold(true)
        for (id in 10..20) s = s.tap(id)
        assertEquals(6, s.selected.size)
        assertEquals(listOf(2, 10, 11, 12, 13, 14), s.selected)
        assertEquals(1, s.numberOf(2))
        assertEquals(3, s.numberOf(11))
        assertNull(s.numberOf(99))
    }

    @Test fun chipRemovesAnAdultAndToggleOffKeepsOne() {
        val s = FaceSelection().onFaces(two).setHousehold(true).tap(1)
        assertEquals(listOf(2, 1), s.selected)
        assertEquals(listOf(1), s.remove(2).selected)
        assertEquals(listOf(2), s.setHousehold(false).selected)
    }

    @Test fun householdSelectionSurvivesBriefDetectionGaps() {
        val s = FaceSelection().onFaces(two).setHousehold(true).tap(1).onFaces(emptyList())
        assertEquals(listOf(2, 1), s.selected)
    }

    @Test fun overlayFillCenterAndMirror() {
        val box = com.remu.app.feature.enrol.Box(0f, 0f, 320f, 240f)
        // 640x480 image into a 1280x960 view: scale 2, no offset.
        val v = OverlayTransform.map(box, 640, 480, 1280f, 960f, mirrored = false)
        assertEquals(0f, v.left, 0.01f); assertEquals(640f, v.width, 0.01f)
        val m = OverlayTransform.map(box, 640, 480, 1280f, 960f, mirrored = true)
        assertEquals(640f, m.left, 0.01f)
        // 640x480 into 1080x560: scale = max(1.6875, 1.1667) = 1.6875; dy = (560-810)/2 = -125.
        val f = OverlayTransform.map(box, 640, 480, 1080f, 560f, mirrored = false)
        assertEquals(-125f, f.top, 0.01f)
        assertEquals(540f, f.width, 0.01f)
    }
}
