package com.woutwerkman.net

import kotlinx.coroutines.CoroutineScope

/**
 * Host a web server that lets browser clients join the game.
 *
 * Web clients connect via WebSocket and participate as thin clients —
 * all gossip and linearization stays on the host device.
 *
 * The server runs for the duration of [block] and is stopped when the block
 * completes or is cancelled. All web client sessions are cleaned up automatically.
 */
expect suspend fun <T> hostingWebClient(
    connection: PeerNetConnection,
    block: suspend CoroutineScope.(url: String) -> T,
): T
