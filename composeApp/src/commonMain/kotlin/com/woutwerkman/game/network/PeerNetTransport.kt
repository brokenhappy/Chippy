package com.woutwerkman.game.network

import com.woutwerkman.game.model.DiscoveredPeer
import com.woutwerkman.game.model.NetworkMessage
import com.woutwerkman.net.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json

/**
 * NetworkTransport implementation that uses PeerNetConnection for peer discovery and messaging.
 * This adapter bridges the channel-based PeerNetConnection API with the callback-based NetworkTransport API.
 */
class PeerNetTransport(
    private val peerId: String,
    private val peerName: String
) : NetworkTransport {

    private var messageHandler: ((String) -> Unit)? = null
    private var connection: PeerNetConnection? = null
    private var scope: CoroutineScope? = null
    private var connectionJob: Job? = null

    private val discoveredPeers = MutableStateFlow<Map<String, PeerInfo>>(emptyMap())
    private val previouslySeenPeers = mutableSetOf<String>()
    private var localAddress: String = "0.0.0.0"
    private var localPort: Int = 0

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun setMessageHandler(handler: (String) -> Unit) {
        messageHandler = handler
    }

    override suspend fun startDiscovery() {
        // Cancel any existing connection
        connectionJob?.cancel()

        val config = PeerNetConfig(
            serviceName = "chippy",
            displayName = peerName
            // Run indefinitely
        )

        // Launch the connection in a new coroutine
        connectionJob = CoroutineScope(Dispatchers.Default + SupervisorJob()).launch {
            scope = this
            withPeerNetConnection(config) { conn ->
                connection = conn

                // Periodically re-broadcast discovery for all known peers
                // This keeps NetworkManager's peerLastSeen updated
                val keepAliveJob = launch {
                    while (isActive) {
                        delay(2000) // Match NetworkManager's broadcastPresence interval
                        discoveredPeers.value.values.forEach { peer ->
                            val discoveryPeer = DiscoveredPeer(
                                id = peer.id,
                                name = peer.name,
                                address = peer.address,
                                port = peer.port
                            )
                            val discoveryMsg = NetworkMessage.Discovery(discoveryPeer)
                            messageHandler?.invoke(json.encodeToString(discoveryMsg))
                        }
                    }
                }

                try {
                    // Process incoming messages
                    for (message in conn.incoming) {
                        when (message) {
                            is PeerMessage.Event.Connected -> {
                                val isReconnection = message.peer.id in previouslySeenPeers
                                println("PeerNetTransport: Peer joined ${message.peer.name} (${message.peer.id})${if (isReconnection) " [reconnection]" else ""}")
                                discoveredPeers.value = discoveredPeers.value + (message.peer.id to message.peer)
                                previouslySeenPeers.add(message.peer.id)

                                // Convert to Discovery message for NetworkManager
                                val discoveryPeer = DiscoveredPeer(
                                    id = message.peer.id,
                                    name = message.peer.name,
                                    address = message.peer.address,
                                    port = message.peer.port
                                )
                                val discoveryMsg = NetworkMessage.Discovery(discoveryPeer)
                                messageHandler?.invoke(json.encodeToString(discoveryMsg))
                            }
                            is PeerMessage.Event.Disconnected -> {
                                println("PeerNetTransport: Peer left ${message.peerId}")
                                discoveredPeers.value = discoveredPeers.value - message.peerId

                                // Convert to PeerLeaving message
                                val leavingMsg = NetworkMessage.PeerLeaving(message.peerId)
                                messageHandler?.invoke(json.encodeToString(leavingMsg))
                            }
                            is PeerMessage.Received -> {
                                val payload = message.payload.decodeToString()
                                println("PeerNetTransport: Received data from ${message.fromPeerId}: ${payload.take(50)}...")
                                messageHandler?.invoke(payload)
                            }
                        }
                    }
                } finally {
                    keepAliveJob.cancel()
                }
            }
        }
    }

    override suspend fun stopDiscovery() {
        connectionJob?.cancel()
        connectionJob = null
        connection = null
    }

    override suspend fun broadcast(message: String) {
        val conn = connection ?: return
        try {
            conn.outgoing.send(PeerCommand.Broadcast(message.encodeToByteArray()))
        } catch (e: Exception) {
            println("PeerNetTransport: Failed to broadcast: ${e.message}")
        }
    }

    override suspend fun sendTo(address: String, port: Int, message: String) {
        val conn = connection ?: return
        // Find peer by address and port
        val peer = discoveredPeers.value.values.find { it.address == address && it.port == port }
        if (peer != null) {
            try {
                conn.outgoing.send(PeerCommand.SendTo(peer.id, message.encodeToByteArray()))
            } catch (e: Exception) {
                println("PeerNetTransport: Failed to send to ${peer.id}: ${e.message}")
            }
        } else {
            println("PeerNetTransport: No peer found at address $address")
        }
    }

    override suspend fun broadcastToConnected(message: String) {
        // For now, broadcast to all discovered peers
        broadcast(message)
    }

    override suspend fun connectTo(address: String, port: Int) {
        // PeerNetConnection handles connections automatically through discovery
        println("PeerNetTransport: connectTo called for $address:$port (handled by discovery)")
    }

    override suspend fun startServer() {
        // PeerNetConnection starts advertising automatically
        println("PeerNetTransport: startServer called (handled by discovery)")
    }

    override suspend fun stopServer() {
        // PeerNetConnection stops advertising when closed
        println("PeerNetTransport: stopServer called (handled by stopDiscovery)")
    }

    override suspend fun disconnectAll() {
        // Just clear tracked peers; actual disconnect happens on stopDiscovery
        discoveredPeers.value = emptyMap()
    }

    override fun getLocalAddress(): String = localAddress

    override fun getLocalPort(): Int = localPort

    override fun cleanup() {
        connectionJob?.cancel()
        connectionJob = null
        connection = null
        discoveredPeers.value = emptyMap()
    }
}
