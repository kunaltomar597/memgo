package com.remu.app.network

import com.remu.app.data.session.InMemorySessionStore
import com.remu.app.data.session.OwnerSessionManager
import com.remu.app.data.session.SessionRepository
import com.remu.app.data.session.StoredSession
import com.remu.app.network.cloud.CloudClient
import com.remu.app.network.cloud.FeedbackRequest
import com.remu.app.network.cloud.ShopCreate
import com.remu.app.network.cloud.SupabaseOwnerAuth
import com.remu.app.testsupport.FakeOwnerAuth
import com.remu.app.testsupport.MockServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudClientTest {
    private val cloud = MockServer()
    @After fun tearDown() = cloud.shutdown()

    @Test fun supabasePasswordSignIn() = runBlocking {
        cloud.on("POST /auth/v1/token", body = """{"access_token":"a1","token_type":"bearer","expires_in":3600,"refresh_token":"r1","user":{"id":"u_1","email":"o@x.in"}}""")
        val auth = SupabaseOwnerAuth(OkHttpClient(), cloud.url(), "anon-key", nowEpochS = { 1000 })
        val r = auth.signIn(" o@x.in ", "secret") as ApiResult.Ok
        assertEquals("a1", r.value.accessToken)
        assertEquals(4600, r.value.expiresAtEpochS)
        val req = cloud.requests.single()
        assertEquals("grant_type=password", req.query)
        assertEquals("anon-key", req.headers["apikey"])
        assertEquals("""{"email":"o@x.in","password":"secret"}""", req.body)
    }

    @Test fun wrongPasswordIsAnHttpError() = runBlocking {
        cloud.on("POST /auth/v1/token", 400, """{"error":"invalid_grant"}""")
        val auth = SupabaseOwnerAuth(OkHttpClient(), cloud.url(), "anon-key")
        assertTrue(auth.signIn("o@x.in", "bad") is ApiResult.HttpError)
    }

    @Test fun notConfiguredWithoutAnonKey() = runBlocking {
        val auth = SupabaseOwnerAuth(OkHttpClient(), cloud.url(), "")
        assertEquals(ApiResult.NotConfigured, auth.signIn("a", "b"))
    }

    @Test fun cloudRoutesCarryTheSupabaseToken() = runBlocking {
        cloud.on("POST /api/shops", 201, """{"id":"sharma-jewellers-jaipur"}""")
        cloud.on("POST /api/visits/v_1/feedback", 204, "")
        cloud.on("POST /api/shops/shop/consent-export", 200, "id,shop_id\n", mapOf("X-Remu-Signature" to "c2ln"))
        val client = CloudClient(OkHttpClient(), cloud.url(), { "access-9" })
        assertEquals("sharma-jewellers-jaipur", (client.createShop(ShopCreate("Sharma Jewellers", "Jaipur", "hi")) as ApiResult.Ok).value.id)
        assertTrue(client.visitFeedback("v_1", FeedbackRequest(greeted = true, greetedBy = "s_1")) is ApiResult.Ok)
        val export = (client.consentExport("shop", "csv") as ApiResult.Ok).value
        assertEquals("c2ln", export.signature)
        assertEquals("format=csv", cloud.requestsTo("POST", "/api/shops/shop/consent-export").single().query)
        assertTrue(cloud.requests.all { it.headers["Authorization"] == "Bearer access-9" })
        assertEquals("""{"name":"Sharma Jewellers","city":"Jaipur","notice_language":"hi"}""", cloud.requestsTo("POST", "/api/shops").single().body)
        assertEquals("""{"greeted":true,"greeted_by":"s_1"}""", cloud.requestsTo("POST", "/api/visits/v_1/feedback").single().body)
    }

    @Test fun ownerSessionRefreshesNearExpiryAndClearsOnRejection() = runBlocking {
        val now = 10_000L
        val session = SessionRepository(InMemorySessionStore(StoredSession(ownerAccessToken = "old", ownerRefreshToken = "r0", ownerExpiresAtEpochS = now + 30)), CoroutineScope(Dispatchers.Unconfined))
        session.awaitLoaded()
        val auth = FakeOwnerAuth()
        val owner = OwnerSessionManager(auth, session, nowEpochS = { now })
        assertEquals("access-1", owner.accessToken())
        assertEquals(1, auth.refreshes)
        session.update { it.copy(ownerExpiresAtEpochS = now + 10) }
        auth.ok = false
        assertNull(owner.accessToken())
        assertNull(session.current.ownerAccessToken)
    }
}
