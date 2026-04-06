package com.woutwerkman.net

import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel

internal const val MESSAGE_PORT = 47391

// Internal protocol prefixes - hidden from consumers
internal const val HANDSHAKE_PREFIX = "_PN_HS_"
internal const val HANDSHAKE_HELLO = "${HANDSHAKE_PREFIX}HELLO|"
internal const val HANDSHAKE_ACK = "${HANDSHAKE_PREFIX}ACK|"

/**
 * Tracks the state of a discovered peer through the handshake process.
 * Each platform may extend this with extra fields via composition.
 */
internal data class PeerState(
    val info: PeerInfo,
    val weSeeThemViaDiscovery: Boolean = false,
    val weSentHello: Boolean = false,
    val theyAckedUs: Boolean = false,
    val weAckedThem: Boolean = false,
    val isJoined: Boolean = false,
    /** iOS only: whether this peer is connected via BLE. */
    val bleConnected: Boolean = false,
    /** iOS only: whether the UDP handshake completed (prefers UDP over BLE when true). */
    val udpHandshakeComplete: Boolean = false,
)

/**
 * Parsed contents of a HELLO handshake message.
 */
internal data class HelloData(
    val peerName: String,
    val peerId: String,
    val address: String,
    val port: Int,
)

internal fun parseHelloPayload(payload: String, fallbackAddress: String): HelloData {
    val helloData = payload.removePrefix(HANDSHAKE_HELLO)
    val parts = helloData.split("|")
    return HelloData(
        peerName = parts.getOrNull(0) ?: "Unknown",
        peerId = parts.getOrNull(1) ?: "",
        address = parts.getOrNull(2) ?: fallbackAddress,
        port = parts.getOrNull(3)?.toIntOrNull() ?: MESSAGE_PORT,
    )
}

internal fun formatHelloPayload(peerName: String, peerId: String, localAddress: String, localPort: Int): String =
    "$HANDSHAKE_HELLO$peerName|$peerId|$localAddress|$localPort"

internal fun formatAckPayload(peerId: String): String =
    "$HANDSHAKE_ACK$peerId"

/**
 * Parses a service name in the format "name|peerId|address|port".
 * Returns null if the format is invalid (fewer than 3 parts).
 */
internal fun parseServiceName(serviceName: String): HelloData? {
    val parts = serviceName.split("|")
    if (parts.size < 3) return null
    return HelloData(
        peerName = parts[0],
        peerId = parts[1],
        address = parts[2],
        port = parts.getOrNull(3)?.toIntOrNull() ?: MESSAGE_PORT,
    )
}

internal fun formatServiceName(peerName: String, peerId: String, localAddress: String, localPort: Int): String =
    "$peerName|$peerId|$localAddress|$localPort"

/**
 * Emit Joined event if handshake is complete (both sides confirmed).
 * Uses trySend since this is called from non-suspend contexts.
 */
internal fun checkAndEmitJoined(peerId: String, state: PeerState, joinedEvents: SendChannel<String>) {
    if (state.weSeeThemViaDiscovery && state.theyAckedUs && !state.isJoined) {
        joinedEvents.trySend(peerId)
    }
}

/**
 * Parses a raw UDP message in the format "senderId:payload".
 * Returns null if the message format is invalid or it's from ourselves.
 */
internal fun parseUdpMessage(message: String, localPeerId: String): Pair<String, String>? {
    val separatorIndex = message.indexOf(':')
    if (separatorIndex <= 0) return null
    val fromPeerId = message.substring(0, separatorIndex)
    if (fromPeerId == localPeerId) return null
    return fromPeerId to message.substring(separatorIndex + 1)
}

/**
 * Processes joined events: marks the peer as joined in [peerStates] and emits a Connected event.
 * This is the same across all platforms — the handshake protocol produces peer IDs on the
 * [joinedEvents] channel, and this function turns them into Connected messages.
 */
internal suspend fun processJoinedEvents(
    joinedEvents: ReceiveChannel<String>,
    peerStates: PeerStates,
    incomingChannel: SendChannel<RawPeerMessage>,
    localPeerId: String,
) {
    for (peerId in joinedEvents) {
        var connected: PeerInfo? = null
        peerStates.compute(peerId) { existing ->
            if (existing != null && !existing.isJoined) {
                connected = existing.info
                existing.copy(isJoined = true)
            } else {
                existing
            }
        }
        connected?.let { info ->
            println("[PeerNet-$localPeerId] Peer JOINED: ${info.name} (${info.id})")
            incomingChannel.send(RawPeerMessage.Event.Connected(info))
        }
    }
}

/**
 * Abstraction over platform-specific thread-safe peer state maps.
 * Android/JVM use ConcurrentHashMap, iOS uses AtomicReference-based CAS.
 */
internal interface PeerStates {
    fun snapshot(): Map<String, PeerState>
    fun compute(peerId: String, transform: (PeerState?) -> PeerState?): PeerState?
    fun remove(peerId: String): PeerState?
    operator fun get(peerId: String): PeerState?
}
