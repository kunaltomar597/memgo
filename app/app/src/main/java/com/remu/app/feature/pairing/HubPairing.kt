package com.remu.app.feature.pairing

import com.remu.app.AppContainer
import com.remu.app.network.hub.HubClient
import com.remu.app.network.hub.PairOutcome

enum class PairError { HOST_INVALID, CODE_EMPTY, WRONG_CODE, TOO_MANY, CERT_MISMATCH, UNREACHABLE, FAILED }

/** The invite QR shown in Owner mode: remu://pair?host=<address>&code=<invite code>. */
data class PairLink(val host: String, val code: String) {
    fun toUri(): String = "remu://pair?host=" + java.net.URLEncoder.encode(host, "UTF-8") + "&code=" + java.net.URLEncoder.encode(code, "UTF-8")

    companion object {
        fun parse(text: String): PairLink? {
            if (!text.startsWith("remu://pair?")) return null
            val params = text.removePrefix("remu://pair?").split("&").mapNotNull {
                val i = it.indexOf('=')
                if (i <= 0) null else it.substring(0, i) to java.net.URLDecoder.decode(it.substring(i + 1), "UTF-8")
            }.toMap()
            val host = params["host"]?.takeIf { it.isNotBlank() } ?: return null
            val code = params["code"]?.takeIf { it.isNotBlank() } ?: return null
            return PairLink(host, code)
        }
    }
}

/**
 * `POST /pair` and storing what it returns: the app token and the certificate fingerprint go
 * into the Keystore-encrypted DataStore; every later request pins that fingerprint (TASK-041).
 */
object HubPairing {
    suspend fun pair(c: AppContainer, hostInput: String, code: String): PairError? {
        val base = HubClient.baseUrlFor(hostInput) ?: return PairError.HOST_INVALID
        if (code.isBlank()) return PairError.CODE_EMPTY
        return when (val r = c.hub.pair(base, code.trim().uppercase())) {
            is PairOutcome.Paired -> {
                c.session.update {
                    it.copy(
                        hubBaseUrl = base, hubToken = r.response.appToken, certSha256 = r.response.certSha256,
                        hubRole = r.response.role, shopId = it.shopId ?: r.response.shopId,
                    )
                }
                c.hubStatus.check()
                null
            }
            PairOutcome.WrongCode -> PairError.WRONG_CODE
            PairOutcome.TooManyTries -> PairError.TOO_MANY
            PairOutcome.CertificateMismatch -> PairError.CERT_MISMATCH
            PairOutcome.Unreachable -> PairError.UNREACHABLE
            PairOutcome.Failed -> PairError.FAILED
        }
    }
}
