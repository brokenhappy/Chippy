package com.woutwerkman.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.SendChannel

internal actual suspend fun <T> withBlePlatform(
    peerId: String,
    peerInfoData: ByteArray,
    discoveryChannel: SendChannel<PeerInfo>,
    incomingChannel: SendChannel<Pair<String, ByteArray>>,
    block: suspend CoroutineScope.(sendToPeer: (String, ByteArray) -> Boolean) -> T,
): T = error("BLE transport is not supported on web")
