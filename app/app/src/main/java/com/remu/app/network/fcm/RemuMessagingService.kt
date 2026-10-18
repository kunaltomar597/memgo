package com.remu.app.network.fcm

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.remu.app.RemuApp
import com.remu.app.data.alerts.FcmPush
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Receives the alert push. Reads only `shop_id`, `visit_id` and `member_id`, fetches the card
 * (hub on the shop WiFi, cloud otherwise) and builds the notification on the phone.
 */
class RemuMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        val c = (application as? RemuApp)?.container ?: return
        runBlocking { withTimeoutOrNull(10_000) { c.fcm.onToken(token) } }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val push = FcmPush.from(message.data) ?: return
        val c = (application as? RemuApp)?.container ?: return
        runBlocking {
            c.session.awaitLoaded()
            withTimeoutOrNull(9_000) { c.ingest.onPush(push) }
        }
    }
}
