package com.remu.app.network

import com.remu.app.network.hub.EnrolErrorBody
import com.remu.app.network.hub.EnrolRequest
import com.remu.app.network.hub.HubClient
import com.remu.app.network.hub.HubConfig
import com.remu.app.network.hub.MemberPatch
import com.remu.app.network.hub.VisitPatch
import com.remu.app.testsupport.MockServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HubClientTest {
    private val hub = MockServer()
    private val client = HubClient(OkHttpClient(), { HubConfig(hub.url(), "tok-1", null) })

    @After fun tearDown() = hub.shutdown()

    @Test fun bearerTokenOnEveryRouteButHealth() = runBlocking {
        hub.on("GET /health", body = MockServer.HEALTH_OK)
        hub.on("GET /members", body = "[]")
        client.health()
        client.members(q = "Raj")
        assertEquals(null, hub.requestsTo("GET", "/health").single().headers["Authorization"])
        val m = hub.requestsTo("GET", "/members").single()
        assertEquals("Bearer tok-1", m.headers["Authorization"])
        assertEquals("q=Raj", m.query)
    }

    @Test fun enrolErrorsCarryTheContractBodies() = runBlocking {
        hub.on("POST /enrol", 409, """{"error":"conflict","member_id":"m_9"}""")
        val r = client.enrol(EnrolRequest("s_1", "+919876543210", null, "B1", 1.0)) as ApiResult.HttpError
        assertEquals(409, r.status)
        assertEquals("conflict", r.error)
        assertEquals("m_9", RemuJson.decodeFromString(EnrolErrorBody.serializer(), r.body!!).memberId)
    }

    @Test fun patchBodiesCarryOnlyTheChangedKeys() = runBlocking {
        hub.on("PATCH /members/m_1", 200, "{}")
        hub.on("PATCH /visits/v_1", 200, "{}")
        client.patchMember("m_1", MemberPatch(notes = "Likes kundan"))
        client.patchVisit("v_1", VisitPatch(greeted = true, greetedBy = "s_owner"))
        assertEquals("""{"notes":"Likes kundan"}""", hub.requestsTo("PATCH", "/members/m_1").single().body)
        assertEquals("""{"greeted":true,"greeted_by":"s_owner"}""", hub.requestsTo("PATCH", "/visits/v_1").single().body)
    }

    @Test fun unreachableHub() = runBlocking {
        hub.down = true
        assertEquals(ApiResult.Unreachable, client.health())
    }

    @Test fun notConfiguredWithoutPairing() = runBlocking {
        val none = HubClient(OkHttpClient(), { null })
        assertEquals(ApiResult.NotConfigured, none.members())
        assertTrue(!none.isConfigured())
    }
}
