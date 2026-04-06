package com.woutwerkman.net

import kotlinx.coroutines.CoroutineScope
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970
import kotlin.random.Random

internal actual fun generatePeerId(): String {
    val timestamp = (NSDate().timeIntervalSince1970 * 1000).toLong()
    val random = Random.nextLong(0, Long.MAX_VALUE).toString(36)
    return "ios-${timestamp.toString(36)}-$random"
}

internal actual fun createPeerStates(): PeerStates = AtomicPeerStates()

internal actual suspend fun <T> withRawPeerNetConnectionImpl(
    config: PeerNetConfig,
    block: suspend CoroutineScope.(RawPeerNetConnection) -> T
): T = withRawPeerNetConnectionCommon(config, block)
