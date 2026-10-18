package com.remu.app.testsupport

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A scripted hub or cloud on MockWebServer. Routes are "METHOD /path" (no query); the newest
 * matching route wins, so a test can change an answer mid-test (for example, the hub going down).
 */
class MockServer {
    val server = MockWebServer()
    private val routes = CopyOnWriteArrayList<Pair<String, (RecordedRequest) -> MockResponse>>()
    val requests = CopyOnWriteArrayList<Recorded>()
    @Volatile var down: Boolean = false

    class Recorded(val method: String, val path: String, val query: String?, val body: String, val headers: okhttp3.Headers)

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                val body = request.body.readUtf8()
                requests += Recorded(request.method!!, url.encodedPath, url.encodedQuery, body, request.headers)
                if (down) return MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST)
                val key = "${request.method} ${url.encodedPath}"
                val handler = routes.lastOrNull { matches(it.first, key) }?.second
                    ?: return MockResponse().setResponseCode(404).setBody("""{"error":"not_found"}""")
                return handler(request)
            }
        }
        server.start()
    }

    private fun matches(pattern: String, key: String): Boolean {
        if (!pattern.contains("*")) return pattern == key
        val regex = Regex("^" + pattern.split("*").joinToString("[^/]+") { Regex.escape(it) } + "$")
        return regex.matches(key)
    }

    fun on(route: String, status: Int = 200, body: String = "{}", headers: Map<String, String> = emptyMap()) {
        routes += route to { _ ->
            MockResponse().setResponseCode(status).setBody(body).also { r ->
                r.setHeader("Content-Type", "application/json")
                headers.forEach { (k, v) -> r.setHeader(k, v) }
            }
        }
    }

    fun onRaw(route: String, handler: (RecordedRequest) -> MockResponse) { routes += route to handler }

    fun url(): String = server.url("/").toString()

    fun requestsTo(method: String, path: String): List<Recorded> = requests.filter { it.method == method && it.path == path }

    fun shutdown() = try { server.shutdown() } catch (_: Exception) { }

    companion object {
        const val HEALTH_OK = """{"ok":true,"version":"0.1.0","model_version":"buffalo_l:3f9a1c2d","uptime_s":3600,"gallery_size":42,"locked":false,"data_dir_encrypted":false,"internet":true,"outbox_pending":0,"outbox_failed":0,"clock_synced":true,"fps":6.8,"stream":"ok"}"""
        const val HEALTH_NO_INTERNET = """{"ok":true,"version":"0.1.0","gallery_size":42,"locked":false,"internet":false,"outbox_pending":3,"stream":"ok"}"""
    }
}
