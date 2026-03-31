package com.woutwerkman.net

import com.woutwerkman.currentTimeMillis
import com.woutwerkman.game.model.GamePhase
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// Internal protocol prefix — distinct from the handshake prefix (_PN_HS_)
private const val LIN_PREFIX = "_PN_LIN_"
private const val LIN_EVENT = "${LIN_PREFIX}EVENT|"
private const val LIN_STATE_RESP = "${LIN_PREFIX}STATE_RESP|"

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * A timestamped event in the linearized log.
 * Events are ordered by (timestamp, peerId) for a deterministic total order.
 */
@Serializable
internal data class TimestampedEvent(
    val timestamp: Long,
    val peerId: String,
    val serializedEvent: String,
) : Comparable<TimestampedEvent> {
    override fun compareTo(other: TimestampedEvent): Int {
        val cmp = timestamp.compareTo(other.timestamp)
        if (cmp != 0) return cmp
        return peerId.compareTo(other.peerId)
    }
}

/**
 * Timestamp-based linearization engine.
 *
 * Every peer broadcasts events with their local timestamp. All peers independently
 * sort events by (timestamp, peerId) to arrive at the same deterministic total order.
 * No leader election needed — we trust everyone's clock.
 */
internal class LinearizationEngine(
    private val raw: RawPeerNetConnection,
    private val displayName: String,
    private val clock: () -> Long = { currentTimeMillis() },
) {
    val localPeerId: String get() = raw.localPeerId

    private val _state = MutableStateFlow(PeerNetState())
    val state: StateFlow<PeerNetState> = _state.asStateFlow()

    // All known events, kept sorted by (timestamp, peerId)
    private val events = mutableListOf<TimestampedEvent>()

    // Peers we've seen at the raw level (for periodic state sync)
    private val connectedPeerIds = mutableSetOf<String>()

    /**
     * Start the engine loops. Suspends until cancelled.
     */
    suspend fun start() {
        // Commit our own Joined event
        val selfInfo = PeerInfo(id = localPeerId, name = displayName, address = "", port = 0)
        val selfJoin = PeerEvent.Joined(selfInfo)
        addEvent(TimestampedEvent(clock(), localPeerId, json.encodeToString<PeerEvent>(selfJoin)))

        coroutineScope {
            launch { processIncoming() }
            launch { periodicStateSync() }
        }
    }

    /**
     * Submit an event. Broadcasts it to all peers with our timestamp.
     * Returns true once broadcast (timestamp ordering means it's immediately "committed").
     */
    suspend fun submitEvent(event: PeerEvent): Boolean {
        val serialized = json.encodeToString<PeerEvent>(event)
        val timestamped = TimestampedEvent(clock(), localPeerId, serialized)
        addEvent(timestamped)
        broadcastEvent(timestamped)
        return true
    }

    private suspend fun processIncoming() {
        while (currentCoroutineContext().isActive) {
            val message = try {
                withTimeoutOrNull(500) {
                    raw.incoming.receive()
                }
            } catch (e: ClosedReceiveChannelException) {
                break
            } ?: continue

            when (message) {
                is RawPeerMessage.Event.Connected -> handleRawConnected(message.peer)
                is RawPeerMessage.Event.Disconnected -> handleRawDisconnected(message.peerId)
                is RawPeerMessage.Received -> handleRawReceived(message.fromPeerId, message.payload.decodeToString())
            }
        }
    }

    private suspend fun handleRawConnected(peer: PeerInfo) {
        connectedPeerIds.add(peer.id)

        // Add a Joined event for this peer
        val event = PeerEvent.Joined(peer)
        val serialized = json.encodeToString<PeerEvent>(event)
        val timestamped = TimestampedEvent(clock(), localPeerId, serialized)
        addEvent(timestamped)
        broadcastEvent(timestamped)

        // Send our full state to the new peer so they can catch up
        sendStateTo(peer.id)
    }

    /**
     * Periodically resend our full state to all connected peers.
     * Handles packet loss, NAT timing issues, and address changes.
     */
    private suspend fun periodicStateSync() {
        while (currentCoroutineContext().isActive) {
            delay(2000)
            for (peerId in connectedPeerIds.toList()) {
                sendStateTo(peerId)
            }
        }
    }

    private suspend fun handleRawDisconnected(peerId: String) {
        connectedPeerIds.remove(peerId)
        val event = PeerEvent.Left(peerId)
        val serialized = json.encodeToString<PeerEvent>(event)
        val timestamped = TimestampedEvent(clock(), localPeerId, serialized)
        addEvent(timestamped)
        broadcastEvent(timestamped)
    }

    private suspend fun handleRawReceived(fromPeerId: String, payload: String) {
        when {
            payload.startsWith(LIN_EVENT) -> gossipEvent(payload.removePrefix(LIN_EVENT))
            payload.startsWith(LIN_STATE_RESP) -> gossipStateResp(payload.removePrefix(LIN_STATE_RESP))
        }
    }

    /**
     * Gossip: when we receive an event we haven't seen, re-broadcast it.
     * This lets peers that can't reach each other directly communicate
     * through any peer that can reach both.
     */
    private suspend fun gossipEvent(data: String) {
        val event = try {
            json.decodeFromString<TimestampedEvent>(data)
        } catch (e: Exception) {
            return
        }
        if (addEvent(event)) {
            broadcastEvent(event)
        }
    }

    private suspend fun gossipStateResp(data: String) {
        val receivedEvents = try {
            json.decodeFromString<List<TimestampedEvent>>(data)
        } catch (e: Exception) {
            return
        }
        val newEvents = mutableListOf<TimestampedEvent>()
        for (event in receivedEvents) {
            if (addEvent(event)) {
                newEvents.add(event)
            }
        }
        // Re-broadcast any events we hadn't seen
        for (event in newEvents) {
            broadcastEvent(event)
        }
    }

    /** Add event if new. Returns true if it was new, false if duplicate. */
    private fun addEvent(event: TimestampedEvent): Boolean {
        if (events.any { it.timestamp == event.timestamp && it.peerId == event.peerId && it.serializedEvent == event.serializedEvent }) {
            return false
        }
        events.add(event)
        events.sortWith(compareBy<TimestampedEvent> { it.timestamp }.thenBy { it.peerId })
        emitState()
        return true
    }

    private fun emitState() {
        _state.value = foldState()
    }

    /**
     * Fold all events into the current state.
     * Deterministic: same events in same order → same state on all peers.
     */
    private fun foldState(): PeerNetState {
        var peers = mapOf<String, PeerInfo>()
        var pendingInvites = listOf<Invite>()
        var lobby: LobbyInfo? = null
        var gamePhase = GamePhase.WAITING
        var playerValues = mapOf<String, Int>()
        var votes = mapOf<String, com.woutwerkman.game.model.VoteChoice>()

        for (timestamped in events) {
            val event = try {
                json.decodeFromString<PeerEvent>(timestamped.serializedEvent)
            } catch (e: Exception) {
                continue
            }
            when (event) {
                is PeerEvent.Joined -> {
                    peers = peers + (event.peer.id to event.peer)
                }
                is PeerEvent.Left -> {
                    peers = peers - event.peerId
                    // Clean up invites involving this peer
                    pendingInvites = pendingInvites.filter { it.fromId != event.peerId && it.toId != event.peerId }
                    // Remove from lobby if in one
                    lobby = lobby?.let { l ->
                        val updated = l.copy(players = l.players - event.peerId)
                        if (updated.players.isEmpty()) null else updated
                    }
                }

                is PeerEvent.InviteSent -> {
                    // Only add if not already pending
                    if (pendingInvites.none { it.fromId == event.fromId && it.toId == event.toId }) {
                        pendingInvites = pendingInvites + Invite(event.fromId, event.toId)
                    }
                }
                is PeerEvent.InviteAccepted -> {
                    // Remove the invite
                    pendingInvites = pendingInvites.filter {
                        !(it.fromId == event.fromId && it.toId == event.toId)
                    }
                    // Create lobby if it doesn't exist, or add both players
                    val currentLobby = lobby
                    if (currentLobby == null || currentLobby.lobbyId == event.lobbyId) {
                        val hostName = peers[event.toId]?.name ?: "Unknown"
                        val joinerName = peers[event.fromId]?.name ?: "Unknown"
                        val existingPlayers = currentLobby?.players ?: emptyMap()
                        lobby = LobbyInfo(
                            lobbyId = event.lobbyId,
                            hostId = event.toId,
                            players = existingPlayers +
                                    (event.toId to LobbyPlayer(name = hostName)) +
                                    (event.fromId to LobbyPlayer(name = joinerName)),
                        )
                    }
                }
                is PeerEvent.InviteRejected -> {
                    pendingInvites = pendingInvites.filter {
                        !(it.fromId == event.fromId && it.toId == event.toId)
                    }
                }

                is PeerEvent.LobbyCreated -> {
                    val hostName = peers[event.hostId]?.name ?: "Unknown"
                    lobby = LobbyInfo(
                        lobbyId = event.lobbyId,
                        hostId = event.hostId,
                        players = mapOf(event.hostId to LobbyPlayer(name = hostName)),
                    )
                    gamePhase = GamePhase.WAITING
                    playerValues = emptyMap()
                    votes = emptyMap()
                }
                is PeerEvent.JoinedLobby -> {
                    lobby = lobby?.let { l ->
                        val name = peers[event.playerId]?.name ?: "Unknown"
                        l.copy(players = l.players + (event.playerId to LobbyPlayer(name = name)))
                    }
                }
                is PeerEvent.LeftLobby -> {
                    lobby = lobby?.let { l ->
                        val updated = l.copy(players = l.players - event.playerId)
                        if (updated.players.isEmpty()) null else updated
                    }
                    if (lobby == null) {
                        gamePhase = GamePhase.WAITING
                        playerValues = emptyMap()
                        votes = emptyMap()
                    }
                }

                is PeerEvent.ReadyChanged -> {
                    lobby = lobby?.let { l ->
                        val player = l.players[event.playerId] ?: return@let l
                        l.copy(players = l.players + (event.playerId to player.copy(isReady = event.isReady)))
                    }
                }
                is PeerEvent.GameStarted -> {
                    gamePhase = GamePhase.COUNTDOWN
                    playerValues = event.playerValues
                    votes = emptyMap()
                }
                is PeerEvent.ButtonPress -> {
                    val currentValue = playerValues[event.targetId]
                    if (currentValue != null) {
                        val newValue = (currentValue + event.delta).coerceIn(-25, 25)
                        playerValues = playerValues + (event.targetId to newValue)
                    }
                }
                is PeerEvent.PhaseChanged -> {
                    gamePhase = event.newPhase
                    if (event.newPhase == GamePhase.WAITING) {
                        // Reset game state when going back to waiting
                        playerValues = emptyMap()
                        votes = emptyMap()
                        // Reset ready states
                        lobby = lobby?.let { l ->
                            l.copy(players = l.players.mapValues { (_, p) -> p.copy(isReady = false) })
                        }
                    }
                }
                is PeerEvent.VoteCast -> {
                    votes = votes + (event.playerId to event.choice)
                }
            }
        }

        return PeerNetState(
            discoveredPeers = peers,
            pendingInvites = pendingInvites,
            lobby = lobby,
            gamePhase = gamePhase,
            playerValues = playerValues,
            votes = votes,
        )
    }

    private suspend fun broadcastEvent(event: TimestampedEvent) {
        val msg = "$LIN_EVENT${json.encodeToString(event)}"
        broadcastRaw(msg)
    }

    private suspend fun sendStateTo(peerId: String) {
        val msg = "$LIN_STATE_RESP${json.encodeToString(events.toList())}"
        sendToRaw(peerId, msg)
    }

    private suspend fun sendToRaw(peerId: String, payload: String) {
        try {
            raw.outgoing.send(PeerCommand.SendTo(peerId, payload.encodeToByteArray()))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {}
    }

    private suspend fun broadcastRaw(payload: String) {
        try {
            raw.outgoing.send(PeerCommand.Broadcast(payload.encodeToByteArray()))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {}
    }
}
