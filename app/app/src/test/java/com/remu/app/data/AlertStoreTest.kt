package com.remu.app.data

import com.remu.app.data.alerts.AlertSource
import com.remu.app.data.alerts.AlertStore
import com.remu.app.data.alerts.Feedback
import com.remu.app.network.hub.AlertPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AlertStoreTest {
    private var now = Instant.parse("2026-10-18T06:00:00Z")
    private val store = AlertStore(clock = { now })
    private fun p(v: String) = AlertPayload(v, "m_$v", "Member $v")

    @Test fun sameVisitFromFcmAndWebSocketShowsOnce() {
        assertTrue(store.add(p("v_1"), AlertSource.WEBSOCKET))
        assertFalse(store.add(p("v_1"), AlertSource.FCM))
        assertFalse(store.add(p("v_1"), AlertSource.WEBSOCKET))
        assertEquals(1, store.alerts.value.size)
        assertEquals(AlertSource.WEBSOCKET, store.alerts.value.single().source)
    }

    @Test fun newestFirstAndUnactionedOnTop() {
        store.add(p("v_1"), AlertSource.FCM, now.minusSeconds(300))
        store.add(p("v_2"), AlertSource.FCM, now.minusSeconds(200))
        store.add(p("v_3"), AlertSource.FCM, now.minusSeconds(100))
        assertEquals(listOf("v_3", "v_2", "v_1"), store.alerts.value.map { it.visitId })
        store.setFeedback("v_3", Feedback.GREETED)
        assertEquals(listOf("v_2", "v_1", "v_3"), store.alerts.value.map { it.visitId })
    }

    @Test fun notThemDismissesAndReplayDoesNotBringItBack() {
        store.add(p("v_1"), AlertSource.WEBSOCKET)
        store.setFeedback("v_1", Feedback.NOT_THEM)
        assertTrue(store.alerts.value.isEmpty())
        assertFalse(store.add(p("v_1"), AlertSource.WEBSOCKET))
        assertTrue(store.alerts.value.isEmpty())
    }

    @Test fun cardsGreyOutAfterTenMinutes() {
        store.add(p("v_1"), AlertSource.FCM, now.minusSeconds(9 * 60))
        store.add(p("v_2"), AlertSource.FCM, now.minusSeconds(11 * 60))
        assertFalse(store.isStale(store.get("v_1")!!))
        assertTrue(store.isStale(store.get("v_2")!!))
    }

    @Test fun todayCounts() {
        store.add(p("v_old"), AlertSource.FCM, Instant.parse("2026-10-17T10:00:00Z"))
        store.add(p("v_1"), AlertSource.FCM)
        store.add(p("v_2"), AlertSource.FCM)
        store.add(p("v_3"), AlertSource.FCM)
        store.setFeedback("v_1", Feedback.GREETED)
        store.setFeedback("v_2", Feedback.GREETED)
        assertEquals(AlertStore.Today(3, 2), store.today())
    }

    @Test fun blankVisitIdIgnored() {
        assertFalse(store.add(p(""), AlertSource.FCM))
    }
}
