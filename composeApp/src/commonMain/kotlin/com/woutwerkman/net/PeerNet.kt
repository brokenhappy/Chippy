package com.woutwerkman.net

import com.woutwerkman.game.model.GamePhase
import com.woutwerkman.game.model.VoteChoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * Public state of the peer network — the result of folding all linearized events.
 * Contains ALL shared data visible to all peers: discovery, lobbies, game state, votes.
 */
data class PeerNetState(
    val discoveredPeers: Map<String, PeerInfo> = emptyMap(),
    val pendingInvites: List<Invite> = emptyList(),
    val lobby: LobbyInfo? = null,
    val gamePhase: GamePhase = GamePhase.WAITING,
    val playerValues: Map<String, Int> = emptyMap(),
    val votes: Map<String, VoteChoice> = emptyMap(),
)

/**
 * An open invite from one peer to another (not yet accepted/rejected).
 */
data class Invite(val fromId: String, val toId: String)

/**
 * Lobby information — active when players are grouped together.
 */
data class LobbyInfo(
    val lobbyId: String,
    val hostId: String,
    val players: Map<String, LobbyPlayer> = emptyMap(),
)

/**
 * A player in a lobby.
 */
data class LobbyPlayer(val name: String, val isReady: Boolean = false)

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
     * The event is broadcast to all peers with a timestamp for deterministic ordering.
     * Returns true once broadcast.
     *
     * System events ([PeerEvent.Joined], [PeerEvent.Left]) are emitted internally
     * and should not be submitted by consumers.
     */
    suspend fun submitEvent(event: PeerEvent): Boolean
}

/**
 * Events in the peer network — system events, lobby events, and game events.
 *
 * System events ([Joined], [Left]) are generated internally by the network layer.
 * All other events are submitted by consumers via [PeerNetConnection.submitEvent].
 */
@Serializable
sealed class PeerEvent {
    // System events (emitted internally by the net layer)
    @Serializable
    data class Joined(val peer: PeerInfo) : PeerEvent()
    @Serializable
    data class Left(val peerId: String) : PeerEvent()

    // Connection/lobby events (all public)
    @Serializable
    data class InviteSent(val fromId: String, val toId: String) : PeerEvent()
    @Serializable
    data class InviteAccepted(val fromId: String, val toId: String, val lobbyId: String) : PeerEvent()
    @Serializable
    data class InviteRejected(val fromId: String, val toId: String) : PeerEvent()
    @Serializable
    data class LobbyCreated(val lobbyId: String, val hostId: String) : PeerEvent()
    @Serializable
    data class JoinedLobby(val playerId: String) : PeerEvent()
    @Serializable
    data class LeftLobby(val playerId: String) : PeerEvent()

    // Game events
    @Serializable
    data class ReadyChanged(val playerId: String, val isReady: Boolean) : PeerEvent()
    @Serializable
    data class GameStarted(val playerValues: Map<String, Int>) : PeerEvent()
    @Serializable
    data class ButtonPress(val sourceId: String, val targetId: String, val delta: Int) : PeerEvent()
    @Serializable
    data class PhaseChanged(val newPhase: GamePhase) : PeerEvent()
    @Serializable
    data class VoteCast(val playerId: String, val choice: VoteChoice) : PeerEvent()
}

/**
 * Establishes a connection to the peer network for discovering and communicating with other peers on the LAN.
 *
 * The connection handles all the complexity internally:
 * - Service advertisement and discovery via mDNS/Bonjour
 * - Bidirectional handshake verification
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
