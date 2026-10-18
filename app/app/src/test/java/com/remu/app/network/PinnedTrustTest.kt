package com.remu.app.network

import com.remu.app.network.hub.CertFingerprint
import com.remu.app.network.hub.HubClient
import com.remu.app.network.hub.HubConfig
import com.remu.app.network.hub.PairOutcome
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * TASK-041 and T-A-04: HTTPS to a self-signed hub accepts only the pinned leaf fingerprint; a
 * changed certificate is refused and reported so the user pairs again.
 */
class PinnedTrustTest {
    private val server = MockWebServer()
    private val hubCert = HeldCertificate.Builder().commonName("remu-hub.local").addSubjectAlternativeName("localhost").build()
    private val otherCert = HeldCertificate.Builder().commonName("impostor").addSubjectAlternativeName("localhost").build()

    @Before fun setUp() {
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(hubCert).build().sslSocketFactory(), false)
        server.start()
    }

    @After fun tearDown() = server.shutdown()

    private fun base() = server.url("/").toString()
    private val pin get() = CertFingerprint.sha256(hubCert.certificate)

    @Test fun fingerprintFormat() {
        val fp = CertFingerprint.sha256(hubCert.certificate)
        assertTrue(Regex("^([0-9A-F]{2}:){31}[0-9A-F]{2}$").matches(fp))
        assertTrue(CertFingerprint.matches(hubCert.certificate, fp.lowercase()))
        assertTrue(CertFingerprint.matches(hubCert.certificate, fp.replace(":", "")))
    }

    @Test fun pinnedCertificateIsAccepted() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"ok":true,"version":"0.1.0"}"""))
        val hub = HubClient(OkHttpClient(), { HubConfig(base(), "tok", pin) })
        val r = hub.health()
        assertTrue("expected Ok, got $r", r is ApiResult.Ok)
    }

    @Test fun changedCertificateIsRefused() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        val wrongPin = CertFingerprint.sha256(otherCert.certificate)
        val hub = HubClient(OkHttpClient(), { HubConfig(base(), "tok", wrongPin) })
        assertEquals(ApiResult.CertificateChanged, hub.health())
        assertEquals(0, server.requestCount)
    }

    @Test fun pairingChecksThePresentedCertificateAgainstTheReply() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"app_token":"t1","cert_sha256":"$pin","shop_id":"shop","role":"owner"}"""))
        val hub = HubClient(OkHttpClient(), { null })
        val ok = hub.pair(base(), "K7QM-2XPA")
        assertTrue("expected Paired, got $ok", ok is PairOutcome.Paired)
        assertEquals("""{"code":"K7QM-2XPA"}""", server.takeRequest().body.readUtf8())

        val other = CertFingerprint.sha256(otherCert.certificate)
        server.enqueue(MockResponse().setBody("""{"app_token":"t1","cert_sha256":"$other","shop_id":"shop","role":"owner"}"""))
        assertEquals(PairOutcome.CertificateMismatch, hub.pair(base(), "K7QM-2XPA"))
    }

    @Test fun wrongCodeAndRateLimit() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}"""))
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":"rate_limited"}"""))
        val hub = HubClient(OkHttpClient(), { null })
        assertEquals(PairOutcome.WrongCode, hub.pair(base(), "000000"))
        assertEquals(PairOutcome.TooManyTries, hub.pair(base(), "000000"))
    }

    @Test fun baseUrlFromHostInput() {
        assertEquals("https://192.168.1.20:8443/", HubClient.baseUrlFor("192.168.1.20"))
        assertEquals("https://remu-hub.local:8443/", HubClient.baseUrlFor(" remu-hub.local "))
        assertEquals("https://192.168.1.20:9443/", HubClient.baseUrlFor("192.168.1.20:9443"))
        assertEquals("http://10.0.2.2:8443/", HubClient.baseUrlFor("http://10.0.2.2:8443"))
        assertEquals(null, HubClient.baseUrlFor(""))
    }
}
