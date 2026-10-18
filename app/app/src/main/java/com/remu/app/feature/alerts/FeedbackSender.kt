package com.remu.app.feature.alerts

import com.remu.app.AppContainer
import com.remu.app.data.alerts.Feedback
import com.remu.app.network.ApiResult
import com.remu.app.network.cloud.FeedbackRequest
import com.remu.app.network.hub.VisitPatch

/**
 * "Greeted" sends greeted=true with greeted_by=<owner staff id>; "Not them" sends not_them=true
 * (TASK-079). On the shop WiFi it goes to the hub's PATCH /visits/{id}; off it, or when the hub
 * does not answer, to the cloud's POST /api/visits/{id}/feedback, which reaches the hub's inbox.
 */
class FeedbackSender(private val c: AppContainer) {
    enum class Route { HUB, CLOUD }

    suspend fun send(visitId: String, feedback: Feedback): Route? {
        val staffId = c.appState.staff.value?.staffId ?: c.session.current.ownerStaffId
        val greeted = feedback == Feedback.GREETED
        if (c.hubStatus.status.value.onLan) {
            val patch = if (greeted) VisitPatch(greeted = true, greetedBy = staffId) else VisitPatch(notThem = true)
            when (c.hub.patchVisit(visitId, patch)) {
                is ApiResult.Ok -> { c.alerts.setFeedback(visitId, feedback); return Route.HUB }
                ApiResult.Unreachable -> c.hubStatus.reportFailure()
                else -> return null
            }
        }
        val req = if (greeted) FeedbackRequest(greeted = true, greetedBy = staffId) else FeedbackRequest(notThem = true)
        return if (c.cloud.visitFeedback(visitId, req) is ApiResult.Ok) {
            c.alerts.setFeedback(visitId, feedback)
            Route.CLOUD
        } else null
    }
}
