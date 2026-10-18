package com.remu.app.network.fcm

import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.remu.app.AppContainer
import com.remu.app.network.ApiResult
import kotlinx.coroutines.launch

/**
 * Sends the FCM token to `POST /api/devices/fcm-token` on sign-in and on rotation (TASK-076).
 * Does nothing when the build has no google-services.json (Firebase not configured).
 */
class FcmRegistrar(private val c: AppContainer) {
    fun registerIfReady() {
        if (!c.firebaseEnabled) return
        if (FirebaseApp.getApps(c.appContext).isEmpty()) return
        try {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                if (token != null) c.scope.launch { onToken(token) }
            }
        } catch (_: IllegalStateException) {
            // Firebase not initialised in this build.
        }
    }

    /** Stores the token and sends it once per token while the owner is signed in. */
    suspend fun onToken(token: String) {
        val s = c.session.update { it.copy(fcmToken = token) }
        val shopId = s.shopId ?: return
        if (!s.ownerSignedIn || s.fcmTokenSentFor == token) return
        if (c.cloud.registerFcmToken(shopId, token) is ApiResult.Ok) {
            c.session.update { it.copy(fcmTokenSentFor = token) }
        }
    }
}
