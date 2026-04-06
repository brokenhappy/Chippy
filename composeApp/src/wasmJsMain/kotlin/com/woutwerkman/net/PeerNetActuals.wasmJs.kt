package com.woutwerkman.net

import kotlinx.coroutines.CoroutineScope

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
