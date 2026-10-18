package com.remu.app.feature

import com.remu.app.feature.alerts.AlertCardFormatter
import com.remu.app.network.hub.AlertPayload
import com.remu.app.network.hub.LastPurchase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/** The brief's card line, exactly: "{name} · {household label} · Last visit {date} · ₹{amount}, {items} · Note: {notes}". */
class AlertCardFormatterTest {
    private val now = Instant.parse("2026-10-18T12:00:00Z")
    private fun payload(
        household: String? = "Sharma family",
        lastVisit: String? = "2025-10-12T17:42:11+05:30",
        amount: Double? = 20000.0,
        items: String? = "Gold necklace",
        notes: String? = "Asked about matching earrings",
    ) = AlertPayload("v_1", "m_1", "Rajesh Sharma", household, lastVisit, if (amount == null && items == null) null else LastPurchase(amount, items), notes, 0.71, 4)

    @Test fun fullLine() {
        val t = AlertCardFormatter.format(payload(), now)
        assertEquals("Rajesh Sharma · Sharma family · Last visit 12 Oct 2025 · ₹20,000, Gold necklace · Note: Asked about matching earrings", t.full)
        assertEquals("Rajesh Sharma", t.name)
        assertEquals("Sharma family · Last visit 12 Oct 2025 · ₹20,000, Gold necklace", t.details)
        assertEquals("Note: Asked about matching earrings", t.note)
    }

    @Test fun noHousehold() {
        assertEquals("Rajesh Sharma · Last visit 12 Oct 2025 · ₹20,000, Gold necklace · Note: Asked about matching earrings",
            AlertCardFormatter.format(payload(household = null), now).full)
        assertEquals("Rajesh Sharma · Last visit 12 Oct 2025 · ₹20,000, Gold necklace · Note: Asked about matching earrings",
            AlertCardFormatter.format(payload(household = "  "), now).full)
    }

    @Test fun emptyNotesAndPurchaseDropCleanly() {
        val t = AlertCardFormatter.format(payload(notes = "", amount = null, items = null), now)
        assertEquals("Rajesh Sharma · Sharma family · Last visit 12 Oct 2025", t.full)
        assertNull(t.note)
        assertEquals("Rajesh Sharma · Sharma family · Last visit 12 Oct 2025 · ₹1,20,000",
            AlertCardFormatter.format(payload(notes = null, amount = 120000.0, items = null), now).full)
        assertEquals("Rajesh Sharma · Sharma family · Last visit 12 Oct 2025 · gold bangles",
            AlertCardFormatter.format(payload(notes = null, amount = null, items = "gold bangles"), now).full)
    }

    @Test fun onlyName() {
        assertEquals("Rajesh Sharma", AlertCardFormatter.format(payload(null, null, null, null, null), now).full)
    }

    @Test fun lastVisitTodayAndMemberSinceDate() {
        assertEquals("Rajesh Sharma · Last visit Today",
            AlertCardFormatter.format(payload(null, "2026-10-18T10:00:00+05:30", null, null, null), now).full)
        assertEquals("Rajesh Sharma · Last visit 1 Oct 2026",
            AlertCardFormatter.format(payload(null, "2026-10-01", null, null, null), now).full)
    }
}
