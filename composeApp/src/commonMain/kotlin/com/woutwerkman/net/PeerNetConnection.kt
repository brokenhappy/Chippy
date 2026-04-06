package com.woutwerkman.net

import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * Represents a discovered peer on the network.
 */
@Serializable
data class PeerInfo(
    val id: String,
    val name: String,
    val address: String,
    val port: Int,
    /** Port of the device's web client host server, or 0 if not hosting. */
    val webPort: Int = 0,
    /** Whether the web server uses TLS (HTTPS/WSS). */
    val webSecure: Boolean = false,
    /** Platform identifier (e.g. "Java 17", "Android 34"). */
    val platform: String = "",
)

/**
 * Messages that can be sent/received over the peer network.
 */
@Serializable
sealed class RawPeerMessage {
    @Serializable
    sealed class Event : RawPeerMessage() {
        @Serializable
        data class Connected(val peer: PeerInfo) : Event()

        @Serializable
        data class Disconnected(val peerId: String) : Event()
    }

    @Serializable
    data class Received(
        val fromPeerId: String,
        val payload: ByteArray
    ) : RawPeerMessage() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false
            other as Received
            return fromPeerId == other.fromPeerId && payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int {
            var result = fromPeerId.hashCode()
            result = 31 * result + payload.contentHashCode()
            return result
        }
    }
}

/**
 * Command to send to the peer network.
 */
@Serializable
sealed class PeerCommand {
    /**
     * Send data to a specific peer.
     */
    @Serializable
    data class SendTo(
        val peerId: String,
        val payload: ByteArray
    ) : PeerCommand() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false
            other as SendTo
            return peerId == other.peerId && payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int {
            var result = peerId.hashCode()
            result = 31 * result + payload.contentHashCode()
            return result
        }
    }

    /**
     * Broadcast data to all discovered peers.
     */
    @Serializable
    data class Broadcast(
        val payload: ByteArray
    ) : PeerCommand() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false
            other as Broadcast
            return payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int = payload.contentHashCode()
    }
}

/** A raw connection to the peer network (internal transport layer). */
class RawPeerNetConnection(
    val localPeerId: String,
    val incoming: ReceiveChannel<RawPeerMessage>,
    val outgoing: SendChannel<PeerCommand>,
    /**
     * Directly broadcasts a payload to all known peers, bypassing the [outgoing] channel.
     * Used for best-effort shutdown messages when the channel-processing coroutine is cancelled.
     * This is a non-suspending, synchronous call.
     */
    val broadcastDirect: (ByteArray) -> Unit = {},
)

/**
 * Configuration for the peer network connection.
 */
data class PeerNetConfig(
    val serviceName: String = "chippy",
    val displayName: String = "Peer",
)

/**
 * Establishes a raw connection to the peer network (internal transport layer).
 *
 * Uses platform-specific [withTransport] for UDP/mDNS, then runs the common handshake
 * protocol to discover and connect peers. The handshake ensures both sides see each other
 * before emitting a Connected event.
 */
internal suspend fun <T> withRawPeerNetConnection(
    config: PeerNetConfig = PeerNetConfig(),
    block: suspend CoroutineScope.(RawPeerNetConnection) -> T
): T = withRawPeerNetConnectionImpl(config, block)

/**
 * Platform-specific implementation. Android/JVM delegate to [withRawPeerNetConnectionCommon].
 * iOS overrides this entirely due to BLE routing requirements.
 */
internal expect suspend fun <T> withRawPeerNetConnectionImpl(
    config: PeerNetConfig,
    block: suspend CoroutineScope.(RawPeerNetConnection) -> T
): T

/**
 * Common implementation used by Android and JVM.
 * Uses [withTransport] for the platform-specific UDP/mDNS layer, then runs the common
 * handshake protocol on top.
 */
internal suspend fun <T> withRawPeerNetConnectionCommon(
    config: PeerNetConfig,
    block: suspend CoroutineScope.(RawPeerNetConnection) -> T
): T {
    val peerId = generatePeerId()
    val incoming = Channel<RawPeerMessage>(Channel.BUFFERED)
    val outgoing = Channel<PeerCommand>(Channel.BUFFERED)

    return withTransport(config, peerId) { transport ->
        val peerStates = createPeerStates()
        val joinedEvents = Channel<String>(Channel.BUFFERED)

        val broadcastFn: (ByteArray) -> Unit = { payload ->
            val message = "$peerId:${payload.decodeToString()}"
            peerStates.snapshot().values.forEach { state ->
                transport.sendUdp(state.info.address, state.info.port, message)
            }
        }

        val connection = RawPeerNetConnection(peerId, incoming, outgoing, broadcastFn)

        coroutineScope {
            val childTasks = launch {
                launch {
                    processReceivedPackets(
                        transport.receivedPackets, peerId, peerStates, incoming,
                        joinedEvents, transport,
                    )
                }
                launch {
                    processServiceDiscoveryEvents(
                        transport.discoveryEvents, peerStates, incoming,
                        peerId, config.displayName, transport,
                    )
                }
                launch {
                    processJoinedEvents(joinedEvents, peerStates, incoming, peerId)
                }
                launch {
                    processOutgoingCommands(outgoing, peerId, peerStates, transport)
                }
                launch {
                    retryUnacknowledgedHandshakes(
                        peerStates, peerId, config.displayName, transport,
                    )
                }
                transport.platformTasks?.let { tasks ->
                    launch { tasks() }
                }
            }

            try {
                coroutineScope { block(connection) }
            } finally {
                incoming.close()
                outgoing.close()
                transport.prepareForTeardown()
                childTasks.cancel()
            }
        }
    }
}

/**
 * Platform-specific peer ID generation.
 */
internal expect fun generatePeerId(): String

/**
 * Platform-specific [PeerStates] factory.
 */
internal expect fun createPeerStates(): PeerStates

/**
 * Processes raw UDP packets: dispatches handshake messages and forwards data to the incoming channel.
 */
private suspend fun processReceivedPackets(
    packets: Flow<ReceivedPacket>,
    peerId: String,
    peerStates: PeerStates,
    incomingChannel: SendChannel<RawPeerMessage>,
    joinedEvents: SendChannel<String>,
    transport: TransportHandle,
) {
    packets.collect { packet ->
        val (fromPeerId, payload) = parseUdpMessage(packet.message, peerId) ?: return@collect
        when {
            payload.startsWith(HANDSHAKE_HELLO) -> {
                handleHelloReceived(
                    fromPeerId, payload, packet.senderAddress, packet.senderPort,
                    peerId, peerStates, joinedEvents, transport,
                )
            }
            payload.startsWith(HANDSHAKE_ACK) -> {
                handleAckReceived(fromPeerId, peerStates, joinedEvents)
            }
            else -> {
                if (peerStates[fromPeerId] != null) {
                    incomingChannel.send(RawPeerMessage.Received(fromPeerId, payload.encodeToByteArray()))
                }
            }
        }
    }
}

private fun handleHelloReceived(
    fromPeerId: String,
    payload: String,
    senderAddress: String,
    senderPort: Int,
    peerId: String,
    peerStates: PeerStates,
    joinedEvents: SendChannel<String>,
    transport: TransportHandle,
) {
    val hello = parseHelloPayload(payload, senderAddress)
    var pAddr = hello.address
    var pPort = hello.port

    // If peer is an Android emulator (sending 10.0.2.15), the emulator's NAT translates
    // both address and port. We must use the packet's actual source address AND port
    // to route responses back through the NAT correctly.
    if (pAddr == "10.0.2.15") {
        pAddr = senderAddress
        pPort = senderPort
    }

    val peerInfo = PeerInfo(id = fromPeerId, name = hello.peerName, address = pAddr, port = pPort)

    val state = peerStates.compute(fromPeerId) { existing ->
        if (existing == null) {
            println("[PeerNet-$peerId] Received HELLO from new peer: ${hello.peerName} ($fromPeerId)")
            PeerState(info = peerInfo, weSeeThemViaDiscovery = true, weAckedThem = true)
        } else {
            existing.copy(info = peerInfo, weSeeThemViaDiscovery = true, weAckedThem = true)
        }
    }!!

    transport.sendUdp(peerInfo.address, peerInfo.port, "$peerId:${formatAckPayload(peerId)}")
    checkAndEmitJoined(fromPeerId, state, joinedEvents)
}

private fun handleAckReceived(
    fromPeerId: String,
    peerStates: PeerStates,
    joinedEvents: SendChannel<String>,
) {
    val state = peerStates.compute(fromPeerId) { existing ->
        existing?.copy(theyAckedUs = true)
    } ?: return
    checkAndEmitJoined(fromPeerId, state, joinedEvents)
}

/**
 * Processes mDNS/Bonjour service discovery events: parses the service name, creates peer state,
 * and initiates handshakes. Also handles service removals (emitting Disconnected events).
 */
private suspend fun processServiceDiscoveryEvents(
    events: ReceiveChannel<ServiceDiscoveryEvent>,
    peerStates: PeerStates,
    incomingChannel: SendChannel<RawPeerMessage>,
    peerId: String,
    peerName: String,
    transport: TransportHandle,
) {
    for (event in events) {
        when (event) {
            is ServiceDiscoveryEvent.Discovered -> {
                val parsed = parseServiceName(event.serviceName) ?: continue
                if (parsed.peerId == peerId) continue

                val peerInfo = PeerInfo(
                    id = parsed.peerId, name = parsed.peerName,
                    address = parsed.address, port = parsed.port,
                )
                peerStates.compute(parsed.peerId) { existing ->
                    if (existing == null) {
                        println("[PeerNet-$peerId] Peer discovered: ${parsed.peerName} (${parsed.peerId})")
                        PeerState(info = peerInfo, weSeeThemViaDiscovery = true)
                    } else {
                        existing.copy(weSeeThemViaDiscovery = true)
                    }
                }
                val helloPayload = formatHelloPayload(peerName, peerId, transport.localAddress, transport.localPort)
                transport.sendUdp(peerInfo.address, peerInfo.port, "$peerId:$helloPayload")
            }
            is ServiceDiscoveryEvent.Removed -> {
                val parsed = parseServiceName(event.serviceName) ?: continue
                val state = peerStates.remove(parsed.peerId)
                if (state?.isJoined == true) {
                    incomingChannel.send(RawPeerMessage.Event.Disconnected(parsed.peerId))
                }
            }
        }
    }
}

/**
 * Processes outgoing commands by routing them through the transport.
 */
private suspend fun processOutgoingCommands(
    outgoingChannel: ReceiveChannel<PeerCommand>,
    peerId: String,
    peerStates: PeerStates,
    transport: TransportHandle,
) {
    for (command in outgoingChannel) {
        when (command) {
            is PeerCommand.SendTo -> {
                peerStates[command.peerId]?.let { state ->
                    transport.sendUdp(state.info.address, state.info.port, "$peerId:${command.payload.decodeToString()}")
                }
            }
            is PeerCommand.Broadcast -> {
                peerStates.snapshot().values.forEach { state ->
                    transport.sendUdp(state.info.address, state.info.port, "$peerId:${command.payload.decodeToString()}")
                }
            }
        }
    }
}

/**
 * Retries HELLO to discovered-but-not-yet-joined peers. UDP is unreliable, so the first
 * HELLO (sent on discovery) may be lost. Retries every 1 second.
 */
private suspend fun retryUnacknowledgedHandshakes(
    peerStates: PeerStates,
    peerId: String,
    peerName: String,
    transport: TransportHandle,
) {
    val helloPayload = formatHelloPayload(peerName, peerId, transport.localAddress, transport.localPort)
    while (true) {
        delay(1.seconds)
        peerStates.snapshot().forEach { (pId, state) ->
            if (state.weSeeThemViaDiscovery && !state.isJoined) {
                transport.sendUdp(state.info.address, state.info.port, "$peerId:$helloPayload")
                peerStates.compute(pId) { it?.copy(weSentHello = true) }
            }
        }
    }
}

