package com.woutwerkman.net

import com.woutwerkman.currentTimeMillis
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// Internal protocol prefix — distinct from the handshake prefix (_PN_HS_)
private const val LIN_PREFIX = "_PN_LIN_"
private const val LIN_EVENT = "${LIN_PREFIX}EVENT|"
private const val LIN_STATE_REQ = "${LIN_PREFIX}STATE_REQ"
private const val LIN_STATE_RESP = "${LIN_PREFIX}STATE_RESP|"
private const val LIN_RELAY = "${LIN_PREFIX}RELAY|"
private const val LIN_RELAY_TO = "${LIN_PREFIX}RELAY_TO|"
private const val MAX_RELAY_IDS = 2000

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

    private val _state = MutableStateFlow(PeerNetState(emptyMap()))
    val state: StateFlow<PeerNetState> = _state.asStateFlow()

    // All known events, kept sorted by (timestamp, peerId)
    private val events = mutableListOf<TimestampedEvent>()

    // App-level message relay with gossip
    private val _appMessages = Channel<AppMessage>(Channel.BUFFERED)
    val appMessages: ReceiveChannel<AppMessage> get() = _appMessages
    private val seenRelayIds = LinkedHashSet<String>()
    private var relayCounter = 0L

    /**
     * Start the engine loops. Suspends until cancelled.
     */
    // Peers we've seen at the raw level (for periodic state sync)
    private val connectedPeerIds = mutableSetOf<String>()

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

    /** Broadcast an app message to all peers via gossip relay. */
    suspend fun relayBroadcast(payload: String) {
        val relayId = "${localPeerId}-${clock()}-${relayCounter++}"
        seenRelayIds.add(relayId)
        trimRelayIds()
        broadcastRaw("$LIN_RELAY$relayId|$localPeerId|$payload")
    }

    /** Send an app message to a specific peer via gossip relay. */
    suspend fun relayTo(targetPeerId: String, payload: String) {
        val relayId = "${localPeerId}-${clock()}-${relayCounter++}"
        seenRelayIds.add(relayId)
        trimRelayIds()
        broadcastRaw("$LIN_RELAY_TO$targetPeerId|$relayId|$localPeerId|$payload")
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
            payload == LIN_STATE_REQ -> sendStateTo(fromPeerId)
            payload.startsWith(LIN_STATE_RESP) -> gossipStateResp(payload.removePrefix(LIN_STATE_RESP))
            payload.startsWith(LIN_RELAY_TO) -> handleRelayTo(payload.removePrefix(LIN_RELAY_TO))
            payload.startsWith(LIN_RELAY) -> handleRelay(payload.removePrefix(LIN_RELAY))
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

    /** Handle a broadcast relay message: deliver locally and re-gossip. */
    private suspend fun handleRelay(data: String) {
        val parts = data.split("|", limit = 3)
        if (parts.size < 3) return
        val relayId = parts[0]
        val originPeerId = parts[1]
        val payload = parts[2]

        if (!seenRelayIds.add(relayId)) return
        trimRelayIds()

        _appMessages.trySend(AppMessage(originPeerId, payload))
        broadcastRaw("$LIN_RELAY$data")
    }

    /** Handle a targeted relay message: deliver only if we're the target, always re-gossip. */
    private suspend fun handleRelayTo(data: String) {
        val parts = data.split("|", limit = 4)
        if (parts.size < 4) return
        val targetPeerId = parts[0]
        val relayId = parts[1]
        val originPeerId = parts[2]
        val payload = parts[3]

        if (!seenRelayIds.add(relayId)) return
        trimRelayIds()

        if (targetPeerId == localPeerId) {
            _appMessages.trySend(AppMessage(originPeerId, payload))
        }

        broadcastRaw("$LIN_RELAY_TO$data")
    }

    private fun trimRelayIds() {
        while (seenRelayIds.size > MAX_RELAY_IDS) {
            val iter = seenRelayIds.iterator()
            iter.next()
            iter.remove()
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

    private fun foldState(): PeerNetState {
        var peers = mapOf<String, PeerInfo>()
        for (timestamped in events) {
            val event = try {
                json.decodeFromString<PeerEvent>(timestamped.serializedEvent)
            } catch (e: Exception) {
                continue
            }
            when (event) {
                is PeerEvent.Joined -> peers = peers + (event.peer.id to event.peer)
                is PeerEvent.Left -> peers = peers - event.peerId
            }
        }
        return PeerNetState(discoveredPeers = peers)
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
