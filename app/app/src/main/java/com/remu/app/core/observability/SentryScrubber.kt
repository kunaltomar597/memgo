package com.remu.app.core.observability

import io.sentry.Breadcrumb
import io.sentry.SentryEvent

/**
 * The before-send scrubber (TASK-118, docs/ARCHITECTURE.md section 15): drops every field whose
 * name contains phone, name, notes, items, vec, landmarks, image, crop, token or key, removes the
 * user and request, and blanks phone numbers, e-mail addresses and anything that looks like
 * binary (long base64) inside free text. Events carry ids only.
 */
object SentryScrubber {
    private val sensitiveKeyParts = listOf(
        "phone", "name", "note", "item", "vec", "landmark", "image", "crop", "token", "key",
        "pin", "email", "password", "jpeg", "template", "embedding", "clip", "amount", "occasion", "bill",
    )
    private val phone = Regex("(\\+?91[\\s-]?)?(?<![0-9])[6-9][0-9]{4}[\\s-]?[0-9]{5}(?![0-9])")
    private val email = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val base64 = Regex("[A-Za-z0-9+/_-]{120,}={0,2}")
    private val bearer = Regex("(?i)bearer\\s+[A-Za-z0-9._~+/=-]+")

    fun isSensitiveKey(key: String): Boolean {
        val k = key.lowercase()
        return sensitiveKeyParts.any { k.contains(it) }
    }

    fun scrubText(text: String?): String? {
        if (text == null) return null
        var t = bearer.replace(text, "Bearer [token]")
        t = base64.replace(t, "[binary]")
        t = email.replace(t, "[email]")
        t = phone.replace(t, "[phone]")
        return t
    }

    private fun scrubValue(value: Any?): Any? = when (value) {
        null -> null
        is String -> scrubText(value)
        is Number, is Boolean -> value
        is ByteArray -> "[binary]"
        is Map<*, *> -> value.entries
            .filter { (k, _) -> !(k is String && isSensitiveKey(k)) }
            .associate { (k, v) -> k to scrubValue(v) }
        is Collection<*> -> value.map { scrubValue(it) }
        else -> scrubText(value.toString())
    }

    fun scrub(event: SentryEvent): SentryEvent {
        event.user = null
        event.request = null
        event.serverName = null
        event.message?.let { m ->
            m.formatted = scrubText(m.formatted)
            m.message = scrubText(m.message)
            m.params = null
        }
        event.exceptions?.forEach { ex -> ex.value = scrubText(ex.value) }
        event.extras?.let { extras ->
            val cleaned = extras.entries.filter { !isSensitiveKey(it.key) }.associate { it.key to scrubValue(it.value) }
            extras.keys.toList().forEach { event.removeExtra(it) }
            cleaned.forEach { (k, v) -> if (v != null) event.setExtra(k, v) }
        }
        event.tags?.let { tags ->
            val cleaned = tags.entries.filter { !isSensitiveKey(it.key) }.associate { it.key to (scrubText(it.value) ?: "") }
            tags.keys.toList().forEach { event.removeTag(it) }
            cleaned.forEach { (k, v) -> event.setTag(k, v) }
        }
        event.contexts.device?.let { d -> d.name = null }
        event.breadcrumbs?.let { list ->
            event.breadcrumbs = list.mapNotNull { scrubBreadcrumb(it) }.toMutableList()
        }
        return event
    }

    fun scrubBreadcrumb(b: Breadcrumb): Breadcrumb? {
        b.message = scrubText(b.message)
        val keys = b.data.keys.toList()
        for (k in keys) {
            if (isSensitiveKey(k)) b.removeData(k) else b.setData(k, scrubValue(b.data[k]) ?: "")
        }
        return b
    }
}
