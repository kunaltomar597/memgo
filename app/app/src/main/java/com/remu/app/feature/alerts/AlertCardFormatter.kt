package com.remu.app.feature.alerts

import com.remu.app.core.format.DateWords
import com.remu.app.core.format.Formatters
import com.remu.app.network.hub.AlertPayload
import java.time.Instant
import java.util.Locale

/** Localised pieces of the alert card. Defaults are the English strings from docs/BRIEF.md 6. */
data class AlertLabels(
    val lastVisit: String = "Last visit %1\$s",
    val note: String = "Note: %1\$s",
    val words: DateWords = DateWords(),
    val locale: Locale = Locale.ENGLISH,
)

/** The card text: [name] on its own line, [details] and [note] below, [full] as one line. */
data class AlertCardText(val name: String, val details: String, val note: String?, val full: String)

/**
 * Builds "{name} · {household label} · Last visit {date} · ₹{amount}, {items} · Note: {notes}"
 * exactly, dropping empty parts cleanly (TASK-078). No match score is ever shown.
 */
object AlertCardFormatter {
    const val SEP = " · "

    fun format(p: AlertPayload, now: Instant, labels: AlertLabels = AlertLabels()): AlertCardText {
        val name = p.displayName.trim()
        val household = p.householdLabel?.trim().orEmpty()
        val lastVisit = Formatters.parseInstant(p.lastVisit)?.let {
            String.format(labels.locale, labels.lastVisit, Formatters.friendlyDate(it, now, labels.words, labels.locale))
        }.orEmpty()
        val purchase = purchaseText(p.lastPurchase?.amount, p.lastPurchase?.items)
        val note = p.notes?.trim()?.takeIf { it.isNotEmpty() }?.let { String.format(labels.locale, labels.note, it) }
        val details = listOf(household, lastVisit, purchase).filter { it.isNotEmpty() }.joinToString(SEP)
        val full = listOf(name, details, note.orEmpty()).filter { it.isNotEmpty() }.joinToString(SEP)
        return AlertCardText(name, details, note, full)
    }

    /** "₹20,000, gold bangles", "₹20,000", or "gold bangles". */
    fun purchaseText(amount: Double?, items: String?): String {
        val i = items?.trim().orEmpty()
        return when {
            amount != null && i.isNotEmpty() -> Formatters.rupees(amount) + ", " + i
            amount != null -> Formatters.rupees(amount)
            else -> i
        }
    }
}
