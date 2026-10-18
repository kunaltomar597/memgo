package com.remu.app.network.cloud

import com.remu.app.network.ApiResult
import com.remu.app.network.RemuJson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** Tokens of the owner's Supabase Auth session. Stored encrypted; never logged. */
data class OwnerTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochS: Long,
    val userId: String?,
    val email: String?,
)

/** Owner sign-in. Behind an interface so tests use a fake instead of Supabase. */
interface OwnerAuth {
    val configured: Boolean
    suspend fun signIn(email: String, password: String): ApiResult<OwnerTokens>
    suspend fun refresh(refreshToken: String): ApiResult<OwnerTokens>
    suspend fun signOut(accessToken: String)
}

/**
 * Email and password sign-in through the Supabase Auth REST API (`/auth/v1/token`). Uses the
 * same OkHttp client as [CloudClient] with standard TLS validation, no pin (docs/SECURITY.md 3.2).
 */
class SupabaseOwnerAuth(
    private val http: OkHttpClient,
    private val supabaseUrl: String,
    private val anonKey: String,
    private val nowEpochS: () -> Long = { System.currentTimeMillis() / 1000 },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : OwnerAuth {
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    override val configured: Boolean get() = supabaseUrl.isNotBlank() && anonKey.isNotBlank()

    override suspend fun signIn(email: String, password: String): ApiResult<OwnerTokens> =
        token("password", RemuJson.encodeToString(PasswordGrant.serializer(), PasswordGrant(email.trim(), password)))

    override suspend fun refresh(refreshToken: String): ApiResult<OwnerTokens> =
        token("refresh_token", RemuJson.encodeToString(RefreshGrant.serializer(), RefreshGrant(refreshToken)))

    override suspend fun signOut(accessToken: String) {
        if (!configured) return
        withContext(io) {
            val url = supabaseUrl.toHttpUrlOrNull()?.newBuilder()?.addPathSegments("auth/v1/logout")?.build() ?: return@withContext
            val req = Request.Builder().url(url).header("apikey", anonKey).header("Authorization", "Bearer $accessToken")
                .post("{}".toRequestBody(jsonType)).build()
            try { http.newCall(req).execute().close() } catch (_: IOException) { }
        }
    }

    private suspend fun token(grant: String, body: String): ApiResult<OwnerTokens> = withContext(io) {
        if (!configured) return@withContext ApiResult.NotConfigured
        val url = supabaseUrl.toHttpUrlOrNull()?.newBuilder()?.addPathSegments("auth/v1/token")
            ?.addQueryParameter("grant_type", grant)?.build() ?: return@withContext ApiResult.NotConfigured
        val req = Request.Builder().url(url).header("apikey", anonKey).post(body.toRequestBody(jsonType)).build()
        try {
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@withContext ApiResult.HttpError(resp.code, null, null)
                val t = RemuJson.decodeFromString(SupabaseTokenResponse.serializer(), text)
                ApiResult.Ok(
                    OwnerTokens(t.accessToken, t.refreshToken, t.expiresAt ?: (nowEpochS() + t.expiresIn), t.user?.id, t.user?.email),
                )
            }
        } catch (e: IOException) {
            ApiResult.Unreachable
        } catch (e: kotlinx.serialization.SerializationException) {
            ApiResult.HttpError(200, "invalid_response", null)
        }
    }
}
