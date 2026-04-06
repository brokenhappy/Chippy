package com.woutwerkman.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.SendChannel

/**
 * BLE is not useful on JVM desktop — mDNS/UDP covers all discovery needs.
 * The no-op driver provides a sendToPeer that always returns false; channels
 * remain open but will never receive events (closed by withBleTransport).
 */
internal actual suspend fun <T> withBlePlatform(
    peerId: String,
    peerInfoData: ByteArray,
    discoveryChannel: SendChannel<PeerInfo>,
    incomingChannel: SendChannel<Pair<String, ByteArray>>,
    block: suspend CoroutineScope.(sendToPeer: (String, ByteArray) -> Boolean) -> T,
): T = coroutineScope { block { _, _ -> false } }
