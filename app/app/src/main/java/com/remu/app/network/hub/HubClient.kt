package com.remu.app.network.hub

import com.remu.app.network.ApiResult
import com.remu.app.network.RemuJson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.ConnectionPool
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.io.InputStream
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLPeerUnverifiedException

/** Where the hub is and how this phone proves itself to it. Kept in encrypted DataStore. */
data class HubConfig(val baseUrl: String, val token: String?, val certSha256: String?)

sealed interface PairOutcome {
    data class Paired(val response: PairResponse) : PairOutcome
    data object WrongCode : PairOutcome
    data object TooManyTries : PairOutcome
    data object CertificateMismatch : PairOutcome
    data object Unreachable : PairOutcome
    data object Failed : PairOutcome
}

/**
 * HubClient is the only class that talks to the hub (docs/RULES.md App rules): LAN HTTPS with
 * the certificate pinned at pairing, plus the WebSocket for events. Plain http is accepted only
 * for a development or test hub address that says http:// explicitly.
 */
class HubClient(
    private val baseHttp: OkHttpClient,
    private val config: () -> HubConfig?,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    @Volatile private var pinnedKey: String? = null
    @Volatile private var pinnedClient: OkHttpClient? = null

    /** Builds (and caches) the client that accepts only the stored fingerprint. */
    internal fun clientFor(cfg: HubConfig): OkHttpClient {
        val url = cfg.baseUrl.toHttpUrlOrNull()
        if (url == null || !url.isHttps) return baseHttp
        val pin = cfg.certSha256 ?: throw PinMismatchException()
        pinnedClient?.let { if (pinnedKey == pin) return it }
        val trust = FingerprintTrustManager(pin)
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
        val client = baseHttp.newBuilder()
            .sslSocketFactory(ssl.socketFactory, trust)
            .hostnameVerifier(PinnedHostnameVerifier(pin))
            .build()
        pinnedKey = pin
        pinnedClient = client
        return client
    }

    fun isConfigured(): Boolean = config()?.token != null

    // ---- Pairing ---------------------------------------------------------------------------

    /** POST /pair. On https the presented leaf must equal `cert_sha256` in the reply. */
    suspend fun pair(baseUrl: String, code: String): PairOutcome = withContext(io) {
        val url = baseUrl.toHttpUrlOrNull()?.resolve("pair") ?: return@withContext PairOutcome.Failed
        val trust = FirstContactTrustManager()
        val client = if (url.isHttps) {
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
            baseHttp.newBuilder()
                .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
                .sslSocketFactory(ssl.socketFactory, trust)
                .hostnameVerifier(FirstContactHostnameVerifier(trust))
                .build()
        } else baseHttp
        val body = RemuJson.encodeToString(PairRequest.serializer(), PairRequest(code.trim()))
        val request = Request.Builder().url(url).post(body.toRequestBody(jsonType)).build()
        try {
            client.newCall(request).execute().use { resp ->
                when {
                    resp.code == 401 -> PairOutcome.WrongCode
                    resp.code == 429 -> PairOutcome.TooManyTries
                    !resp.isSuccessful -> PairOutcome.Failed
                    else -> {
                        val parsed = RemuJson.decodeFromString(PairResponse.serializer(), resp.body?.string().orEmpty())
                        val leaf = trust.presentedLeaf
                        if (url.isHttps && (leaf == null || !CertFingerprint.matches(leaf, parsed.certSha256))) {
                            PairOutcome.CertificateMismatch
                        } else PairOutcome.Paired(parsed)
                    }
                }
            }
        } catch (e: IOException) {
            PairOutcome.Unreachable
        } catch (e: kotlinx.serialization.SerializationException) {
            PairOutcome.Failed
        }
    }

    // ---- Routes ----------------------------------------------------------------------------

    suspend fun health(): ApiResult<HubHealth> =
        call("GET", "health", null, HubHealth.serializer(), auth = false, timeoutMs = 5_000)

    suspend fun createInvite(): ApiResult<InviteResponse> =
        call("POST", "pair/invite", "{}", InviteResponse.serializer())

    suspend fun listStaff(): ApiResult<List<Staff>> =
        call("GET", "staff", null, ListSerializer(Staff.serializer()))

    suspend fun createStaff(req: StaffCreate): ApiResult<StaffCreated> =
        call("POST", "staff", RemuJson.encodeToString(StaffCreate.serializer(), req), StaffCreated.serializer())

    suspend fun verifyPin(pin: String): ApiResult<VerifiedStaff> =
        call("POST", "staff/verify-pin", RemuJson.encodeToString(VerifyPinRequest.serializer(), VerifyPinRequest(pin)), VerifiedStaff.serializer())

    /** POST /enrol. The body holds face crops; it lives in memory only and is never cached. */
    suspend fun enrol(req: EnrolRequest): ApiResult<EnrolResponse> =
        call("POST", "enrol", RemuJson.encodeToString(EnrolRequest.serializer(), req), EnrolResponse.serializer(), timeoutMs = ENROL_TIMEOUT_MS)

    suspend fun members(q: String? = null, phone: String? = null, status: String? = null): ApiResult<List<MemberSummary>> =
        call("GET", "members", null, ListSerializer(MemberSummary.serializer()),
            query = listOfNotNull(q?.let { "q" to it }, phone?.let { "phone" to it }, status?.let { "status" to it }))

    suspend fun member(id: String): ApiResult<MemberCard> =
        call("GET", "members/$id", null, MemberCard.serializer())

    suspend fun patchMember(id: String, patch: MemberPatch): ApiResult<Unit> =
        call("PATCH", "members/$id", RemuJson.encodeToString(MemberPatch.serializer(), patch), Unit.serializer())

    suspend fun removeMember(id: String, staffId: String): ApiResult<RemoveResponse> =
        call("POST", "members/$id/remove", RemuJson.encodeToString(RemoveRequest.serializer(), RemoveRequest(staffId)), RemoveResponse.serializer())

    suspend fun addPurchase(memberId: String, req: PurchaseCreate): ApiResult<Purchase> =
        call("POST", "members/$memberId/purchases", RemuJson.encodeToString(PurchaseCreate.serializer(), req), Purchase.serializer())

    suspend fun createHousehold(req: HouseholdCreate): ApiResult<IdResponse> =
        call("POST", "households", RemuJson.encodeToString(HouseholdCreate.serializer(), req), IdResponse.serializer())

    suspend fun patchHousehold(id: String, patch: HouseholdPatch): ApiResult<Unit> =
        call("PATCH", "households/$id", RemuJson.encodeToString(HouseholdPatch.serializer(), patch), Unit.serializer())

    suspend fun visits(memberId: String): ApiResult<List<Visit>> =
        call("GET", "visits", null, ListSerializer(Visit.serializer()), query = listOf("member_id" to memberId))

    suspend fun patchVisit(id: String, patch: VisitPatch): ApiResult<Unit> =
        call("PATCH", "visits/$id", RemuJson.encodeToString(VisitPatch.serializer(), patch), Unit.serializer())

    /** GET /clips/{id}: the 5-second clip, held in memory for playback and never saved. */
    suspend fun clip(id: String): ApiResult<ByteArray> = withContext(io) {
        val cfg = config() ?: return@withContext ApiResult.NotConfigured
        val url = urlFor(cfg, "clips/$id", emptyList()) ?: return@withContext ApiResult.NotConfigured
        execute(cfg, request(cfg, "GET", url, null, auth = true), 20_000) { resp -> resp.body?.bytes() ?: ByteArray(0) }
    }

    suspend fun discoverStreams(): ApiResult<DiscoverResponse> =
        call("GET", "discover", null, DiscoverResponse.serializer(), timeoutMs = 20_000)

    suspend fun saveGateStream(url: String): ApiResult<Unit> =
        call("PUT", "settings/streams", RemuJson.encodeToString(StreamSettings.serializer(), StreamSettings(url)), Unit.serializer())

    /**
     * GET /preview: the hub's low-resolution MJPEG aim preview. Emits one JPEG per frame, held in
     * memory only; the caller decodes it for display and drops it.
     */
    fun previewFrames(): Flow<ByteArray> = flow {
        val cfg = config() ?: return@flow
        val url = urlFor(cfg, "preview", emptyList()) ?: return@flow
        val client = clientFor(cfg).newBuilder().readTimeout(15, TimeUnit.SECONDS).callTimeout(0, TimeUnit.SECONDS).build()
        client.newCall(request(cfg, "GET", url, null, auth = true)).execute().use { resp ->
            if (!resp.isSuccessful) return@use
            val input = resp.body?.byteStream() ?: return@use
            val reader = MjpegReader(input)
            while (true) {
                val frame = reader.nextFrame() ?: break
                emit(frame)
            }
        }
    }.flowOn(io)

    /** Opens /ws. The token goes in the first message, never in the URL. */
    fun openEvents(listener: WebSocketListener): WebSocket? {
        val cfg = config() ?: return null
        cfg.token ?: return null
        val base = cfg.baseUrl.toHttpUrlOrNull() ?: return null
        val wsUrl = base.resolve("ws") ?: return null
        val client = try { clientFor(cfg) } catch (_: PinMismatchException) { return null }
        return client.newWebSocket(Request.Builder().url(wsUrl).build(), listener)
    }

    // ---- Plumbing --------------------------------------------------------------------------

    private suspend fun <T> call(
        method: String,
        path: String,
        body: String?,
        deserializer: DeserializationStrategy<T>,
        auth: Boolean = true,
        query: List<Pair<String, String>> = emptyList(),
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): ApiResult<T> = withContext(io) {
        val cfg = config() ?: return@withContext ApiResult.NotConfigured
        if (auth && cfg.token == null) return@withContext ApiResult.NotConfigured
        val url = urlFor(cfg, path, query) ?: return@withContext ApiResult.NotConfigured
        execute(cfg, request(cfg, method, url, body, auth), timeoutMs) { resp ->
            val text = resp.body?.string().orEmpty()
            @Suppress("UNCHECKED_CAST")
            if (deserializer == Unit.serializer()) Unit as T else RemuJson.decodeFromString(deserializer, text)
        }
    }

    private fun urlFor(cfg: HubConfig, path: String, query: List<Pair<String, String>>): HttpUrl? {
        val base = cfg.baseUrl.toHttpUrlOrNull() ?: return null
        val b = base.newBuilder().addPathSegments(path)
        query.forEach { (k, v) -> b.addQueryParameter(k, v) }
        return b.build()
    }

    private fun request(cfg: HubConfig, method: String, url: HttpUrl, body: String?, auth: Boolean): Request {
        val b = Request.Builder().url(url).header("Accept", "application/json")
        if (auth) cfg.token?.let { b.header("Authorization", "Bearer $it") }
        val rb = body?.toRequestBody(jsonType)
        when (method) {
            "GET" -> b.get()
            "POST" -> b.post(rb ?: "{}".toRequestBody(jsonType))
            "PATCH" -> b.patch(rb ?: "{}".toRequestBody(jsonType))
            "PUT" -> b.put(rb ?: "{}".toRequestBody(jsonType))
            else -> error("unsupported method")
        }
        return b.build()
    }

    private fun <T> execute(cfg: HubConfig, request: Request, timeoutMs: Long, parse: (Response) -> T): ApiResult<T> {
        return try {
            val client = clientFor(cfg)
            val call = client.newCall(request)
            call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
            call.execute().use { resp ->
                if (resp.isSuccessful) ApiResult.Ok(parse(resp))
                else {
                    val text = resp.body?.string()
                    ApiResult.HttpError(resp.code, errorCode(text), text)
                }
            }
        } catch (e: PinMismatchException) {
            ApiResult.CertificateChanged
        } catch (e: IOException) {
            if (isPinFailure(e)) ApiResult.CertificateChanged else ApiResult.Unreachable
        } catch (e: kotlinx.serialization.SerializationException) {
            ApiResult.HttpError(200, "invalid_response", null)
        }
    }

    private fun isPinFailure(e: Throwable): Boolean {
        var t: Throwable? = e
        while (t != null) {
            if (t is PinMismatchException || t is SSLPeerUnverifiedException) return true
            t = t.cause
        }
        return false
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 10_000L
        /** Make member waits at most this long; the camera stays live meanwhile (docs/DESIGN.md section 9). */
        const val ENROL_TIMEOUT_MS = 8_000L

        fun errorCode(body: String?): String? = try {
            body?.let { RemuJson.parseToJsonElement(it).jsonObject["error"]?.jsonPrimitive?.contentOrNull }
        } catch (_: Exception) {
            null
        }

        /** "192.168.1.20" -> "https://192.168.1.20:8443/". A URL with a scheme is kept as typed. */
        fun baseUrlFor(hostInput: String): String? {
            val t = hostInput.trim().trimEnd('/')
            if (t.isEmpty()) return null
            val withScheme = if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
            val url = withScheme.toHttpUrlOrNull() ?: return null
            val port = if (t.startsWith("http")) url.port else if (Regex(":[0-9]+$").containsMatchIn(t)) url.port else 8443
            return url.newBuilder().port(port).encodedPath("/").build().toString()
        }
    }
}

/** Splits a multipart MJPEG stream into JPEG frames by their SOI and EOI markers. */
internal class MjpegReader(private val input: InputStream, private val maxFrame: Int = 2_000_000) {
    fun nextFrame(): ByteArray? {
        var prev = -1
        while (true) {
            val b = input.read()
            if (b < 0) return null
            if (prev == 0xFF && b == 0xD8) break
            prev = b
        }
        val out = java.io.ByteArrayOutputStream()
        out.write(0xFF); out.write(0xD8)
        prev = -1
        while (true) {
            val b = input.read()
            if (b < 0) return null
            out.write(b)
            if (prev == 0xFF && b == 0xD9) return out.toByteArray()
            if (out.size() > maxFrame) return null
            prev = b
        }
    }
}
