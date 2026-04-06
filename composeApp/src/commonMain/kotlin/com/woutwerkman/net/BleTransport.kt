package com.woutwerkman.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel

/**
 * Represents a BLE connection providing discovery and data transport.
 */
internal class BleConnection(
    /** Peers discovered via BLE (peerId, address, port). */
    val discoveredPeers: ReceiveChannel<PeerInfo>,
    /** Data received from BLE peers (fromPeerId to payload). */
    val incoming: ReceiveChannel<Pair<String, ByteArray>>,
    /** Send data to a specific peer over BLE. Returns true if sent. */
    val sendToPeer: (peerId: String, data: ByteArray) -> Boolean,
)

/**
 * Runs BLE transport (peripheral + central) for the duration of [block].
 *
 * Common orchestration: encodes peer info, owns channel lifecycle, and wraps the
 * platform driver in a [BleConnection]. Platform-specific BLE operations are
 * delegated to [withBlePlatform].
 */
internal suspend fun <T> withBleTransport(
    peerId: String,
    localAddress: String,
    localPort: Int,
    block: suspend CoroutineScope.(BleConnection) -> T,
): T {
    val discoveryChannel = Channel<PeerInfo>(Channel.BUFFERED)
    val incomingChannel = Channel<Pair<String, ByteArray>>(Channel.BUFFERED)
    val peerInfoData = encodePeerInfoForBle(peerId, localAddress, localPort)
    return try {
        withBlePlatform(peerId, peerInfoData, discoveryChannel, incomingChannel) { sendToPeer ->
            block(BleConnection(discoveryChannel, incomingChannel, sendToPeer))
        }
    } finally {
        discoveryChannel.close()
        incomingChannel.close()
    }
}

/**
 * Platform-specific BLE driver: sets up peripheral (advertising) and central (scanning)
 * roles, populates [discoveryChannel] and [incomingChannel], and provides [sendToPeer].
 *
 * Ownership: channels are owned by [withBleTransport] — do NOT close them here.
 */
internal expect suspend fun <T> withBlePlatform(
    peerId: String,
    peerInfoData: ByteArray,
    discoveryChannel: SendChannel<PeerInfo>,
    incomingChannel: SendChannel<Pair<String, ByteArray>>,
    block: suspend CoroutineScope.(sendToPeer: (String, ByteArray) -> Boolean) -> T,
): T
