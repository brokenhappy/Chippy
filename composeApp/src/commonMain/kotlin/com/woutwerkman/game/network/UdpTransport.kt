package com.woutwerkman.game.network

const val DISCOVERY_PORT = 41234
const val GAME_PORT_START = 41235

/**
 * Factory to create network transport using PeerNetConnection.
 * This is now a common implementation that works on all platforms.
 */
fun createUdpNetworkTransport(peerId: String, peerName: String): NetworkTransport {
    return PeerNetTransport(peerId, peerName)
}
