package com.woutwerkman.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * A raw UDP packet received on the transport.
 */
internal data class ReceivedPacket(
    val message: String,
    val senderAddress: String,
    val senderPort: Int,
)

/**
 * Discovery event from the platform's service discovery mechanism.
 */
internal sealed class ServiceDiscoveryEvent {
    data class Discovered(val serviceName: String) : ServiceDiscoveryEvent()
    data class Removed(val serviceName: String) : ServiceDiscoveryEvent()
}

/**
 * Platform-specific UDP transport and service discovery.
 *
 * Each platform creates a UDP socket, registers an mDNS/Bonjour service, and provides
 * channels for discovery events and received packets. The handshake protocol consumes
 * these channels and uses [sendUdp] to respond.
 */
internal class TransportHandle(
    val localAddress: String,
    val localPort: Int,
    /** Service discovery events (peers appearing/disappearing on the network). */
    val discoveryEvents: ReceiveChannel<ServiceDiscoveryEvent>,
    /** Raw UDP packets received on this transport. */
    val receivedPackets: ReceiveChannel<ReceivedPacket>,
    /** Send a raw UDP message to address:port. Fire-and-forget. */
    val sendUdp: (address: String, port: Int, message: String) -> Unit,
    /**
     * Platform-specific hook called after the handshake protocol is set up.
     * This runs as a child coroutine alongside the handshake infrastructure.
     * iOS uses this for BLE transport and RunLoop driving; Android for emulator probing
     * and mDNS polling. Empty on platforms with no extra needs.
     */
    val platformTasks: (suspend CoroutineScope.() -> Unit)? = null,
)

/**
 * Platform-specific setup: creates a UDP socket, registers an mDNS/Bonjour service, starts
 * receive loops and discovery listeners. Tears down everything when [block] completes.
 *
 * The [TransportHandle] provides raw transport operations. The common handshake protocol
 * in [withRawPeerNetConnectionImpl] consumes these to establish peer connections.
 */
internal expect suspend fun <T> withTransport(
    config: PeerNetConfig,
    peerId: String,
    block: suspend CoroutineScope.(TransportHandle) -> T,
): T
