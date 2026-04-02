package com.woutwerkman.net

import kotlinx.coroutines.CoroutineScope

internal actual suspend fun <T> withRawPeerNetConnectionImpl(
    config: PeerNetConfig,
    block: suspend CoroutineScope.(RawPeerNetConnection) -> T,
): T {
    error("Raw peer networking is not supported on web clients — use WebSocketPeerNetConnection instead")
}
