package com.woutwerkman.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow

internal actual class UdpSocket

internal actual suspend fun <T> withUdpSocket(
    peerId: String,
    block: suspend CoroutineScope.(UdpSocket) -> T,
): T = error("UDP not supported on web")

internal actual val UdpSocket.localPort: Int get() = error("UDP not supported on web")

internal actual fun UdpSocket.send(address: String, port: Int, message: String): Unit =
    error("UDP not supported on web")

internal actual fun UdpSocket.receivedPackets(): Flow<ReceivedPacket> =
    error("UDP not supported on web")

internal actual class ServiceDiscovery

internal actual suspend fun <T> withServiceDiscovery(
    peerId: String,
    serviceName: String,
    displayName: String,
    localAddress: String,
    localPort: Int,
    block: suspend CoroutineScope.(ServiceDiscovery) -> T,
): T = error("Service discovery not supported on web")

internal actual val ServiceDiscovery.events: ReceiveChannel<ServiceDiscoveryEvent>
    get() = error("Service discovery not supported on web")

internal actual fun ServiceDiscovery.trySendEvent(event: ServiceDiscoveryEvent): Unit =
    error("Service discovery not supported on web")

internal actual fun generatePeerId(): String = error("Not supported on web")

internal actual fun createPeerStates(): PeerStates = error("Not supported on web")

internal actual fun getLocalIpAddress(): String = error("Not supported on web")

internal actual fun createPlatformTransportConfig(
    config: PeerNetConfig,
    peerId: String,
    localAddress: String,
    socket: UdpSocket,
    sd: ServiceDiscovery,
): PlatformTransportConfig = error("Not supported on web")

internal actual suspend fun <T> withRawPeerNetConnectionImpl(
    config: PeerNetConfig,
    block: suspend CoroutineScope.(RawPeerNetConnection) -> T,
): T = error("Raw peer networking is not supported on web clients — use WebSocketPeerNetConnection instead")
