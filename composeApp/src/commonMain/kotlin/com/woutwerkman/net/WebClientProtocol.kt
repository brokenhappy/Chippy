package com.woutwerkman.net

import kotlinx.serialization.Serializable

/**
 * Messages exchanged between a device-hosted WebSocket server and browser clients.
 */
@Serializable
sealed class WsMessage {
    /** Sent by client as the first message on a new connection. */
    @Serializable
    data class Hello(val playerName: String = "Web Player") : WsMessage()

    /** Sent by host after receiving Hello/Reconnect, with the client's identity. */
    @Serializable
    data class Identity(
        val localId: String,
        val hostId: String,
        val peers: List<WebPeer> = emptyList(),
    ) : WsMessage()

    /** Sent by host whenever PeerNetState changes. */
    @Serializable
    data class StateUpdate(val state: PeerNetState) : WsMessage()

    /** Sent by client to submit a PeerEvent through the host. */
    @Serializable
    data class EventSubmission(val event: PeerEvent) : WsMessage()

    /** Sent by client as the first message to resume an existing session on a new host. */
    @Serializable
    data class Reconnect(
        val localId: String,
        val playerName: String = "Web Player",
    ) : WsMessage()
}

/** Address of another host's web server, for reconnection. */
@Serializable
data class WebPeer(val address: String, val wsPort: Int)
