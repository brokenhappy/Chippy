package com.woutwerkman.net

import kotlinx.serialization.Serializable

/**
 * Messages exchanged between a device-hosted WebSocket server and browser clients.
 */
@Serializable
sealed class WsMessage {
    /** Sent by host on connect (and periodically) with the client's identity and peer list. */
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

    /** Sent by client on reconnect to resume an existing session. */
    @Serializable
    data class Reconnect(val localId: String) : WsMessage()
}

/** Address of another host's web server, for reconnection. */
@Serializable
data class WebPeer(val address: String, val wsPort: Int)
