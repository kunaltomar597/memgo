package com.remu.app.core.format

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/** Words the formatters need in the current language. Built from string resources in the UI. */
data class DateWords(
    val today: String = "Today",
    val yesterday: String = "Yesterday",
    val justNow: String = "Just now",
    val minutesAgo: String = "%1\$d min ago",
    val hoursAgo: String = "%1\$d hr ago",
)

/** Formatting rules from docs/DESIGN.md section 7: ₹1,20,000, 18 Oct 2026, 11:42 am. */
object Formatters {
    val IST: ZoneId = ZoneId.of("Asia/Kolkata")

    /** Indian digit grouping: 20000 -> "20,000", 120000 -> "1,20,000"; paise only when present. */
    fun rupees(amount: Double): String {
        val bd = BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP)
        val negative = bd.signum() < 0
        val abs = bd.abs()
        val whole = abs.toBigInteger().toString()
        val paise = abs.remainder(BigDecimal.ONE).movePointRight(2).toInt()
        val grouped = if (whole.length <= 3) whole else {
            val last3 = whole.takeLast(3)
            val rest = whole.dropLast(3)
            rest.reversed().chunked(2).joinToString(",").reversed() + "," + last3
        }
        val body = if (paise == 0) grouped else grouped + "." + paise.toString().padStart(2, '0')
        return (if (negative) "-" else "") + "₹" + body
    }

    /** Parses an ISO 8601 timestamp with offset, or a plain YYYY-MM-DD date (start of day, IST). */
    fun parseInstant(value: String?): Instant? {
        if (value.isNullOrBlank()) return null
        return try {
            OffsetDateTime.parse(value).toInstant()
        } catch (_: DateTimeParseException) {
            try {
                LocalDate.parse(value).atStartOfDay(IST).toInstant()
            } catch (_: DateTimeParseException) {
                try { Instant.parse(value) } catch (_: DateTimeParseException) { null }
            }
        }
    }

    fun date(instant: Instant, locale: Locale = Locale.ENGLISH, zone: ZoneId = IST): String =
        DateTimeFormatter.ofPattern("d MMM yyyy", locale).format(instant.atZone(zone))

    /** "Today", "Yesterday", or "18 Oct 2026". */
    fun friendlyDate(
        instant: Instant,
        now: Instant,
        words: DateWords = DateWords(),
        locale: Locale = Locale.ENGLISH,
        zone: ZoneId = IST,
    ): String {
        val d = instant.atZone(zone).toLocalDate()
        val today = now.atZone(zone).toLocalDate()
        return when (d) {
            today -> words.today
            today.minusDays(1) -> words.yesterday
            else -> date(instant, locale, zone)
        }
    }

    /** "11:42 am". */
    fun time(instant: Instant, locale: Locale = Locale.ENGLISH, zone: ZoneId = IST): String =
        DateTimeFormatter.ofPattern("h:mm a", locale).format(instant.atZone(zone)).lowercase(locale)

    /** "18 Oct 2026, 11:42 am". */
    fun dateTime(instant: Instant, locale: Locale = Locale.ENGLISH, zone: ZoneId = IST): String =
        date(instant, locale, zone) + ", " + time(instant, locale, zone)

    /** "Just now", "2 min ago", "3 hr ago", then the friendly date. */
    fun ago(instant: Instant, now: Instant, words: DateWords = DateWords(), locale: Locale = Locale.ENGLISH): String {
        val d = Duration.between(instant, now)
        return when {
            d.toMinutes() < 1 -> words.justNow
            d.toMinutes() < 60 -> String.format(locale, words.minutesAgo, d.toMinutes())
            d.toHours() < 24 -> String.format(locale, words.hoursAgo, d.toHours())
            else -> friendlyDate(instant, now, words, locale)
        }
    }

    /** "+91 •••• 4321" from an E.164 number or from the hub's "+91******4321". */
    fun maskedPhone(value: String?): String {
        if (value.isNullOrBlank()) return ""
        val digits = value.filter { it.isDigit() }
        val last4 = digits.takeLast(4)
        return "+91 •••• $last4"
    }

    /** "+91 98765 43210" for the full number on member detail. */
    fun fullPhone(e164: String?): String {
        if (e164.isNullOrBlank()) return ""
        val digits = e164.filter { it.isDigit() }
        val national = if (digits.length == 12 && digits.startsWith("91")) digits.drop(2) else digits
        return if (national.length == 10) "+91 ${national.take(5)} ${national.drop(5)}" else e164
    }
}
