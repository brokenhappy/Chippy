package com.woutwerkman.game.network

import com.woutwerkman.game.model.DiscoveredPeer
import com.woutwerkman.game.model.NetworkMessage
import com.woutwerkman.net.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json

/**
 * NetworkTransport implementation that uses PeerNetConnection (linearized, with gossip relay)
 * for peer discovery and messaging.
 *
 * Gossip relay ensures that peers which can't reach each other directly (e.g., Android emulator
 * and iPhone) can still communicate through any bridge peer (e.g., JVM host).
 */
class PeerNetTransport(
    private val peerId: String,
    private val peerName: String
) : NetworkTransport {

    private var messageHandler: ((String) -> Unit)? = null
    private var connection: PeerNetConnection? = null
    private var connectionJob: Job? = null
    private var currentPeers: Map<String, PeerInfo> = emptyMap()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun setMessageHandler(handler: (String) -> Unit) {
        messageHandler = handler
    }

    override suspend fun startDiscovery() {
        connectionJob?.cancel()

        val config = PeerNetConfig(
            serviceName = "chippy",
            displayName = peerName
        )

        connectionJob = CoroutineScope(Dispatchers.Default + SupervisorJob()).launch {
            withPeerNetConnection(config) { conn ->
                connection = conn

                // Process incoming app messages via gossip relay
                launch {
                    for (msg in conn.messages) {
                        println("PeerNetTransport: Received relay from ${msg.fromPeerId}: ${msg.payload.take(50)}...")
                        messageHandler?.invoke(msg.payload)
                    }
                }

                // Watch linearized state for peer discovery
                launch {
                    var previousPeerIds = emptySet<String>()
                    conn.state.collect { state ->
                        val otherPeers = state.discoveredPeers.filterKeys { it != conn.localId }
                        currentPeers = state.discoveredPeers

                        val currentIds = otherPeers.keys

                        // New peers
                        for (id in currentIds - previousPeerIds) {
                            val peer = otherPeers[id] ?: continue
                            println("PeerNetTransport: Peer joined ${peer.name} (${peer.id})")
                            emitDiscovery(peer)
                        }

                        // Removed peers
                        for (id in previousPeerIds - currentIds) {
                            println("PeerNetTransport: Peer left $id")
                            val leavingMsg = NetworkMessage.PeerLeaving(id)
                            messageHandler?.invoke(json.encodeToString(leavingMsg))
                        }

                        previousPeerIds = currentIds
                    }
                }

                // Periodic re-broadcast of discovery to keep NetworkManager's peerLastSeen alive
                launch {
                    while (isActive) {
                        delay(2000)
                        for ((id, peer) in currentPeers) {
                            if (id != conn.localId) {
                                emitDiscovery(peer)
                            }
                        }
                    }
                }

                awaitCancellation()
            }
        }
    }

    private fun emitDiscovery(peer: PeerInfo) {
        val discoveryPeer = DiscoveredPeer(
            id = peer.id,
            name = peer.name,
            address = peer.address,
            port = peer.port
        )
        val discoveryMsg = NetworkMessage.Discovery(discoveryPeer)
        messageHandler?.invoke(json.encodeToString(discoveryMsg))
    }

    override suspend fun stopDiscovery() {
        connectionJob?.cancel()
        connectionJob = null
        connection = null
    }

    override suspend fun broadcast(message: String) {
        try {
            connection?.broadcast(message)
        } catch (e: Exception) {
            println("PeerNetTransport: Failed to broadcast: ${e.message}")
        }
    }

    override suspend fun sendTo(address: String, port: Int, message: String) {
        val peer = currentPeers.values.find { it.address == address && it.port == port }
        if (peer != null) {
            try {
                connection?.sendTo(peer.id, message)
            } catch (e: Exception) {
                println("PeerNetTransport: Failed to send to ${peer.id}: ${e.message}")
            }
        } else {
            println("PeerNetTransport: No peer found at $address:$port, falling back to broadcast")
            broadcast(message)
        }
    }

    override suspend fun broadcastToConnected(message: String) {
        broadcast(message)
    }

    override suspend fun connectTo(address: String, port: Int) {
        println("PeerNetTransport: connectTo called for $address:$port (handled by discovery)")
    }

    override suspend fun startServer() {
        println("PeerNetTransport: startServer called (handled by discovery)")
    }

    override suspend fun stopServer() {
        println("PeerNetTransport: stopServer called (handled by stopDiscovery)")
    }

    override suspend fun disconnectAll() {
        currentPeers = emptyMap()
    }

    override fun getLocalAddress(): String = "0.0.0.0"

    override fun getLocalPort(): Int = 0

    override fun cleanup() {
        connectionJob?.cancel()
        connectionJob = null
        connection = null
        currentPeers = emptyMap()
    }
}
