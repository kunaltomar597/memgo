package com.remu.app.core

import com.remu.app.core.format.Formatters
import com.remu.app.core.format.Validation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class FormattersTest {
    @Test fun rupeesUseIndianGrouping() {
        assertEquals("₹20,000", Formatters.rupees(20000.0))
        assertEquals("₹1,20,000", Formatters.rupees(120000.0))
        assertEquals("₹12,34,567", Formatters.rupees(1234567.0))
        assertEquals("₹999", Formatters.rupees(999.0))
        assertEquals("₹0", Formatters.rupees(0.0))
        assertEquals("₹1,500.50", Formatters.rupees(1500.5))
    }

    @Test fun datesAndTimes() {
        val t = Formatters.parseInstant("2026-10-18T11:42:00+05:30")!!
        assertEquals("18 Oct 2026", Formatters.date(t))
        assertEquals("11:42 am", Formatters.time(t))
        assertEquals("18 Oct 2026, 11:42 am", Formatters.dateTime(t))
        assertEquals("18 Oct 2026", Formatters.date(Formatters.parseInstant("2026-10-18")!!))
        assertNull(Formatters.parseInstant("not a date"))
    }

    @Test fun todayYesterdayAndAgo() {
        val now = Formatters.parseInstant("2026-10-18T18:00:00+05:30")!!
        assertEquals("Today", Formatters.friendlyDate(Formatters.parseInstant("2026-10-18T09:00:00+05:30")!!, now))
        assertEquals("Yesterday", Formatters.friendlyDate(Formatters.parseInstant("2026-10-17T23:00:00+05:30")!!, now))
        assertEquals("16 Oct 2026", Formatters.friendlyDate(Formatters.parseInstant("2026-10-16T09:00:00+05:30")!!, now))
        assertEquals("Just now", Formatters.ago(now.minusSeconds(20), now))
        assertEquals("2 min ago", Formatters.ago(now.minusSeconds(130), now))
        assertEquals("3 hr ago", Formatters.ago(now.minusSeconds(3 * 3600 + 5), now))
    }

    @Test fun phonesAreMasked() {
        assertEquals("+91 •••• 3210", Formatters.maskedPhone("+91******3210"))
        assertEquals("+91 •••• 3210", Formatters.maskedPhone("+919876543210"))
        assertEquals("+91 98765 43210", Formatters.fullPhone("+919876543210"))
    }

    @Test fun phoneValidation() {
        assertEquals("9876543210", Validation.normalisePhone("9876543210"))
        assertEquals("9876543210", Validation.normalisePhone("+91 98765 43210"))
        assertEquals("9876543210", Validation.normalisePhone("919876543210"))
        assertEquals("9876543210", Validation.normalisePhone("09876543210"))
        assertEquals("+919876543210", Validation.toE164("98765-43210"))
        assertNull(Validation.normalisePhone("5876543210"))
        assertNull(Validation.normalisePhone("98765"))
        assertNull(Validation.normalisePhone("98765abcde"))
        assertEquals(Validation.PhoneError.EMPTY, Validation.phoneError(" "))
        assertEquals(Validation.PhoneError.INVALID, Validation.phoneError("12345"))
        assertNull(Validation.phoneError("9876543210"))
    }

    @Test fun amountsBillNumbersPins() {
        assertEquals(20000.0, Validation.parseAmount("20,000")!!, 0.0)
        assertEquals(99.5, Validation.parseAmount("₹99.50")!!, 0.0)
        assertEquals(0.0, Validation.parseAmount("0")!!, 0.0)
        assertNull(Validation.parseAmount("-5"))
        assertNull(Validation.parseAmount("1.234"))
        assertNull(Validation.parseAmount("abc"))
        assertTrue(Validation.isValidBillNo("INV-2026-10-18-0417"))
        assertFalse(Validation.isValidBillNo(""))
        assertFalse(Validation.isValidBillNo("bill no 1"))
        assertTrue(Validation.isValidPin("4821"))
        assertTrue(Validation.isValidPin("482199"))
        assertFalse(Validation.isValidPin("482"))
        assertFalse(Validation.isValidPin("4821990"))
        assertFalse(Validation.isValidPin("48a1"))
        assertEquals("Sharma family", Validation.defaultHouseholdLabel("Rajesh Sharma"))
        assertEquals("Sharma परिवार", Validation.defaultHouseholdLabel("Rajesh Sharma", "%1\$s परिवार"))
    }

    @Test fun parseInstantAcceptsZulu() {
        assertEquals(Instant.parse("2026-10-18T06:12:11Z"), Formatters.parseInstant("2026-10-18T06:12:11Z"))
    }
}
