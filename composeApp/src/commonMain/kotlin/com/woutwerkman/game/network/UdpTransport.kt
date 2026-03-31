package com.woutwerkman.game.network

/**
 * Factory to create network transport using PeerNetConnection.
 * This is a common implementation that works on all platforms.
 */
fun createUdpNetworkTransport(peerId: String, peerName: String): NetworkTransport {
    return PeerNetTransport(peerId, peerName)
}
