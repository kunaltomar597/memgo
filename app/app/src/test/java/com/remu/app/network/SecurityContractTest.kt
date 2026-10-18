package com.remu.app.network

import com.remu.app.feature.pairing.HubIdentity
import com.remu.app.feature.pairing.PairLink
import com.remu.app.feature.settings.TokensViewModel
import com.remu.app.network.cloud.CloudDevice
import com.remu.app.network.hub.EnrolRequest
import com.remu.app.network.hub.HouseholdCreate
import com.remu.app.network.hub.HouseholdPatch
import com.remu.app.network.hub.HubClient
import com.remu.app.network.hub.HubConfig
import com.remu.app.network.hub.HubHealth
import com.remu.app.network.hub.MemberPatch
import com.remu.app.network.hub.PairOutcome
import com.remu.app.network.hub.PurchaseCreate
import com.remu.app.network.hub.StaffCreate
import com.remu.app.network.hub.VisitPatch
import com.remu.app.testsupport.MockServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** docs/API_CONTRACT.md section 7 (TASK-112) at the HubClient level, against MockWebServer. */
class SecurityContractTest {
    private val hub = MockServer()
    @Volatile private var session: String? = "sess-1"
    @Volatile private var ownerData = true
    private val answers = CopyOnWriteArrayList<Triple<String, Int, String?>>()
    private val client = HubClient(
        OkHttpClient(), { HubConfig(hub.url(), "tok-1", null) },
        staffSession = { session }, ownerDataAllowed = { ownerData },
        onAnswer = { p, s, e -> answers += Triple(p, s, e) },
    )

    @After fun tearDown() = hub.shutdown()

    private fun header(method: String, path: String) = hub.requestsTo(method, path).last().headers[HubClient.STAFF_SESSION_HEADER]

    // ---- 7.1 Staff sessions --------------------------------------------------------------------

    @Test fun everyWriteRouteCarriesTheStaffSession() = runBlocking {
        listOf("POST /enrol", "POST /members/m_1/remove", "POST /members/m_1/purchases", "PATCH /members/m_1", "POST /households",
            "PATCH /households/h_1", "PATCH /visits/v_1", "POST /staff", "POST /pair/invite", "PUT /settings/streams").forEach { hub.on(it, 200, "{}") }
        hub.on("POST /enrol", 201, """{"members":[],"refused":[]}""")
        hub.on("POST /members/m_1/remove", 200, """{"status":"withdrawn"}""")
        hub.on("POST /members/m_1/purchases", 201, """{"id":"p_1"}""")
        hub.on("POST /households", 201, """{"id":"h_1"}""")
        hub.on("POST /staff", 201, """{"staff_id":"s_1"}""")
        hub.on("POST /pair/invite", 200, """{"invite_code":"483920"}""")
        client.enrol(EnrolRequest("s_1", "+919876543210", null, "B1", 1.0))
        client.removeMember("m_1", "s_1")
        client.addPurchase("m_1", PurchaseCreate("s_1", "B1", 1.0))
        client.patchMember("m_1", MemberPatch(notes = "x"))
        client.createHousehold(HouseholdCreate("Sharma family", listOf("m_1")))
        client.patchHousehold("h_1", HouseholdPatch(label = "x"))
        client.patchVisit("v_1", VisitPatch(greeted = true))
        client.createStaff(StaffCreate("Ravi", "cashier", "4821"))
        client.createInvite()
        client.saveGateStream("rtsp://dvr/ch1")
        listOf("POST" to "/enrol", "POST" to "/members/m_1/remove", "POST" to "/members/m_1/purchases", "PATCH" to "/members/m_1",
            "POST" to "/households", "PATCH" to "/households/h_1", "PATCH" to "/visits/v_1", "POST" to "/staff", "POST" to "/pair/invite",
            "PUT" to "/settings/streams").forEach { (m, p) -> assertEquals("$m $p", "sess-1", header(m, p)) }
    }

    @Test fun readsAndHealthNeverCarryTheStaffSession() = runBlocking {
        hub.on("GET /health", body = MockServer.HEALTH_OK)
        hub.on("GET /members", body = "[]")
        hub.on("GET /staff", body = "[]")
        hub.on("POST /staff/verify-pin", body = """{"staff_id":"s_1","name":"Ravi","role":"cashier","staff_session":"new"}""")
        client.health(); client.members(); client.listStaff()
        val v = client.verifyPin("4821") as ApiResult.Ok
        assertEquals("new", v.value.staffSession)
        listOf("GET" to "/health", "GET" to "/members", "GET" to "/staff", "POST" to "/staff/verify-pin").forEach { (m, p) -> assertNull("$m $p", header(m, p)) }
    }

    @Test fun firstOwnerStaffGoesWithoutASession() = runBlocking {
        session = null
        hub.on("POST /staff", 201, """{"staff_id":"s_owner"}""")
        assertTrue(client.createStaff(StaffCreate("Rajesh", "owner", "482199")) is ApiResult.Ok)
        assertNull(header("POST", "/staff"))
    }

    @Test fun staffSessionRequiredIsReportedWithItsCode() = runBlocking {
        hub.on("PATCH /members/m_1", 401, """{"error":"staff_session_required"}""")
        val r = client.patchMember("m_1", MemberPatch(notes = "x")) as ApiResult.HttpError
        assertEquals(HubClient.ERROR_STAFF_SESSION_REQUIRED, r.error)
        assertEquals(Triple("members/m_1", 401, "staff_session_required"), answers.last())
    }

    // ---- 7.2 Owner-only data -------------------------------------------------------------------

    @Test fun clipsAndWebSocketStayClosedOutsideOwnerMode() = runBlocking {
        ownerData = false
        assertEquals(ApiResult.NotConfigured, client.clip("k_1"))
        assertNull(client.openEvents(object : okhttp3.WebSocketListener() {}))
        assertTrue(hub.requests.none { it.path.startsWith("/clips") || it.path == "/ws" })
    }

    @Test fun clipForbiddenIsAnHttpError() = runBlocking {
        hub.on("GET /clips/k_1", 403, """{"error":"forbidden"}""")
        assertEquals(403, (client.clip("k_1") as ApiResult.HttpError).status)
    }

    // ---- 7.3 Tokens ----------------------------------------------------------------------------

    @Test fun tokensListAndRevoke() = runBlocking {
        hub.on("GET /tokens", body = """[{"id":"a1b2c3d4e5f6","role":"owner","created_at":"2026-10-18T10:00:00+05:30","revoked_at":null},{"id":"0f0f0f0f0f0f","role":"counter","created_at":"2026-10-18T11:00:00+05:30","revoked_at":null}]""")
        hub.on("POST /tokens/0f0f0f0f0f0f/revoke", 204, "")
        val list = (client.tokens() as ApiResult.Ok).value
        assertEquals(listOf("owner", "counter"), list.map { it.role })
        assertTrue(client.revokeToken("0f0f0f0f0f0f") is ApiResult.Ok)
        assertEquals("Bearer tok-1", hub.requestsTo("POST", "/tokens/0f0f0f0f0f0f/revoke").single().headers["Authorization"])
        assertEquals(12, TokensViewModel.tokenId("tok-1").length)
        assertTrue(Regex("^[0-9a-f]{12}$").matches(TokensViewModel.tokenId("tok-1")))
    }

    // ---- 7.4 Certificate check -----------------------------------------------------------------

    @Test fun inviteLinkCarriesTheFingerprint() {
        val uri = PairLink("192.168.1.20", "483920", "AB:CD:EF").toUri()
        assertEquals("remu://pair?host=192.168.1.20&code=483920&fp=AB%3ACD%3AEF", uri)
        assertEquals(PairLink("192.168.1.20", "483920", "AB:CD:EF"), PairLink.parse(uri))
        assertNull(PairLink.parse("remu://pair?host=192.168.1.20&code=483920")!!.fp)
    }

    @Test fun counterPairingRefusesAHubWhoseCertificateDiffersFromTheQr() = runBlocking {
        hub.on("POST /pair", 200, """{"app_token":"t","cert_sha256":"EF:01","shop_id":"s","role":"counter"}""")
        val none = HubClient(OkHttpClient(), { null })
        assertEquals(PairOutcome.CertificateMismatch, none.pair(hub.url(), "483920", expectedFingerprint = "AB:CD"))
        assertTrue(none.pair(hub.url(), "483920", expectedFingerprint = "ef01") is PairOutcome.Paired)
        assertTrue(none.pair(hub.url(), "483920") is PairOutcome.Paired)
    }

    @Test fun pinnedFingerprintAgainstTheCloudDevices() {
        val ours = CloudDevice("d_1", certSha256 = "AB:CD")
        assertEquals(true, HubIdentity.certMatches("ab:cd", listOf(ours)))
        assertEquals(false, HubIdentity.certMatches("AB:CD", listOf(CloudDevice("d_1", certSha256 = "EF:01"))))
        // Nothing to compare with: neither a match nor a mismatch.
        assertNull(HubIdentity.certMatches("AB:CD", listOf(CloudDevice("d_1"))))
        assertNull(HubIdentity.certMatches("AB:CD", emptyList()))
        assertNull(HubIdentity.certMatches(null, listOf(ours)))
    }

    // ---- 7.6 Claimed shop ----------------------------------------------------------------------

    @Test fun claimedShopFromHealth() = runBlocking {
        hub.on("GET /health", body = MockServer.HEALTH_OK.dropLast(1) + ""","cloud_shop_id":"other-shop"}""")
        val h = (client.health() as ApiResult.Ok).value
        assertEquals("other-shop", h.cloudShopId)
        assertTrue(HubIdentity.claimedByOtherShop(h, "sharma-jewellers-jaipur"))
        assertFalse(HubIdentity.claimedByOtherShop(h, "other-shop"))
        assertFalse(HubIdentity.claimedByOtherShop(HubHealth(ok = true), "sharma-jewellers-jaipur"))
    }

    // ---- 7.7 Locked hub ------------------------------------------------------------------------

    @Test fun lockedIsReportedFromAnyRoute() = runBlocking {
        hub.on("GET /members", 423, """{"error":"locked"}""")
        val r = client.members() as ApiResult.HttpError
        assertEquals(423, r.status)
        assertEquals(HubClient.ERROR_LOCKED, r.error)
        assertEquals(Triple("members", 423, "locked"), answers.last())
    }
}
