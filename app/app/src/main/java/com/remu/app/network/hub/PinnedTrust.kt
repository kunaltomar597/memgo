package com.remu.app.network.hub

import android.annotation.SuppressLint
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSession
import javax.net.ssl.X509TrustManager

/** SHA-256 fingerprints in the hub's "AB:CD:..." form. */
object CertFingerprint {
    fun sha256(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(":") { "%02X".format(it) }

    /** Upper-case hex without separators, for comparison. */
    fun normalise(fingerprint: String): String =
        fingerprint.replace(":", "").replace(" ", "").removePrefix("sha256/").uppercase()

    fun matches(cert: X509Certificate, expected: String): Boolean {
        val a = normalise(sha256(cert)).toByteArray()
        val b = normalise(expected).toByteArray()
        return MessageDigest.isEqual(a, b)
    }
}

/** Thrown when the hub presents a certificate other than the pinned one. */
class PinMismatchException : CertificateException("hub certificate does not match the pinned fingerprint")

/**
 * Accepts only the hub's self-signed leaf whose SHA-256 fingerprint was stored at pairing.
 * Standard CertificatePinner is not enough because the hub certificate is not issued by any CA
 * in the Android trust store; this replaces chain validation with the exact leaf check.
 */
@SuppressLint("CustomX509TrustManager") // Deliberate: exact leaf pin for a self-signed hub; see KDoc.
class FingerprintTrustManager(private val expected: String) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("client certificates are not used")
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        if (chain.isNullOrEmpty()) throw CertificateException("empty certificate chain")
        val leaf = chain[0]
        leaf.checkValidity()
        if (!CertFingerprint.matches(leaf, expected)) throw PinMismatchException()
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/**
 * Pairing only: the first contact with a hub, before any fingerprint is known. Records the leaf
 * the hub presented so the client can check it against `cert_sha256` in the `POST /pair` reply
 * and refuse the pairing on a mismatch. Never used for any other request.
 */
@SuppressLint("CustomX509TrustManager") // Deliberate: first-contact capture, verified against cert_sha256.
class FirstContactTrustManager : X509TrustManager {
    @Volatile var presentedLeaf: X509Certificate? = null
        private set

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("client certificates are not used")
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        if (chain.isNullOrEmpty()) throw CertificateException("empty certificate chain")
        presentedLeaf = chain[0]
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/**
 * The hub certificate names `remu-hub.local` and the LAN address at first boot; the address can
 * change with DHCP. The leaf is already pinned, so the host check accepts a session only when its
 * leaf is the pinned certificate.
 */
class PinnedHostnameVerifier(private val expected: String) : HostnameVerifier {
    override fun verify(hostname: String?, session: SSLSession?): Boolean {
        val leaf = session?.peerCertificates?.firstOrNull() as? X509Certificate ?: return false
        return CertFingerprint.matches(leaf, expected)
    }
}

/** First contact: the host is checked after the reply, against `cert_sha256`. */
class FirstContactHostnameVerifier(private val trust: FirstContactTrustManager) : HostnameVerifier {
    override fun verify(hostname: String?, session: SSLSession?): Boolean {
        val leaf = session?.peerCertificates?.firstOrNull() as? X509Certificate ?: return false
        return leaf == trust.presentedLeaf
    }
}
