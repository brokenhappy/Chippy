package com.woutwerkman.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow

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

// ==================== UdpSocket expect API ====================

internal expect class UdpSocket

/** Structured resource: creates, binds, and closes a UDP socket. */
internal expect suspend fun <T> withUdpSocket(
    peerId: String,
    block: suspend CoroutineScope.(UdpSocket) -> T,
): T

internal expect val UdpSocket.localPort: Int

/**
 * Fire-and-forget UDP send. Not suspend — sendto/DatagramChannel.send are
 * near-instant for single datagrams and not cancellable on any platform.
 * Silently swallows errors (best-effort delivery, like UDP itself).
 */
internal expect fun UdpSocket.send(address: String, port: Int, message: String)

/**
 * Cold flow of incoming packets. Runs the platform receive loop when collected.
 * Truly cancellable on all platforms:
 * - JVM: blocking DatagramChannel.receive() + runInterruptible (InterruptibleChannel)
 * - iOS: non-blocking recvfrom + delay-based polling
 */
internal expect fun UdpSocket.receivedPackets(): Flow<ReceivedPacket>

// ==================== TransportHandle ====================

/**
 * Platform-specific UDP transport and service discovery.
 *
 * Each platform creates a UDP socket, registers an mDNS/Bonjour service, and provides
 * channels for discovery events and a flow of received packets. The handshake protocol
 * consumes these and uses [sendUdp] to respond.
 */
internal class TransportHandle(
    val localAddress: String,
    val localPort: Int,
    /** Service discovery events (peers appearing/disappearing on the network). */
    val discoveryEvents: ReceiveChannel<ServiceDiscoveryEvent>,
    /** Raw UDP packets received on this transport. */
    val receivedPackets: Flow<ReceivedPacket>,
    /** Send a raw UDP message to address:port. Fire-and-forget. */
    val sendUdp: (address: String, port: Int, message: String) -> Unit,
    /**
     * Platform-specific hook called after the handshake protocol is set up.
     * This runs as a child coroutine alongside the handshake infrastructure.
     * iOS uses this for BLE transport and RunLoop driving; Android for emulator probing
     * and mDNS polling. Empty on platforms with no extra needs.
     */
    val platformTasks: (suspend CoroutineScope.() -> Unit)? = null,
    /**
     * Called before child coroutines are cancelled during teardown.
     * Platforms use this to release blocking resources (e.g., destroy processes)
     * so that blocked coroutines can respond to cancellation.
     */
    val prepareForTeardown: () -> Unit = {},
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
