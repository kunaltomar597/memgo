package com.remu.app.data

import com.remu.app.data.alerts.AlertCards
import com.remu.app.data.alerts.AlertIngest
import com.remu.app.data.alerts.AlertStore
import com.remu.app.data.alerts.FcmPush
import com.remu.app.data.state.HubStatus
import com.remu.app.network.cloud.CloudClient
import com.remu.app.network.hub.HubClient
import com.remu.app.network.hub.HubConfig
import com.remu.app.network.hub.HubEvent
import com.remu.app.network.hub.MemberCard
import com.remu.app.network.hub.Purchase
import com.remu.app.network.hub.Visit
import com.remu.app.testsupport.MockServer
import com.remu.app.testsupport.RecordingNotifier
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TASK-076 and TASK-077: FCM reads ids only, fetches the card, and dedupes with the WebSocket. */
class AlertIngestTest {
    private val hub = MockServer()
    private val cloud = MockServer()
    private val store = AlertStore()
    private val notifier = RecordingNotifier()
    private var status = HubStatus(reachable = false)
    private val ingest = AlertIngest(
        store,
        HubClient(OkHttpClient(), { HubConfig(hub.url(), "t", null) }),
        CloudClient(OkHttpClient(), cloud.url(), { "a" }),
        { status }, { "shop_1" }, notifier,
    )

    @After fun tearDown() { hub.shutdown(); cloud.shutdown() }

    @Test fun payloadReadsOnlyTheThreeIds() {
        val p = FcmPush.from(mapOf("shop_id" to "shop_1", "visit_id" to "v_1", "member_id" to "m_1", "display_name" to "Rajesh", "notes" to "x"))!!
        assertEquals(FcmPush("shop_1", "v_1", "m_1"), p)
        assertNull(FcmPush.from(mapOf("visit_id" to "v_1", "member_id" to "m_1")))
        assertNull(FcmPush.from(mapOf("shop_id" to "s", "visit_id" to "v 1; drop", "member_id" to "m")))
    }

    @Test fun offLanTheCardComesFromTheCloud() = runBlocking {
        cloud.on("GET /api/visits/v_1/card", body = """{"visit_id":"v_1","member_id":"m_1","display_name":"Rajesh Sharma","household_label":"Sharma family","last_visit":"2025-10-12","last_purchase":{"amount":20000,"items":"Gold necklace"},"notes":null}""")
        assertTrue(ingest.onPush(FcmPush("shop_1", "v_1", "m_1")))
        assertEquals("Rajesh Sharma", store.get("v_1")!!.payload.displayName)
        assertEquals(1, notifier.shown.size)
        assertTrue(hub.requests.isEmpty())
    }

    @Test fun onLanTheCardIsBuiltFromTheHub() = runBlocking {
        status = HubStatus(reachable = true)
        hub.on("GET /members/m_1", body = """{"id":"m_1","display_name":"Rajesh Sharma","status":"active","member_since":"2025-10-12","household":{"id":"h_1","label":"Sharma family","payer_member_id":"m_1","member_ids":["m_1"]},"notes":"Kundan","purchases":[{"id":"p_1","bill_no":"B1","amount":20000,"items":"Gold necklace","ts":"2025-10-12T17:00:00+05:30"}]}""")
        hub.on("GET /visits", body = """[{"id":"v_1","member_id":"m_1","ts":"2026-10-18T11:00:00+05:30"},{"id":"v_0","member_id":"m_1","ts":"2026-03-01T11:00:00+05:30"}]""")
        assertTrue(ingest.onPush(FcmPush("shop_1", "v_1", "m_1")))
        val p = store.get("v_1")!!.payload
        assertEquals("Sharma family", p.householdLabel)
        assertEquals("2026-03-01T11:00:00+05:30", p.lastVisit)
        assertEquals(20000.0, p.lastPurchase!!.amount!!, 0.0)
        assertTrue(cloud.requests.isEmpty())
    }

    @Test fun sameVisitFromSocketThenPushShowsOnceAndNotifiesOnce() = runBlocking {
        val ev = HubEvent.parse("""{"type":"alert","id":"e_1","ts":"2026-10-18T17:42:11+05:30","payload":{"visit_id":"v_5","member_id":"m_5","display_name":"Asha"}}""")!!.second!!
        ingest.onSocketEvent(ev)
        ingest.onSocketEvent(ev)
        assertFalse(ingest.onPush(FcmPush("shop_1", "v_5", "m_5")))
        assertEquals(1, store.alerts.value.size)
        assertEquals(1, notifier.shown.size)
        assertTrue(cloud.requests.isEmpty())
    }

    @Test fun pushForAnotherShopIsIgnored() = runBlocking {
        assertFalse(ingest.onPush(FcmPush("other_shop", "v_1", "m_1")))
        assertTrue(cloud.requests.isEmpty())
    }

    @Test fun firstVisitFallsBackToMemberSince() {
        val card = MemberCard(id = "m", displayName = "A", status = "active", memberSince = "2026-10-01", purchases = listOf(Purchase("p", amount = 5.0, items = "x", ts = "2026-10-01T10:00:00+05:30")))
        val p = AlertCards.fromHub("v_1", card, listOf(Visit("v_1", "m", "2026-10-18T10:00:00+05:30")))
        assertEquals("2026-10-01", p.lastVisit)
    }
}
