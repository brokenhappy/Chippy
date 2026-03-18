package com.woutwerkman.game.network

import com.woutwerkman.game.model.currentTimeMillis
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Simulated network transport for local development and testing.
 * Uses a shared in-memory message bus to simulate peer-to-peer communication.
 * 
 * In a production app, this would be replaced with real WebRTC or WebSocket transport.
 */
class SimulatedNetworkTransport(
    private val peerId: String
) : NetworkTransport {
    
    private var messageHandler: ((String) -> Unit)? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var isDiscovering = false
    private var isServerRunning = false
    
    companion object {
        // Shared message bus for all simulated peers
        private val messageBus = MutableStateFlow<BusMessage?>(null)
        private val registeredPeers = mutableMapOf<String, SimulatedNetworkTransport>()
        private var portCounter = 8080
        
        data class BusMessage(
            val fromPeerId: String,
            val targetPeerId: String?, // null = broadcast
            val message: String,
            val timestamp: Long
        )
    }
    
    private val localPort = portCounter++
    private val connectedPeers = mutableSetOf<String>()
    
    init {
        registeredPeers[peerId] = this
        
        // Listen to message bus
        scope.launch {
            messageBus.collect { busMessage ->
                if (busMessage == null) return@collect
                if (busMessage.fromPeerId == peerId) return@collect
                
                // Check if message is for us
                if (busMessage.targetPeerId == null || busMessage.targetPeerId == peerId) {
                    messageHandler?.invoke(busMessage.message)
                }
            }
        }
    }
    
    override fun setMessageHandler(handler: (String) -> Unit) {
        messageHandler = handler
    }
    
    override suspend fun startDiscovery() {
        isDiscovering = true
    }
    
    override suspend fun stopDiscovery() {
        isDiscovering = false
    }
    
    override suspend fun broadcast(message: String) {
        messageBus.value = BusMessage(
            fromPeerId = peerId,
            targetPeerId = null,
            message = message,
            timestamp = currentTimeMillis()
        )
    }
    
    override suspend fun sendTo(address: String, port: Int, message: String) {
        // Find peer by port (in simulation, address is ignored)
        val targetPeer = registeredPeers.values.find { it.localPort == port }
        if (targetPeer != null) {
            messageBus.value = BusMessage(
                fromPeerId = peerId,
                targetPeerId = targetPeer.peerId,
                message = message,
                timestamp = currentTimeMillis()
            )
        }
    }
    
    override suspend fun broadcastToConnected(message: String) {
        connectedPeers.forEach { connectedPeerId ->
            messageBus.value = BusMessage(
                fromPeerId = peerId,
                targetPeerId = connectedPeerId,
                message = message,
                timestamp = currentTimeMillis()
            )
        }
    }
    
    override suspend fun connectTo(address: String, port: Int) {
        val targetPeer = registeredPeers.values.find { it.localPort == port }
        if (targetPeer != null) {
            connectedPeers.add(targetPeer.peerId)
            targetPeer.connectedPeers.add(peerId)
        }
    }
    
    override suspend fun startServer() {
        isServerRunning = true
    }
    
    override suspend fun stopServer() {
        isServerRunning = false
    }
    
    override suspend fun disconnectAll() {
        connectedPeers.forEach { connectedPeerId ->
            registeredPeers[connectedPeerId]?.connectedPeers?.remove(peerId)
        }
        connectedPeers.clear()
    }
    
    override fun getLocalAddress(): String = "127.0.0.1"
    
    override fun getLocalPort(): Int = localPort
    
    override fun cleanup() {
        scope.cancel()
        registeredPeers.remove(peerId)
        disconnectFromAll()
    }
    
    private fun disconnectFromAll() {
        connectedPeers.forEach { connectedPeerId ->
            registeredPeers[connectedPeerId]?.connectedPeers?.remove(peerId)
        }
        connectedPeers.clear()
    }
}
