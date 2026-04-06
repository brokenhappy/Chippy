package com.woutwerkman.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970
import kotlin.random.Random

internal actual fun generatePeerId(): String {
    val timestamp = (NSDate().timeIntervalSince1970 * 1000).toLong()
    val random = Random.nextLong(0, Long.MAX_VALUE).toString(36)
    return "ios-${timestamp.toString(36)}-$random"
}

internal actual fun createPeerStates(): PeerStates = AtomicPeerStates()

internal actual suspend fun <T> withTransport(
    config: PeerNetConfig,
    peerId: String,
    block: suspend CoroutineScope.(TransportHandle) -> T,
): T = error("iOS uses withIosPeerTransport directly — this is not called")

internal actual suspend fun <T> withRawPeerNetConnectionImpl(
    config: PeerNetConfig,
    block: suspend CoroutineScope.(RawPeerNetConnection) -> T
): T {
    val incoming = Channel<RawPeerMessage>(Channel.BUFFERED)
    val outgoing = Channel<PeerCommand>(Channel.BUFFERED)
    val peerId = generatePeerId()

    return withIosPeerTransport(
        config = config,
        peerId = peerId,
        incomingChannel = incoming,
        outgoingChannel = outgoing,
    ) { broadcastFn ->
        val connection = RawPeerNetConnection(peerId, incoming, outgoing, broadcastFn)
        try {
            block(connection)
        } finally {
            incoming.close()
            outgoing.close()
        }
    }
}
