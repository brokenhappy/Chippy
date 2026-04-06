package com.woutwerkman.net

import kotlinx.coroutines.CoroutineScope
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

internal actual fun generatePeerId(): String = error("Not supported on web")

internal actual fun createPeerStates(): PeerStates = error("Not supported on web")

internal actual suspend fun <T> withTransport(
    config: PeerNetConfig,
    peerId: String,
    block: suspend CoroutineScope.(TransportHandle) -> T,
): T = error("Raw peer networking is not supported on web clients — use WebSocketPeerNetConnection instead")

internal actual suspend fun <T> withRawPeerNetConnectionImpl(
    config: PeerNetConfig,
    block: suspend CoroutineScope.(RawPeerNetConnection) -> T,
): T = error("Raw peer networking is not supported on web clients — use WebSocketPeerNetConnection instead")
