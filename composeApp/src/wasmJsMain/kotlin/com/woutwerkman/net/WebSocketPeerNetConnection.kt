package com.woutwerkman.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.w3c.dom.MessageEvent
import org.w3c.dom.WebSocket

/**
 * A [PeerNetConnection] backed by a browser WebSocket to the host device.
 *
 * The web client is a thin client — it doesn't participate in gossip or
 * linearization. It receives [PeerNetState] updates from the host and
 * submits [PeerEvent]s through the host.
 */
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
internal class WebSocketPeerNetConnection private constructor(
    override val localId: String,
    private val ws: WebSocket,
    private val json: Json,
) : PeerNetConnection {

    private val _state = MutableStateFlow(PeerNetState())
    override val state: Flow<PeerNetState> = _state

    override suspend fun submitEvent(event: PeerEvent): Boolean {
        return try {
            val msg = json.encodeToString<WsMessage>(WsMessage.EventSubmission(event))
            ws.send(msg)
            true
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        /**
         * Connect to the host's WebSocket and run [block] with the resulting connection.
         * Cleans up the WebSocket when [block] completes or is cancelled.
         */
        suspend fun <T> connect(
            wsUrl: String,
            block: suspend (WebSocketPeerNetConnection) -> T,
        ): T {
            val json = Json { ignoreUnknownKeys = true }
            val identityReceived = CompletableDeferred<WsMessage.Identity>()
            val state = MutableStateFlow(PeerNetState())

            val ws = WebSocket(wsUrl)

            ws.onmessage = { event ->
                val messageEvent = event.unsafeCast<MessageEvent>()
                val text = messageEvent.data.toString()
                try {
                    when (val msg = json.decodeFromString<WsMessage>(text)) {
                        is WsMessage.Identity -> identityReceived.complete(msg)
                        is WsMessage.StateUpdate -> state.value = msg.state
                        else -> {}
                    }
                } catch (_: Exception) {
                    // Ignore malformed messages
                }
            }

            val openDeferred = CompletableDeferred<Unit>()
            ws.onopen = { openDeferred.complete(Unit) }
            ws.onerror = { openDeferred.completeExceptionally(RuntimeException("WebSocket connection failed")) }

            try {
                openDeferred.await()
                val identity = identityReceived.await()

                val connection = WebSocketPeerNetConnection(identity.localId, ws, json)
                connection._state.value = state.value

                return coroutineScope {
                    launch {
                        state.collect { connection._state.value = it }
                    }
                    val result = async { block(connection) }
                    result.await()
                }
            } finally {
                ws.close()
            }
        }
    }
}
