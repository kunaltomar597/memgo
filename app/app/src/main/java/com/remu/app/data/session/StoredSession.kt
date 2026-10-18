package com.remu.app.data.session

import kotlinx.serialization.Serializable

/**
 * Everything the app keeps across restarts, as one encrypted DataStore value: the hub pairing,
 * the owner's cloud session, the chosen mode and per-phone preferences. No member data, no PIN.
 */
@Serializable
data class StoredSession(
    val hubBaseUrl: String? = null,
    val hubToken: String? = null,
    val certSha256: String? = null,
    val hubRole: String? = null,
    val shopId: String? = null,
    val shopName: String? = null,
    val shopCity: String? = null,
    val noticeLanguage: String? = null,
    val ownerAccessToken: String? = null,
    val ownerRefreshToken: String? = null,
    val ownerExpiresAtEpochS: Long? = null,
    val ownerUserId: String? = null,
    val ownerEmail: String? = null,
    /** Last owner staff id and name from a PIN login, used for greeted_by off the shop WiFi. */
    val ownerStaffId: String? = null,
    val ownerStaffName: String? = null,
    val lastMode: String? = null,
    val lastExportAt: String? = null,
    /** "Skip face, save bill only" taps, the pilot enrolment denominator (TASK-048, TASK-127). */
    val skipFaceCount: Int = 0,
    val useFrontCamera: Boolean = false,
    val alertTone: Boolean = false,
    val fcmToken: String? = null,
    val fcmTokenSentFor: String? = null,
    val wizard: WizardProgress? = null,
) {
    val paired: Boolean get() = hubBaseUrl != null && hubToken != null
    val ownerSignedIn: Boolean get() = ownerAccessToken != null && ownerRefreshToken != null
}

/** The setup wizard can be resumed at its step; elapsed time counts toward the 60-minute target. */
@Serializable
data class WizardProgress(
    val startedAtEpochMs: Long,
    val step: Int = 0,
    val completed: Boolean = false,
    val chosenStreamUrl: String? = null,
)
