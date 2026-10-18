package com.remu.app.data.session

import com.remu.app.network.ApiResult
import com.remu.app.network.cloud.OwnerAuth
import com.remu.app.network.cloud.OwnerTokens

/** The owner's Supabase Auth session: sign-in, silent refresh, sign-out (TASK-075). */
class OwnerSessionManager(
    private val auth: OwnerAuth,
    private val session: SessionRepository,
    private val nowEpochS: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    val configured: Boolean get() = auth.configured

    suspend fun signIn(email: String, password: String): ApiResult<Unit> = when (val r = auth.signIn(email, password)) {
        is ApiResult.Ok -> { store(r.value); ApiResult.Ok(Unit) }
        is ApiResult.HttpError -> r
        ApiResult.Unreachable -> ApiResult.Unreachable
        ApiResult.CertificateChanged -> ApiResult.CertificateChanged
        ApiResult.NotConfigured -> ApiResult.NotConfigured
    }

    /** A valid access token, refreshed when it expires within a minute; null when signed out. */
    suspend fun accessToken(): String? {
        val s = session.current
        val access = s.ownerAccessToken ?: return null
        val refresh = s.ownerRefreshToken ?: return null
        val exp = s.ownerExpiresAtEpochS ?: 0
        if (exp - nowEpochS() > 60) return access
        return when (val r = auth.refresh(refresh)) {
            is ApiResult.Ok -> { store(r.value); r.value.accessToken }
            is ApiResult.HttpError -> {
                if (r.status == 400 || r.status == 401) clear()
                null
            }
            else -> access // offline: try the old token; the cloud is unreachable anyway
        }
    }

    suspend fun signOut() {
        session.current.ownerAccessToken?.let { auth.signOut(it) }
        clear()
    }

    private suspend fun store(t: OwnerTokens) {
        session.update {
            it.copy(
                ownerAccessToken = t.accessToken, ownerRefreshToken = t.refreshToken,
                ownerExpiresAtEpochS = t.expiresAtEpochS, ownerUserId = t.userId, ownerEmail = t.email,
            )
        }
    }

    private suspend fun clear() {
        session.update {
            it.copy(ownerAccessToken = null, ownerRefreshToken = null, ownerExpiresAtEpochS = null, ownerUserId = null, ownerEmail = null, fcmTokenSentFor = null)
        }
    }
}
