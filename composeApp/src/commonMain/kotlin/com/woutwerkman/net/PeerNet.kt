package com.woutwerkman.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * Public state of the peer network — the result of folding all linearized events.
 * Contains only shared/public data visible to all peers.
 */
data class PeerNetState(
    val discoveredPeers: Map<String, PeerInfo> = emptyMap(),
)

/**
 * A connection to the peer network with collective event linearization.
 *
 * All peers in the network agree on the same ordered sequence of events.
 * The [state] flow emits the current state derived by folding all committed events.
 */
interface PeerNetConnection {
    /** Our identity in the peer network. */
    val localId: String

    /** The current state, derived by folding all linearized events over the initial empty state. */
    val state: Flow<PeerNetState>

    /**
     * Submit an event to the peer network for linearization.
     *
     * The event is proposed to the current leader, who assigns it a sequence number
     * and broadcasts it to all peers. Returns true if the event was committed,
     * false if it was rejected or timed out.
     *
     * System events ([PeerEvent.Joined], [PeerEvent.Left]) are emitted internally
     * and should not be submitted by consumers.
     */
    suspend fun submitEvent(event: PeerEvent): Boolean
}

/**
 * Events in the peer network — both system events and application events.
 *
 * System events ([Joined], [Left]) are generated internally by the network layer.
 * Application events are submitted by consumers via [PeerNetConnection.submitEvent].
 */
@Serializable
sealed class PeerEvent {
    /** A peer has joined the network (system event, emitted internally). */
    @Serializable
    data class Joined(val peer: PeerInfo) : PeerEvent()

    /** A peer has left the network (system event, emitted internally). */
    @Serializable
    data class Left(val peerId: String) : PeerEvent()
}

/**
 * Establishes a connection to the peer network for discovering and communicating with other peers on the LAN.
 *
 * The connection handles all the complexity internally:
 * - Service advertisement and discovery via mDNS/Bonjour
 * - Bidirectional handshake verification
 * - Leader election (smallest peerId)
 * - Event linearization (all peers see events in the same order)
 * - Catch-up for late joiners
 * - Keep-alive and timeout management
 */
suspend fun <T> withPeerNetConnection(
    config: PeerNetConfig = PeerNetConfig(),
    block: suspend CoroutineScope.(PeerNetConnection) -> T,
): T = coroutineScope {
    withRawPeerNetConnection(config) { rawConn ->
        val engine = LinearizationEngine(rawConn, config.displayName)
        val engineJob = launch { engine.start() }

        val connection = object : PeerNetConnection {
            override val localId: String = rawConn.localPeerId
            override val state: StateFlow<PeerNetState> = engine.state
            override suspend fun submitEvent(event: PeerEvent): Boolean = engine.submitEvent(event)
        }
        try {
            block(connection)
        } finally {
            engineJob.cancel()
        }
    }
}
