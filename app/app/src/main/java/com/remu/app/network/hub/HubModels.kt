package com.remu.app.network.hub

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// Shapes from docs/API_CONTRACT.md section 1. The contract wins over every other document.

@Serializable
data class HubHealth(
    val ok: Boolean = false,
    val version: String? = null,
    @SerialName("model_version") val modelVersion: String? = null,
    @SerialName("uptime_s") val uptimeS: Long? = null,
    @SerialName("gallery_size") val gallerySize: Int? = null,
    val locked: Boolean = false,
    @SerialName("data_dir_encrypted") val dataDirEncrypted: Boolean? = null,
    val internet: Boolean = true,
    @SerialName("outbox_pending") val outboxPending: Int = 0,
    @SerialName("outbox_failed") val outboxFailed: Int = 0,
    @SerialName("clock_synced") val clockSynced: Boolean? = null,
    val fps: Double? = null,
    val stream: String? = null,
    /** Null until the hub is claimed, then the cloud shop id (docs/API_CONTRACT.md 7.6). */
    @SerialName("cloud_shop_id") val cloudShopId: String? = null,
)

@Serializable data class PairRequest(val code: String)

@Serializable
data class PairResponse(
    @SerialName("app_token") val appToken: String,
    @SerialName("cert_sha256") val certSha256: String,
    @SerialName("shop_id") val shopId: String,
    val role: String,
)

@Serializable
data class InviteResponse(
    @SerialName("invite_code") val inviteCode: String,
    @SerialName("expires_at") val expiresAt: String? = null,
)

@Serializable data class StaffCreate(val name: String, val role: String, val pin: String)
@Serializable data class StaffCreated(@SerialName("staff_id") val staffId: String)

@Serializable
data class Staff(
    val id: String,
    val name: String,
    val role: String,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable data class VerifyPinRequest(val pin: String)

@Serializable
data class VerifiedStaff(
    @SerialName("staff_id") val staffId: String,
    val name: String,
    val role: String,
    /** Opaque, valid 12 hours; kept in memory only and sent on every hub write (contract 7.1). */
    @SerialName("staff_session") val staffSession: String? = null,
)

/** One app token from `GET /tokens` (owner). [id] is the first 12 hex characters of its hash. */
@Serializable
data class HubToken(
    val id: String,
    val role: String,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("revoked_at") val revokedAt: String? = null,
)

@Serializable
data class EnrolFace(
    @SerialName("crop_jpeg_b64") val cropJpegB64: String,
    val landmarks: List<List<Float>>,
    @SerialName("display_name") val displayName: String? = null,
    val phone: String? = null,
)

@Serializable
data class EnrolRequest(
    @SerialName("staff_id") val staffId: String,
    val phone: String,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("bill_no") val billNo: String,
    val amount: Double,
    val items: String? = null,
    val occasion: String? = null,
    val notes: String? = null,
    @SerialName("household_label") val householdLabel: String? = null,
    @SerialName("payer_index") val payerIndex: Int? = null,
    val faces: List<EnrolFace> = emptyList(),
)

@Serializable
data class EnrolledMember(
    @SerialName("face_index") val faceIndex: Int? = null,
    @SerialName("member_id") val memberId: String,
    val status: String,
    @SerialName("consent_record_id") val consentRecordId: String? = null,
)

@Serializable
data class RefusedFace(
    @SerialName("face_index") val faceIndex: Int,
    val reason: String,
)

@Serializable
data class EnrolResponse(
    @SerialName("phone_member") val phoneMember: Boolean = false,
    @SerialName("household_id") val householdId: String? = null,
    val members: List<EnrolledMember> = emptyList(),
    val refused: List<RefusedFace> = emptyList(),
)

/** Body of a 409 or 422 from POST /enrol. */
@Serializable
data class EnrolErrorBody(
    val error: String? = null,
    val detail: String? = null,
    @SerialName("member_id") val memberId: String? = null,
    val refused: List<RefusedFace> = emptyList(),
    /** With 422 `phone_required`: the face that needs its own phone (contract 7.5). */
    @SerialName("face_index") val faceIndex: Int? = null,
)

@Serializable
data class MemberSummary(
    val id: String,
    @SerialName("display_name") val displayName: String,
    val status: String,
    @SerialName("member_since") val memberSince: String? = null,
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
    @SerialName("household_label") val householdLabel: String? = null,
    @SerialName("has_template") val hasTemplate: Boolean = false,
    @SerialName("phone_masked") val phoneMasked: String? = null,
)

@Serializable
data class HouseholdRef(
    val id: String,
    val label: String,
    @SerialName("payer_member_id") val payerMemberId: String? = null,
    @SerialName("member_ids") val memberIds: List<String> = emptyList(),
)

@Serializable
data class Purchase(
    val id: String,
    @SerialName("bill_no") val billNo: String? = null,
    val amount: Double? = null,
    val items: String? = null,
    val occasion: String? = null,
    val ts: String? = null,
)

@Serializable
data class MemberCard(
    val id: String,
    @SerialName("display_name") val displayName: String,
    val status: String,
    @SerialName("member_since") val memberSince: String? = null,
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
    @SerialName("household_label") val householdLabel: String? = null,
    @SerialName("has_template") val hasTemplate: Boolean = false,
    @SerialName("phone_masked") val phoneMasked: String? = null,
    val phone: String? = null,
    val notes: String? = null,
    @SerialName("discount_flag") val discountFlag: Boolean = false,
    val household: HouseholdRef? = null,
    val purchases: List<Purchase> = emptyList(),
    @SerialName("visits_count") val visitsCount: Int = 0,
) {
    fun summary() = MemberSummary(id, displayName, status, memberSince, lastSeenAt, householdLabel, hasTemplate, phoneMasked)
}

/** PATCH /members/{id}: only display_name and notes; any other key is 400. */
@Serializable
data class MemberPatch(
    @SerialName("display_name") val displayName: String? = null,
    val notes: String? = null,
)

@Serializable data class RemoveRequest(@SerialName("staff_id") val staffId: String)

@Serializable
data class RemoveResponse(
    val status: String,
    @SerialName("template_deleted_at") val templateDeletedAt: String? = null,
)

@Serializable
data class PurchaseCreate(
    @SerialName("staff_id") val staffId: String,
    @SerialName("bill_no") val billNo: String,
    val amount: Double,
    val items: String? = null,
    val occasion: String? = null,
)

@Serializable
data class HouseholdCreate(
    val label: String,
    @SerialName("member_ids") val memberIds: List<String>,
    @SerialName("payer_member_id") val payerMemberId: String? = null,
)

@Serializable data class IdResponse(val id: String)

@Serializable
data class HouseholdPatch(
    val label: String? = null,
    @SerialName("payer_member_id") val payerMemberId: String? = null,
    @SerialName("add_member_ids") val addMemberIds: List<String>? = null,
    @SerialName("remove_member_ids") val removeMemberIds: List<String>? = null,
)

@Serializable
data class Visit(
    val id: String,
    @SerialName("member_id") val memberId: String,
    val ts: String,
    val camera: String? = null,
    val score: Double? = null,
    @SerialName("frames_agreed") val framesAgreed: Int? = null,
    val greeted: Boolean = false,
    @SerialName("greeted_by") val greetedBy: String? = null,
    @SerialName("not_them") val notThem: Boolean = false,
    @SerialName("has_clip") val hasClip: Boolean = false,
    /** Not in the contract; used when the hub sends it. Otherwise the visit id names the clip. */
    @SerialName("clip_id") val clipId: String? = null,
)

@Serializable
data class VisitPatch(
    val greeted: Boolean? = null,
    @SerialName("greeted_by") val greetedBy: String? = null,
    @SerialName("not_them") val notThem: Boolean? = null,
)

@Serializable
data class LastPurchase(val amount: Double? = null, val items: String? = null)

/** The `alert` WebSocket payload, also returned by the cloud's GET /api/visits/{id}/card. */
@Serializable
data class AlertPayload(
    @SerialName("visit_id") val visitId: String,
    @SerialName("member_id") val memberId: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("household_label") val householdLabel: String? = null,
    @SerialName("last_visit") val lastVisit: String? = null,
    @SerialName("last_purchase") val lastPurchase: LastPurchase? = null,
    val notes: String? = null,
    val score: Double? = null,
    @SerialName("frames_agreed") val framesAgreed: Int? = null,
)

@Serializable
data class VisitFeedback(
    @SerialName("visit_id") val visitId: String,
    val greeted: Boolean? = null,
    @SerialName("greeted_by") val greetedBy: String? = null,
    @SerialName("not_them") val notThem: Boolean? = null,
)

/** Server message on /ws: {"type", "id", "ts", "payload"}. */
@Serializable
data class WsEnvelope(
    val type: String,
    val id: String? = null,
    val ts: String? = null,
    val payload: JsonElement? = null,
)

// Setup routes. Named in docs/ARCHITECTURE.md section 9 but not shaped by the contract; these
// shapes are assumptions recorded in app/README.md.

@Serializable
data class DiscoveredStream(
    val id: String? = null,
    val label: String? = null,
    val url: String,
)

@Serializable
data class DiscoverResponse(val streams: List<DiscoveredStream> = emptyList())

@Serializable
data class StreamSettings(@SerialName("gate_url") val gateUrl: String)
