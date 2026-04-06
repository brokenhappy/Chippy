package com.woutwerkman.net

import kotlinx.coroutines.CoroutineScope
import kotlin.random.Random

internal actual fun generatePeerId(): String {
    val timestamp = System.currentTimeMillis()
    val random = Random.nextLong(0, Long.MAX_VALUE).toString(36)
    return "android-${timestamp.toString(36)}-$random"
}

internal actual fun createPeerStates(): PeerStates = createPeerStatesImpl()

internal actual suspend fun <T> withRawPeerNetConnectionImpl(
    config: PeerNetConfig,
    block: suspend CoroutineScope.(RawPeerNetConnection) -> T
): T = withRawPeerNetConnectionCommon(config, block)
