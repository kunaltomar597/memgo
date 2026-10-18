package com.remu.app.feature.pairing

import com.remu.app.AppContainer
import com.remu.app.data.state.HubIdentityProblem
import com.remu.app.network.hub.HubClient
import com.remu.app.network.hub.PairOutcome

enum class PairError { HOST_INVALID, CODE_EMPTY, WRONG_CODE, TOO_MANY, CERT_MISMATCH, UNREACHABLE, FAILED, NOT_YOUR_HUB, OTHER_SHOP }

/**
 * The invite QR shown in Owner mode: remu://pair?host=<address>&code=<invite code>&fp=<fingerprint>.
 * [fp] is the hub certificate fingerprint the owner phone pinned; the counter phone refuses a hub
 * that presents any other certificate (docs/API_CONTRACT.md 7.4). Older codes have no `fp`.
 */
data class PairLink(val host: String, val code: String, val fp: String? = null) {
    fun toUri(): String = "remu://pair?host=" + enc(host) + "&code=" + enc(code) + (fp?.let { "&fp=" + enc(it) } ?: "")

    companion object {
        private fun enc(v: String) = java.net.URLEncoder.encode(v, "UTF-8")

        fun parse(text: String): PairLink? {
            if (!text.startsWith("remu://pair?")) return null
            val params = text.removePrefix("remu://pair?").split("&").mapNotNull {
                val i = it.indexOf('=')
                if (i <= 0) null else it.substring(0, i) to java.net.URLDecoder.decode(it.substring(i + 1), "UTF-8")
            }.toMap()
            val host = params["host"]?.takeIf { it.isNotBlank() } ?: return null
            val code = params["code"]?.takeIf { it.isNotBlank() } ?: return null
            return PairLink(host, code, params["fp"]?.takeIf { it.isNotBlank() })
        }
    }
}

/**
 * `POST /pair` and storing what it returns: the app token and the certificate fingerprint go
 * into the Keystore-encrypted DataStore; every later request pins that fingerprint (TASK-041).
 * An owner pairing then checks the hub is the shop's (HubIdentity) and unpairs when it is not.
 */
object HubPairing {
    suspend fun pair(c: AppContainer, hostInput: String, code: String, expectedFingerprint: String? = null): PairError? {
        val base = HubClient.baseUrlFor(hostInput) ?: return PairError.HOST_INVALID
        if (code.isBlank()) return PairError.CODE_EMPTY
        return when (val r = c.hub.pair(base, code.trim().uppercase(), expectedFingerprint)) {
            is PairOutcome.Paired -> {
                c.session.update {
                    it.copy(
                        hubBaseUrl = base, hubToken = r.response.appToken, certSha256 = r.response.certSha256,
                        hubRole = r.response.role, shopId = it.shopId ?: r.response.shopId,
                    )
                }
                c.hubStatus.check()
                val problem = if (r.response.role == "owner") HubIdentity.checkAfterOwnerPairing(c) else null
                when (problem) {
                    null -> { c.appState.setHubIdentity(null); null }
                    HubIdentityProblem.NOT_YOUR_HUB -> PairError.NOT_YOUR_HUB
                    HubIdentityProblem.OTHER_SHOP -> PairError.OTHER_SHOP
                }
            }
            PairOutcome.WrongCode -> PairError.WRONG_CODE
            PairOutcome.TooManyTries -> PairError.TOO_MANY
            PairOutcome.CertificateMismatch -> PairError.CERT_MISMATCH
            PairOutcome.Unreachable -> PairError.UNREACHABLE
            PairOutcome.Failed -> PairError.FAILED
        }
    }
}
