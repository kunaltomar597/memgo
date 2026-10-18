package com.remu.app.network

import com.remu.app.network.hub.Backoff
import com.remu.app.network.hub.HubClient
import com.remu.app.network.hub.HubConfig
import com.remu.app.network.hub.HubEvent
import com.remu.app.network.hub.HubEventSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** TASK-077: the LAN WebSocket, reconnection with backoff and the `since` replay cursor. */
class HubEventSocketTest {
    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After fun tearDown() { scope.cancel(); server.shutdown() }

    @Test fun backoffDoublesToThirtySecondsAndResets() {
        val b = Backoff()
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 16000L, 30000L, 30000L), List(7) { b.next() })
        b.reset()
        assertEquals(1000L, b.next())
    }

    @Test fun parsesEnvelopes() {
        val alert = HubEvent.parse("""{"type":"alert","id":"e_1","ts":"2026-10-18T17:42:11+05:30","payload":{"visit_id":"v_1","member_id":"m_1","display_name":"Rajesh Sharma","household_label":"Sharma family","last_visit":"2025-10-12","last_purchase":{"amount":20000,"items":"Gold necklace"},"notes":"x","score":0.7,"frames_agreed":4}}""")!!
        val ev = alert.second as HubEvent.Alert
        assertEquals("v_1", ev.payload.visitId)
        assertEquals(20000.0, ev.payload.lastPurchase!!.amount!!, 0.0)
        assertTrue(HubEvent.parse("""{"type":"ping"}""")!!.second is HubEvent.Other)
        assertNull(HubEvent.parse("not json"))
        assertTrue(HubEvent.parse("""{"type":"visit_feedback","id":"e_2","payload":{"visit_id":"v_1","greeted":true,"greeted_by":"s_1","not_them":false}}""")!!.second is HubEvent.Feedback)
    }

    @Test fun sendsTokenAndSinceAndReconnects() = runBlocking {
        val hellos = LinkedBlockingQueue<String>()
        val sockets = CopyOnWriteArrayList<WebSocket>()
        fun upgrade() = MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { sockets += webSocket }
            override fun onMessage(webSocket: WebSocket, text: String) {
                hellos += text
                webSocket.send("""{"type":"hello","id":null,"ts":"2026-10-18T17:00:00+05:30","payload":{"shop_id":"s"}}""")
                if (sockets.size == 1) {
                    webSocket.send("""{"type":"alert","id":"e_7","ts":"2026-10-18T17:42:11+05:30","payload":{"visit_id":"v_7","member_id":"m_7","display_name":"Asha"}}""")
                    webSocket.close(1001, "restart")
                }
            }
        })
        server.enqueue(upgrade())
        server.enqueue(upgrade())
        server.start()
        val hub = HubClient(OkHttpClient(), { HubConfig(server.url("/").toString(), "tok", null) })
        val socket = HubEventSocket(hub, scope, Backoff(baseMs = 50, maxMs = 200), tokenProvider = { "tok" })
        socket.start()
        val got = withTimeout(5_000) { socket.events.first { it is HubEvent.Alert } } as HubEvent.Alert
        assertEquals("v_7", got.payload.visitId)
        val hello1 = hellos.poll(5, TimeUnit.SECONDS)!!
        assertTrue(hello1.contains("\"token\":\"tok\""))
        assertTrue(hello1, hello1.contains("\"since\":null"))
        val hello2 = hellos.poll(5, TimeUnit.SECONDS)!!
        assertTrue("second hello should carry since=e_7: $hello2", hello2.contains("\"since\":\"e_7\""))
        assertEquals(2, socket.connectionCount)
        assertTrue(server.takeRequest().path!!.endsWith("/ws"))
        socket.stop()
    }
}
