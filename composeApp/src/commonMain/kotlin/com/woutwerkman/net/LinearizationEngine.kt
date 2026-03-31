package com.woutwerkman.net

import com.woutwerkman.currentTimeMillis
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
private const val LIN_STATE_REQ = "${LIN_PREFIX}STATE_REQ"
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
        // Add a Joined event for this peer
        val event = PeerEvent.Joined(peer)
        val serialized = json.encodeToString<PeerEvent>(event)
        val timestamped = TimestampedEvent(clock(), localPeerId, serialized)
        addEvent(timestamped)
        broadcastEvent(timestamped)

        // Send our full state to the new peer so they can catch up
        sendStateTo(peer.id)
    }

    private suspend fun handleRawDisconnected(peerId: String) {
        val event = PeerEvent.Left(peerId)
        val serialized = json.encodeToString<PeerEvent>(event)
        val timestamped = TimestampedEvent(clock(), localPeerId, serialized)
        addEvent(timestamped)
        broadcastEvent(timestamped)
    }

    private suspend fun handleRawReceived(fromPeerId: String, payload: String) {
        when {
            payload.startsWith(LIN_EVENT) -> handleEvent(payload.removePrefix(LIN_EVENT))
            payload == LIN_STATE_REQ -> sendStateTo(fromPeerId)
            payload.startsWith(LIN_STATE_RESP) -> handleStateResp(payload.removePrefix(LIN_STATE_RESP))
        }
    }

    private fun handleEvent(data: String) {
        val event = try {
            json.decodeFromString<TimestampedEvent>(data)
        } catch (e: Exception) {
            return
        }
        addEvent(event)
    }

    private fun handleStateResp(data: String) {
        val receivedEvents = try {
            json.decodeFromString<List<TimestampedEvent>>(data)
        } catch (e: Exception) {
            return
        }
        var changed = false
        for (event in receivedEvents) {
            if (events.none { it.timestamp == event.timestamp && it.peerId == event.peerId && it.serializedEvent == event.serializedEvent }) {
                events.add(event)
                changed = true
            }
        }
        if (changed) {
            events.sortWith(compareBy<TimestampedEvent> { it.timestamp }.thenBy { it.peerId })
            emitState()
        }
    }

    private fun addEvent(event: TimestampedEvent) {
        // Dedup: don't add if we already have this exact event
        if (events.any { it.timestamp == event.timestamp && it.peerId == event.peerId && it.serializedEvent == event.serializedEvent }) {
            return
        }
        events.add(event)
        events.sortWith(compareBy<TimestampedEvent> { it.timestamp }.thenBy { it.peerId })
        emitState()
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
