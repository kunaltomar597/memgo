package com.remu.app.network.cloud

import com.remu.app.network.ApiResult
import com.remu.app.network.RemuJson
import com.remu.app.network.hub.AlertPayload
import com.remu.app.network.hub.HubClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * CloudClient is the only class that talks to the cloud at `REMU_CLOUD_URL` (docs/RULES.md App
 * rules). The Supabase access token from the owner's session goes in the Authorization header.
 */
class CloudClient(
    private val http: OkHttpClient,
    private val baseUrl: String,
    private val accessToken: suspend () -> String?,
    private val supabaseUrl: String = "",
    private val supabaseAnonKey: String = "",
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    suspend fun createShop(req: ShopCreate): ApiResult<ShopCreated> =
        call("POST", "api/shops", RemuJson.encodeToString(ShopCreate.serializer(), req), ShopCreated.serializer())

    suspend fun devices(shopId: String): ApiResult<List<CloudDevice>> =
        call("GET", "api/shops/$shopId/devices", null, ListSerializer(CloudDevice.serializer()))

    suspend fun pairDevice(shopId: String, code: String): ApiResult<DevicePairResponse> =
        call("POST", "api/devices/pair", RemuJson.encodeToString(DevicePairRequest.serializer(), DevicePairRequest(shopId, code.trim())), DevicePairResponse.serializer())

    suspend fun registerFcmToken(shopId: String, token: String): ApiResult<Unit> =
        call("POST", "api/devices/fcm-token", RemuJson.encodeToString(FcmTokenRequest.serializer(), FcmTokenRequest(shopId, token)), Unit.serializer())

    suspend fun visitCard(visitId: String): ApiResult<AlertPayload> =
        call("GET", "api/visits/$visitId/card", null, AlertPayload.serializer())

    suspend fun visitFeedback(visitId: String, req: FeedbackRequest): ApiResult<Unit> =
        call("POST", "api/visits/$visitId/feedback", RemuJson.encodeToString(FeedbackRequest.serializer(), req), Unit.serializer())

    suspend fun withdrawMember(memberId: String): ApiResult<Unit> =
        call("POST", "api/members/$memberId/withdraw", "{}", Unit.serializer())

    /** POST /api/shops/{id}/consent-export?format=csv|pdf; keeps the X-Remu-Signature header. */
    suspend fun consentExport(shopId: String, format: String): ApiResult<ExportFile> = withContext(io) {
        val url = url("api/shops/$shopId/consent-export", listOf("format" to format)) ?: return@withContext ApiResult.NotConfigured
        val token = accessToken() ?: return@withContext ApiResult.NotConfigured
        val req = Request.Builder().url(url).header("Authorization", "Bearer $token").post("{}".toRequestBody(jsonType)).build()
        execute(req, 60_000) { resp -> ExportFile(resp.body?.bytes() ?: ByteArray(0), resp.header("X-Remu-Signature"), resp.header("Content-Type")) }
    }

    /**
     * The owner's shops through Supabase PostgREST, scoped by RLS. Used only when this phone has a
     * cloud session but no shop id yet; the contract has no GET /api/shops (assumption, README).
     */
    suspend fun myShops(): ApiResult<List<ShopRow>> = withContext(io) {
        if (supabaseUrl.isBlank() || supabaseAnonKey.isBlank()) return@withContext ApiResult.NotConfigured
        val token = accessToken() ?: return@withContext ApiResult.NotConfigured
        val url = supabaseUrl.toHttpUrlOrNull()?.newBuilder()?.addPathSegments("rest/v1/shops")
            ?.addQueryParameter("select", "id,name,city")?.build() ?: return@withContext ApiResult.NotConfigured
        val req = Request.Builder().url(url).header("apikey", supabaseAnonKey).header("Authorization", "Bearer $token").get().build()
        execute(req, 10_000) { resp -> RemuJson.decodeFromString(ListSerializer(ShopRow.serializer()), resp.body?.string().orEmpty()) }
    }

    private suspend fun <T> call(method: String, path: String, body: String?, de: DeserializationStrategy<T>): ApiResult<T> = withContext(io) {
        val url = url(path, emptyList()) ?: return@withContext ApiResult.NotConfigured
        val token = accessToken() ?: return@withContext ApiResult.NotConfigured
        val b = Request.Builder().url(url).header("Authorization", "Bearer $token").header("Accept", "application/json")
        when (method) {
            "GET" -> b.get()
            "POST" -> b.post((body ?: "{}").toRequestBody(jsonType))
            else -> error("unsupported method")
        }
        execute(b.build(), 15_000) { resp ->
            @Suppress("UNCHECKED_CAST")
            if (de == Unit.serializer()) Unit as T else RemuJson.decodeFromString(de, resp.body?.string().orEmpty())
        }
    }

    private fun url(path: String, query: List<Pair<String, String>>): HttpUrl? {
        val base = baseUrl.toHttpUrlOrNull() ?: return null
        val b = base.newBuilder().addPathSegments(path)
        query.forEach { (k, v) -> b.addQueryParameter(k, v) }
        return b.build()
    }

    private fun <T> execute(req: Request, timeoutMs: Long, parse: (Response) -> T): ApiResult<T> = try {
        val call = http.newCall(req)
        call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
        call.execute().use { resp ->
            if (resp.isSuccessful) ApiResult.Ok(parse(resp))
            else {
                val text = resp.body?.string()
                ApiResult.HttpError(resp.code, HubClient.errorCode(text), text)
            }
        }
    } catch (e: IOException) {
        ApiResult.Unreachable
    } catch (e: kotlinx.serialization.SerializationException) {
        ApiResult.HttpError(200, "invalid_response", null)
    }
}
