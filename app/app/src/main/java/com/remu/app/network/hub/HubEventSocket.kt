package com.remu.app.network.hub

import com.remu.app.network.RemuJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** Reconnect delays: 1, 2, 4, 8, 16, then 30 seconds; reset after the hub says hello. */
class Backoff(private val baseMs: Long = 1_000, private val maxMs: Long = 30_000) {
    private var attempt = 0
    fun next(): Long {
        val d = (baseMs shl attempt.coerceAtMost(20)).coerceAtMost(maxMs)
        if (d < maxMs) attempt++
        return d
    }
    fun reset() { attempt = 0 }
}

/** One parsed server message from /ws. */
sealed interface HubEvent {
    data class Alert(val id: String?, val ts: String?, val payload: AlertPayload) : HubEvent
    data class Feedback(val id: String?, val payload: VisitFeedback) : HubEvent
    data class MemberUpdated(val id: String?, val payload: MemberSummary) : HubEvent
    data class Health(val id: String?, val payload: HubHealth) : HubEvent
    data class Other(val type: String, val id: String?) : HubEvent

    companion object {
        /** Parses one envelope; unknown or malformed messages return null and are ignored. */
        fun parse(text: String): Pair<WsEnvelope, HubEvent?>? {
            val env = try { RemuJson.decodeFromString(WsEnvelope.serializer(), text) } catch (_: Exception) { return null }
            val payload: JsonElement? = env.payload
            val event = try {
                when (env.type) {
                    "alert" -> payload?.let { Alert(env.id, env.ts, RemuJson.decodeFromJsonElement(AlertPayload.serializer(), it)) }
                    "visit_feedback" -> payload?.let { Feedback(env.id, RemuJson.decodeFromJsonElement(VisitFeedback.serializer(), it)) }
                    "member_updated" -> payload?.let { MemberUpdated(env.id, RemuJson.decodeFromJsonElement(MemberSummary.serializer(), it)) }
                    "health" -> payload?.let { Health(env.id, RemuJson.decodeFromJsonElement(HubHealth.serializer(), it)) }
                    else -> Other(env.type, env.id)
                }
            } catch (_: Exception) { null }
            return env to event
        }
    }
}

/**
 * Keeps Owner mode connected to the hub's /ws while paired and on the shop WiFi. Sends the token
 * and the last event id in the first message so the hub replays what was missed, reconnects with
 * [Backoff], and treats 45 seconds of silence (the hub pings every 20) as a dead connection.
 */
class HubEventSocket(
    private val hub: HubClient,
    private val scope: CoroutineScope,
    private val backoff: Backoff = Backoff(),
    private val silenceTimeoutMs: Long = 45_000,
    private val tokenProvider: () -> String?,
    /** The hub refused the upgrade with this HTTP status (403 for a counter token, 423 locked). */
    private val onRejected: (Int) -> Unit = {},
) {
    private val _events = MutableSharedFlow<HubEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<HubEvent> = _events

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    /** Number of connections opened, for tests and the device status screen. */
    @Volatile var connectionCount: Int = 0
        private set

    @Volatile var lastEventId: String? = null
        private set

    private var job: Job? = null

    /**
     * True after the hub answered 403: this token may not open /ws (docs/API_CONTRACT.md 7.2).
     * The socket stops retrying until [start] is called again.
     */
    @Volatile var forbidden: Boolean = false
        private set

    fun start() {
        if (job?.isActive == true) return
        forbidden = false
        job = scope.launch {
            while (isActive) {
                val token = tokenProvider()
                if (token == null) { delay(backoff.next()); continue }
                val closed = Channel<Unit>(Channel.CONFLATED)
                var rejectedWith: Int? = null
                val activity = Channel<Unit>(Channel.CONFLATED)
                val socket = hub.openEvents(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        // "since" is sent as an explicit null on the first connection (docs/API_CONTRACT.md 1).
                        val hello = buildJsonObject { put("token", token); put("since", lastEventId) }.toString()
                        webSocket.send(hello)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        activity.trySend(Unit)
                        val (env, event) = HubEvent.parse(text) ?: return
                        when (env.type) {
                            "hello" -> { backoff.reset(); _connected.value = true }
                            "ping" -> Unit
                            else -> {
                                env.id?.let { lastEventId = it }
                                event?.let { _events.tryEmit(it) }
                            }
                        }
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(1000, null)
                        closed.trySend(Unit)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.trySend(Unit) }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        response?.code?.takeIf { it in 400..499 }?.let { rejectedWith = it }
                        closed.trySend(Unit)
                    }
                })
                if (socket == null) { delay(backoff.next()); continue }
                connectionCount++
                try {
                    while (isActive) {
                        val ended = withTimeoutOrNull(silenceTimeoutMs) {
                            kotlinx.coroutines.selects.select<Boolean> {
                                closed.onReceive { true }
                                activity.onReceive { false }
                            }
                        }
                        if (ended == null || ended) break
                    }
                } finally {
                    socket.cancel()
                    _connected.value = false
                }
                rejectedWith?.let { code ->
                    onRejected(code)
                    if (code == 403) { forbidden = true; return@launch }
                }
                delay(backoff.next())
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _connected.value = false
    }
}
