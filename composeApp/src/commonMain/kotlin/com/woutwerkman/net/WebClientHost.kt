package com.woutwerkman.net

import kotlinx.coroutines.flow.Flow

/**
 * A locally-hosted web server that lets browser clients join the game.
 *
 * Web clients connect via WebSocket and participate as thin clients —
 * all gossip and linearization stays on the host device.
 * Only supported on JVM and Android (Ktor CIO); returns null on iOS.
 */
interface WebClientHost {
    /** The URL to access the web client, or null if not yet started. */
    val url: String?

    /** QR code PNG bytes encoding the [url], or null if not available. */
    val qrCodeBytes: ByteArray?

    /** Start the embedded web server. */
    suspend fun start()

    /** Stop the embedded web server and disconnect all web clients. */
    fun stop()
}

/**
 * Create a [WebClientHost] for this platform, or null if not supported.
 */
expect fun createWebClientHost(
    connection: PeerNetConnection,
    stateFlow: Flow<PeerNetState>,
): WebClientHost?
