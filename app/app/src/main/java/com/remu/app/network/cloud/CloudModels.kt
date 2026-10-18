package com.remu.app.network.cloud

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Shapes from docs/API_CONTRACT.md section 4.

@Serializable
data class ShopCreate(
    val name: String,
    val city: String,
    @SerialName("notice_language") val noticeLanguage: String,
)

@Serializable data class ShopCreated(val id: String)

@Serializable
data class ShopRow(val id: String, val name: String? = null, val city: String? = null)

@Serializable
data class CloudDevice(
    val id: String,
    @SerialName("hub_version") val hubVersion: String? = null,
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
    val online: Boolean = false,
    val locked: Boolean = false,
    @SerialName("chain_break") val chainBreak: Boolean = false,
)

@Serializable
data class DevicePairRequest(
    @SerialName("shop_id") val shopId: String,
    @SerialName("pairing_code") val pairingCode: String,
)

@Serializable data class DevicePairResponse(@SerialName("device_id") val deviceId: String)

@Serializable
data class FcmTokenRequest(
    @SerialName("shop_id") val shopId: String,
    @SerialName("fcm_token") val fcmToken: String,
)

@Serializable
data class FeedbackRequest(
    val greeted: Boolean? = null,
    @SerialName("greeted_by") val greetedBy: String? = null,
    @SerialName("not_them") val notThem: Boolean? = null,
)

/** A consent export file as returned by the cloud, held in memory until it is shared. */
class ExportFile(val bytes: ByteArray, val signature: String?, val contentType: String?)

// Supabase Auth REST (GoTrue) token response.
@Serializable
data class SupabaseUser(val id: String, val email: String? = null)

@Serializable
data class SupabaseTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Long = 3600,
    @SerialName("expires_at") val expiresAt: Long? = null,
    val user: SupabaseUser? = null,
)

@Serializable data class PasswordGrant(val email: String, val password: String)
@Serializable data class RefreshGrant(@SerialName("refresh_token") val refreshToken: String)
