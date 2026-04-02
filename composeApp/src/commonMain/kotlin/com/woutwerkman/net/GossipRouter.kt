package com.woutwerkman.net

import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val LIN_PREFIX = "_PN_LIN_"
internal const val LIN_EVENT = "${LIN_PREFIX}EVENT|"
private const val LIN_STATE_RESP = "${LIN_PREFIX}STATE_RESP|"
private const val LIN_REACH = "${LIN_PREFIX}REACH|"
private const val LIN_ROUTE = "${LIN_PREFIX}ROUTE|"

internal val linJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Serializable
internal data class ReachabilityAnnounce(
    val peerId: String,
    /** Direct connections of this peer. */
    val directPeers: Set<String>,
)

/**
 * Gossip router: bridges the raw transport layer and the event linearizer.
 *
 * Responsibilities:
 * - Converts raw Connected/Disconnected into PeerEvent.Joined/Left
 * - Deserializes incoming gossip events and feeds them to the linearizer
 * - Broadcasts local events to all reachable peers (directly or via mediums)
 * - Periodically syncs the full event log and reachability to direct peers
 * - Tracks peer connectivity and routes messages through mediums
 * - On disconnect, emits Left for the peer and any peers only reachable through it
 */
/**
 * Channel for events submitted locally via [PeerNetConnection.submitEvent].
 * The gossip router reads from this to include them in state sync and gossip.
 */
internal suspend fun gossipRouter(
    raw: RawPeerNetConnection,
    eventChannel: SendChannel<EventWithTime>,
    localEvents: kotlinx.coroutines.channels.ReceiveChannel<EventWithTime>,
    displayName: String,
    clock: Clock,
) = coroutineScope {
    val directConnections = mutableSetOf<String>()
    val eventLog = mutableListOf<EventWithTime>()
    // reachabilityMap: directPeerId -> set of their DIRECT peers
    val reachabilityMap = mutableMapOf<String, Set<String>>()
    // Peers we discovered via direct connection or reachability (not gossip).
    // Only these are subject to "unreachable → Left" logic.
    val reachabilityKnownPeers = mutableSetOf<String>()

    /**
     * Compute all peers reachable from this node using BFS over the
     * reachability graph.
     */
    fun allReachablePeers(): Set<String> {
        val reachable = mutableSetOf(raw.localPeerId)
        val queue = ArrayDeque(directConnections.toList())
        reachable.addAll(directConnections)
        while (queue.isNotEmpty()) {
            val peer = queue.removeFirst()
            for (transitivePeer in reachabilityMap[peer] ?: emptySet()) {
                if (reachable.add(transitivePeer)) {
                    queue.add(transitivePeer)
                }
            }
        }
        return reachable
    }

    /**
     * Elect a medium for routing to [targetId].
     * First tries direct peers that know the target, then does BFS.
     */
    fun electMedium(targetId: String): String? {
        val directCandidates = directConnections.filter { directPeer ->
            targetId in (reachabilityMap[directPeer] ?: emptySet())
        }
        if (directCandidates.isNotEmpty()) {
            return directCandidates.minByOrNull { fnv1a(it + targetId) }
        }
        for (directPeer in directConnections) {
            val visited = mutableSetOf(raw.localPeerId, directPeer)
            val bfsQueue = ArrayDeque<String>()
            bfsQueue.add(directPeer)
            while (bfsQueue.isNotEmpty()) {
                val current = bfsQueue.removeFirst()
                for (next in reachabilityMap[current] ?: emptySet()) {
                    if (next == targetId) return directPeer
                    if (visited.add(next)) bfsQueue.add(next)
                }
            }
        }
        return null
    }

    suspend fun emitEvent(ewt: EventWithTime) {
        if (ewt !in eventLog) {
            eventLog.add(ewt)
            eventChannel.send(ewt)
        }
    }

    suspend fun broadcastReachability() {
        val announce = ReachabilityAnnounce(raw.localPeerId, directConnections.toSet())
        val payload = "$LIN_REACH${linJson.encodeToString(announce)}"
        for (peerId in directConnections.toList()) {
            sendToRaw(raw, peerId, payload)
        }
    }

    suspend fun sendToTarget(targetId: String, payload: String) {
        if (targetId in directConnections) {
            sendToRaw(raw, targetId, payload)
        } else {
            val medium = electMedium(targetId)
            if (medium != null) {
                sendToRaw(raw, medium, "$LIN_ROUTE$targetId|3|$payload")
            }
        }
    }

    suspend fun broadcastToAll(payload: String) {
        for (peerId in directConnections.toList()) {
            sendToRaw(raw, peerId, payload)
        }
        val indirectPeers = allReachablePeers() - directConnections - raw.localPeerId
        for (targetId in indirectPeers) {
            sendToTarget(targetId, payload)
        }
    }

    suspend fun handleGossipPayload(payload: String) {
        when {
            payload.startsWith(LIN_EVENT) -> {
                val ewt = try {
                    linJson.decodeFromString<EventWithTime>(payload.removePrefix(LIN_EVENT))
                } catch (_: Exception) { return }
                if (ewt !in eventLog) {
                    eventLog.add(ewt)
                    eventChannel.send(ewt)
                    broadcastToAll(payload)
                }
            }
            payload.startsWith(LIN_STATE_RESP) -> {
                val received = try {
                    linJson.decodeFromString<List<EventWithTime>>(payload.removePrefix(LIN_STATE_RESP))
                } catch (_: Exception) { return }
                for (ewt in received) {
                    if (ewt !in eventLog) {
                        eventLog.add(ewt)
                        eventChannel.send(ewt)
                        broadcastToAll("$LIN_EVENT${linJson.encodeToString(ewt)}")
                    }
                }
            }
        }
    }

    /**
     * Emit Left events for peers that were known via reachability but are no longer reachable.
     * Only called on disconnect — NOT on reachability updates from gossip.
     */
    suspend fun emitLeftForUnreachablePeers() {
        val reachable = allReachablePeers()
        val unreachable = reachabilityKnownPeers.toSet() - reachable
        for (peerId in unreachable) {
            reachabilityKnownPeers.remove(peerId)
            val ewt = EventWithTime(clock.now(), raw.localPeerId, PeerEvent.Left(peerId))
            emitEvent(ewt)
            broadcastToAll("$LIN_EVENT${linJson.encodeToString(ewt)}")
        }
    }

    // Emit self-join
    val selfInfo = PeerInfo(id = raw.localPeerId, name = displayName, address = "", port = 0)
    val selfJoin = EventWithTime(clock.now(), raw.localPeerId, PeerEvent.Joined(selfInfo))
    emitEvent(selfJoin)
    reachabilityKnownPeers.add(raw.localPeerId)

    // Process locally submitted events — add to eventLog and broadcast
    launch {
        for (ewt in localEvents) {
            if (ewt !in eventLog) {
                eventLog.add(ewt)
                broadcastToAll("$LIN_EVENT${linJson.encodeToString(ewt)}")
            }
        }
    }

    // Periodic state sync + reachability announce
    launch {
        while (currentCoroutineContext().isActive) {
            delay(2.seconds)
            for (peerId in directConnections.toList()) {
                sendToRaw(raw, peerId, "$LIN_STATE_RESP${linJson.encodeToString(eventLog.toList())}")
            }
            broadcastReachability()
            // Share all known reachability data for multi-hop BFS
            for (peerId in directConnections.toList()) {
                for ((announcer, peers) in reachabilityMap) {
                    if (announcer != peerId) {
                        val announce = ReachabilityAnnounce(announcer, peers)
                        sendToRaw(raw, peerId, "$LIN_REACH${linJson.encodeToString(announce)}")
                    }
                }
            }
        }
    }

    // Process incoming messages
    for (message in raw.incoming) {
        when (message) {
            is RawPeerMessage.Event.Connected -> {
                directConnections.add(message.peer.id)
                reachabilityKnownPeers.add(message.peer.id)
                val ewt = EventWithTime(clock.now(), raw.localPeerId, PeerEvent.Joined(message.peer))
                emitEvent(ewt)
                broadcastToAll("$LIN_EVENT${linJson.encodeToString(ewt)}")
                sendToRaw(raw, message.peer.id, "$LIN_STATE_RESP${linJson.encodeToString(eventLog.toList())}")
                broadcastReachability()
            }
            is RawPeerMessage.Event.Disconnected -> {
                directConnections.remove(message.peerId)
                reachabilityMap.remove(message.peerId)
                val stillReachable = allReachablePeers()
                if (message.peerId !in stillReachable) {
                    reachabilityKnownPeers.remove(message.peerId)
                    val ewt = EventWithTime(clock.now(), raw.localPeerId, PeerEvent.Left(message.peerId))
                    emitEvent(ewt)
                    broadcastToAll("$LIN_EVENT${linJson.encodeToString(ewt)}")
                }
                emitLeftForUnreachablePeers()
                broadcastReachability()
            }
            is RawPeerMessage.Received -> {
                val payload = message.payload.decodeToString()
                when {
                    payload.startsWith(LIN_ROUTE) -> {
                        val rest = payload.removePrefix(LIN_ROUTE)
                        val firstPipe = rest.indexOf('|')
                        val secondPipe = rest.indexOf('|', firstPipe + 1)
                        if (firstPipe < 0 || secondPipe < 0) continue
                        val targetId = rest.substring(0, firstPipe)
                        val ttl = rest.substring(firstPipe + 1, secondPipe).toIntOrNull() ?: continue
                        val innerPayload = rest.substring(secondPipe + 1)
                        if (targetId == raw.localPeerId) {
                            handleGossipPayload(innerPayload)
                        } else if (ttl > 1) {
                            val nextHop = if (targetId in directConnections) targetId
                                else electMedium(targetId)
                            if (nextHop != null) {
                                sendToRaw(raw, nextHop, "$LIN_ROUTE$targetId|${ttl - 1}|$innerPayload")
                            }
                        }
                    }
                    payload.startsWith(LIN_REACH) -> {
                        val announce = try {
                            linJson.decodeFromString<ReachabilityAnnounce>(payload.removePrefix(LIN_REACH))
                        } catch (_: Exception) { continue }
                        reachabilityMap[announce.peerId] = announce.directPeers
                        // Check for peers that became unreachable
                        emitLeftForUnreachablePeers()
                        // Discover new indirect peers via reachability
                        val reachable = allReachablePeers()
                        for (peerId in reachable) {
                            if (peerId !in reachabilityKnownPeers && peerId != raw.localPeerId) {
                                reachabilityKnownPeers.add(peerId)
                                // Only emit Joined if we don't already have one for this peer in the log.
                                // The gossip/state-sync Joined carries the correct display name;
                                // emitting a reachability-based Joined with peerId as name would overwrite it.
                                val alreadyKnown = eventLog.any {
                                    it.event is PeerEvent.Joined && (it.event as PeerEvent.Joined).peer.id == peerId
                                }
                                if (!alreadyKnown) {
                                    val peerInfo = PeerInfo(id = peerId, name = peerId, address = "", port = 0)
                                    val ewt = EventWithTime(clock.now(), raw.localPeerId, PeerEvent.Joined(peerInfo))
                                    emitEvent(ewt)
                                }
                            }
                        }
                    }
                    else -> handleGossipPayload(payload)
                }
            }
        }
    }
}

internal suspend fun broadcastRaw(raw: RawPeerNetConnection, payload: String) {
    try { raw.outgoing.send(PeerCommand.Broadcast(payload.encodeToByteArray())) }
    catch (e: kotlinx.coroutines.CancellationException) { throw e }
    catch (_: Exception) {}
}

private suspend fun sendToRaw(raw: RawPeerNetConnection, peerId: String, payload: String) {
    try { raw.outgoing.send(PeerCommand.SendTo(peerId, payload.encodeToByteArray())) }
    catch (e: kotlinx.coroutines.CancellationException) { throw e }
    catch (_: Exception) {}
}
