package com.remu.app.data.alerts

import com.remu.app.core.format.Formatters
import com.remu.app.network.ApiResult
import com.remu.app.network.cloud.CloudClient
import com.remu.app.network.hub.AlertPayload
import com.remu.app.network.hub.HubClient
import com.remu.app.network.hub.HubEvent
import com.remu.app.network.hub.LastPurchase
import com.remu.app.network.hub.MemberCard
import com.remu.app.network.hub.Visit
import com.remu.app.data.state.HubStatus
import java.time.Instant

/** Shows an alert as a heads-up notification. Built locally; the push never carries the text. */
fun interface AlertNotifier {
    fun notify(item: AlertItem)
}

/**
 * An FCM data message. Only `shop_id`, `visit_id` and `member_id` are read; any other key is
 * ignored, because nothing else may pass through Google's push servers (docs/API_CONTRACT.md 4).
 */
data class FcmPush(val shopId: String, val visitId: String, val memberId: String) {
    companion object {
        private val ID = Regex("^[A-Za-z0-9_-]{1,64}$")

        fun from(data: Map<String, String>): FcmPush? {
            val shop = data["shop_id"] ?: return null
            val visit = data["visit_id"] ?: return null
            val member = data["member_id"] ?: return null
            if (!ID.matches(shop) || !ID.matches(visit) || !ID.matches(member)) return null
            return FcmPush(shop, visit, member)
        }
    }
}

/** Builds the alert card from hub data when only ids are known (an FCM push on the shop WiFi). */
object AlertCards {
    fun fromHub(visitId: String, card: MemberCard, visits: List<Visit>): AlertPayload {
        val previous = visits.filter { it.id != visitId }.maxByOrNull { Formatters.parseInstant(it.ts) ?: Instant.EPOCH }
        val lastPurchase = card.purchases.maxByOrNull { Formatters.parseInstant(it.ts) ?: Instant.EPOCH }
        return AlertPayload(
            visitId = visitId,
            memberId = card.id,
            displayName = card.displayName,
            householdLabel = card.household?.label ?: card.householdLabel,
            lastVisit = previous?.ts ?: card.memberSince,
            lastPurchase = lastPurchase?.let { LastPurchase(it.amount, it.items) },
            notes = card.notes,
        )
    }
}

/**
 * The one way an alert enters the app, from the WebSocket or from FCM. The same visit id from
 * both shows one card and one notification.
 */
class AlertIngest(
    private val store: AlertStore,
    private val hub: HubClient,
    private val cloud: CloudClient,
    private val hubStatus: () -> HubStatus,
    private val shopId: () -> String?,
    private val notifier: AlertNotifier,
) {
    fun onSocketEvent(event: HubEvent) {
        when (event) {
            is HubEvent.Alert -> {
                val at = Formatters.parseInstant(event.ts)
                if (store.add(event.payload, AlertSource.WEBSOCKET, at)) {
                    store.get(event.payload.visitId)?.let(notifier::notify)
                }
            }
            is HubEvent.Feedback -> {
                val f = event.payload
                when {
                    f.notThem == true -> store.setFeedback(f.visitId, Feedback.NOT_THEM)
                    f.greeted == true -> store.setFeedback(f.visitId, Feedback.GREETED)
                }
            }
            else -> Unit
        }
    }

    /** Fetches the card from the hub on the shop WiFi, from the cloud otherwise. */
    suspend fun onPush(push: FcmPush): Boolean {
        val expected = shopId()
        if (expected != null && expected != push.shopId) return false
        if (store.contains(push.visitId)) return false
        val payload = if (hubStatus().onLan) fromHub(push) ?: fromCloud(push) else fromCloud(push)
        payload ?: return false
        if (!store.add(payload, AlertSource.FCM)) return false
        store.get(payload.visitId)?.let(notifier::notify)
        return true
    }

    private suspend fun fromCloud(push: FcmPush): AlertPayload? =
        (cloud.visitCard(push.visitId) as? ApiResult.Ok)?.value?.copy(visitId = push.visitId)

    private suspend fun fromHub(push: FcmPush): AlertPayload? {
        val card = (hub.member(push.memberId) as? ApiResult.Ok)?.value ?: return null
        val visits = (hub.visits(push.memberId) as? ApiResult.Ok)?.value.orEmpty()
        return AlertCards.fromHub(push.visitId, card, visits)
    }
}
