package com.remu.app.data.alerts

import com.remu.app.core.format.Formatters
import com.remu.app.network.hub.AlertPayload
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import java.time.ZoneId

enum class AlertSource { WEBSOCKET, FCM }
enum class Feedback { GREETED, NOT_THEM }

/** One visit card. Keyed by visit id, so FCM and the WebSocket never show the same visit twice. */
data class AlertItem(
    val payload: AlertPayload,
    val receivedAt: Instant,
    val source: AlertSource,
    val feedback: Feedback? = null,
) {
    val visitId: String get() = payload.visitId
}

/**
 * Today's alerts in memory (the app has no database). De-duplicates by `visit_id` across FCM and
 * the WebSocket (TASK-077), keeps unactioned cards on top and newest first, and remembers the
 * visits "Not them" dismissed so a WebSocket replay does not bring them back.
 */
class AlertStore(private val clock: () -> Instant = Instant::now, private val zone: ZoneId = Formatters.IST) {
    private val _alerts = MutableStateFlow<List<AlertItem>>(emptyList())
    val alerts: StateFlow<List<AlertItem>> = _alerts

    private val seen = LinkedHashSet<String>()
    private val dismissed = HashSet<String>()

    /** Adds the alert unless this visit is already known. Returns true only for a new visit. */
    @Synchronized
    fun add(payload: AlertPayload, source: AlertSource, at: Instant? = null): Boolean {
        if (payload.visitId.isBlank()) return false
        if (!seen.add(payload.visitId)) return false
        if (seen.size > MAX_SEEN) seen.remove(seen.first())
        val item = AlertItem(payload, at ?: clock(), source)
        _alerts.value = sort(_alerts.value + item)
        return true
    }

    fun contains(visitId: String): Boolean = synchronized(this) { visitId in seen }

    @Synchronized
    fun setFeedback(visitId: String, feedback: Feedback) {
        if (feedback == Feedback.NOT_THEM) {
            dismissed += visitId
            _alerts.value = _alerts.value.filterNot { it.visitId == visitId }
            return
        }
        _alerts.value = sort(_alerts.value.map { if (it.visitId == visitId) it.copy(feedback = feedback) else it })
    }

    fun get(visitId: String): AlertItem? = _alerts.value.firstOrNull { it.visitId == visitId }

    /** Cards older than 10 minutes grey out (TASK-078), matching the hub's alert cooldown. */
    fun isStale(item: AlertItem, now: Instant = clock()): Boolean =
        now.toEpochMilli() - item.receivedAt.toEpochMilli() > STALE_MS

    data class Today(val visits: Int, val greeted: Int)

    fun today(now: Instant = clock()): Today {
        val day = now.atZone(zone).toLocalDate()
        val list = _alerts.value.filter { it.receivedAt.atZone(zone).toLocalDate() == day }
        return Today(list.size, list.count { it.feedback == Feedback.GREETED })
    }

    @Synchronized
    fun clear() { _alerts.value = emptyList(); seen.clear(); dismissed.clear() }

    private fun sort(list: List<AlertItem>) =
        list.sortedWith(compareBy<AlertItem> { it.feedback != null }.thenByDescending { it.receivedAt })

    companion object {
        const val STALE_MS = 10 * 60 * 1000L
        private const val MAX_SEEN = 2_000
    }
}
