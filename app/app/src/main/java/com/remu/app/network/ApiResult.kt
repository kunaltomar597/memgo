package com.remu.app.network

import kotlinx.serialization.json.Json

/** The outcome of one call to the hub or the cloud. Screens map these to plain sentences. */
sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>

    /** The server answered with a non-2xx status. [error] is the contract's error code, if any. */
    data class HttpError(val status: Int, val error: String?, val body: String?) : ApiResult<Nothing>

    /** No answer: unreachable, timed out, or TLS failed for a reason other than the pin. */
    data object Unreachable : ApiResult<Nothing>

    /** The hub presented a certificate other than the one pinned at pairing. Pair again. */
    data object CertificateChanged : ApiResult<Nothing>

    /** No pairing, no session, or no configuration for this call. */
    data object NotConfigured : ApiResult<Nothing>
}

inline fun <T, R> ApiResult<T>.map(f: (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Ok -> ApiResult.Ok(f(value))
    is ApiResult.HttpError -> this
    ApiResult.Unreachable -> ApiResult.Unreachable
    ApiResult.CertificateChanged -> ApiResult.CertificateChanged
    ApiResult.NotConfigured -> ApiResult.NotConfigured
}

val <T> ApiResult<T>.valueOrNull: T? get() = (this as? ApiResult.Ok<T>)?.value

/** One JSON configuration for every request and response. Nulls are left out of bodies. */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
val RemuJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    isLenient = false
}
