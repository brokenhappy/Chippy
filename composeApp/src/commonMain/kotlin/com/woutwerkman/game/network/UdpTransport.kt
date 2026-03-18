package com.woutwerkman.game.network

const val DISCOVERY_PORT = 41234
const val GAME_PORT_START = 41235

/**
 * Factory to create platform-specific UDP transport
 */
expect fun createUdpNetworkTransport(peerId: String, peerName: String): NetworkTransport
