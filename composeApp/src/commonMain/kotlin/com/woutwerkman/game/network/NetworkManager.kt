package com.woutwerkman.game.network

import com.woutwerkman.game.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Manages P2P network communication for the game.
 * Uses WebSocket-based communication for peer-to-peer connections.
 */
class NetworkManager(
    private val localPlayer: Player,
    private val gameStateManager: GameStateManager
) {
    // Callback when our connection request is accepted
    var onConnectionAccepted: ((LobbyState) -> Unit)? = null
    private val json = Json { 
        ignoreUnknownKeys = true 
        encodeDefaults = true
    }
    
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    private val _discoveredPeers = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    val discoveredPeers: StateFlow<List<DiscoveredPeer>> = _discoveredPeers.asStateFlow()

    // Track when we last saw each peer for timeout
    private val peerLastSeen = mutableMapOf<String, Long>()
    private val PEER_TIMEOUT_MS = 10_000L // Remove peers not seen for 10 seconds
    
    private val _connectedPeers = MutableStateFlow<Set<String>>(emptySet())
    val connectedPeers: StateFlow<Set<String>> = _connectedPeers.asStateFlow()
    
    private val _pendingRequests = MutableStateFlow<List<ConnectionRequest>>(emptyList())
    val pendingRequests: StateFlow<List<ConnectionRequest>> = _pendingRequests.asStateFlow()

    // Invites we sent that are waiting for response
    private val _sentInvites = MutableStateFlow<Set<String>>(emptySet()) // Set of peer IDs
    val sentInvites: StateFlow<Set<String>> = _sentInvites.asStateFlow()

    private val _isHosting = MutableStateFlow(false)
    val isHosting: StateFlow<Boolean> = _isHosting.asStateFlow()
    
    // Platform-specific transport will be injected
    private var transport: NetworkTransport? = null
    
    fun setTransport(transport: NetworkTransport) {
        this.transport = transport
        transport.setMessageHandler { message ->
            handleMessage(message)
        }
    }
    
    fun startDiscovery() {
        scope.launch {
            transport?.startDiscovery()

            // Broadcast our presence periodically
            while (isActive) {
                broadcastPresence()
                delay(2000)
            }
        }

        // Start peer timeout checker
        scope.launch {
            while (isActive) {
                delay(3000)
                val now = currentTimeMillis()
                val timedOutPeers = peerLastSeen.filter { now - it.value > PEER_TIMEOUT_MS }.keys
                if (timedOutPeers.isNotEmpty()) {
                    _discoveredPeers.update { peers ->
                        peers.filter { it.id !in timedOutPeers }
                    }
                    timedOutPeers.forEach { peerLastSeen.remove(it) }
                    // Also remove pending requests from timed out peers
                    _pendingRequests.update { requests ->
                        requests.filter { it.fromPlayer.id !in timedOutPeers }
                    }
                }
            }
        }
    }
    
    fun stopDiscovery() {
        scope.launch {
            transport?.stopDiscovery()
        }
    }
    
    private suspend fun broadcastPresence() {
        val peer = DiscoveredPeer(
            id = localPlayer.id,
            name = localPlayer.name,
            address = transport?.getLocalAddress() ?: "unknown",
            port = transport?.getLocalPort() ?: 0
        )
        val message = NetworkMessage.Discovery(peer)
        transport?.broadcast(json.encodeToString(message))
    }
    
    fun requestConnection(peer: DiscoveredPeer) {
        scope.launch {
            val request = ConnectionRequest(
                fromPlayer = localPlayer,
                toPlayerId = peer.id,
                timestamp = currentTimeMillis()
            )
            val message = NetworkMessage.ConnectionRequestMsg(request)
            // Send to discovery port since that's where peers are listening
            transport?.sendTo(peer.address, DISCOVERY_PORT, json.encodeToString(message))

            // Track this as a pending sent invite
            _sentInvites.update { it + peer.id }
        }
    }
    
    fun acceptConnection(request: ConnectionRequest) {
        scope.launch {
            _pendingRequests.update { it - request }
            _connectedPeers.update { it + request.fromPlayer.id }

            // Add them to the lobby first
            val event = GameEvent.PlayerJoined(
                timestamp = currentTimeMillis(),
                sourcePlayerId = localPlayer.id,
                player = request.fromPlayer,
                initialValue = generateRandomOddNumber()
            )
            gameStateManager.applyEvent(event)

            // Send response with current lobby state so they can join
            val response = NetworkMessage.ConnectionResponse(
                fromPlayerId = localPlayer.id,
                accepted = true,
                lobbyState = gameStateManager.lobbyState.value
            )

            // Find the peer's address
            val peer = _discoveredPeers.value.find { it.id == request.fromPlayer.id }
            if (peer != null) {
                // Send response to discovery port since that's where peers are listening
                transport?.sendTo(peer.address, DISCOVERY_PORT, json.encodeToString(response))
                transport?.connectTo(peer.address, DISCOVERY_PORT)
            }

            broadcastEvent(event)
        }
    }
    
    fun rejectConnection(request: ConnectionRequest) {
        scope.launch {
            _pendingRequests.update { it - request }

            val response = NetworkMessage.ConnectionResponse(
                fromPlayerId = localPlayer.id,
                accepted = false
            )

            val peer = _discoveredPeers.value.find { it.id == request.fromPlayer.id }
            if (peer != null) {
                transport?.sendTo(peer.address, DISCOVERY_PORT, json.encodeToString(response))
            }
        }
    }
    
    fun broadcastEvent(event: GameEvent) {
        scope.launch {
            val message = NetworkMessage.GameEventMsg(event)
            transport?.broadcastToConnected(json.encodeToString(message))
        }
    }
    
    fun broadcastStateSync() {
        scope.launch {
            val lobbyState = gameStateManager.lobbyState.value ?: return@launch
            val gameState = gameStateManager.gameState.value
            val message = NetworkMessage.StateSync(lobbyState, gameState)
            transport?.broadcastToConnected(json.encodeToString(message))
        }
    }
    
    private fun handleMessage(rawMessage: String) {
        try {
            // Try to parse as different message types
            val message = parseMessage(rawMessage) ?: return
            
            when (message) {
                is NetworkMessage.Discovery -> handleDiscovery(message)
                is NetworkMessage.ConnectionRequestMsg -> handleConnectionRequest(message)
                is NetworkMessage.ConnectionResponse -> handleConnectionResponse(message)
                is NetworkMessage.GameEventMsg -> handleGameEvent(message)
                is NetworkMessage.StateSync -> handleStateSync(message)
                is NetworkMessage.Ping -> handlePing(message)
                is NetworkMessage.Pong -> { /* Handle latency tracking if needed */ }
                is NetworkMessage.PeerLeaving -> handlePeerLeaving(message)
            }
        } catch (e: Exception) {
            // Log error but don't crash
            println("Error handling message: ${e.message}")
        }
    }
    
    private fun parseMessage(rawMessage: String): NetworkMessage? {
        return try {
            // Try each message type
            tryParse<NetworkMessage.Discovery>(rawMessage)
                ?: tryParse<NetworkMessage.ConnectionRequestMsg>(rawMessage)
                ?: tryParse<NetworkMessage.ConnectionResponse>(rawMessage)
                ?: tryParse<NetworkMessage.GameEventMsg>(rawMessage)
                ?: tryParse<NetworkMessage.StateSync>(rawMessage)
                ?: tryParse<NetworkMessage.Ping>(rawMessage)
                ?: tryParse<NetworkMessage.Pong>(rawMessage)
                ?: tryParse<NetworkMessage.PeerLeaving>(rawMessage)
        } catch (e: Exception) {
            null
        }
    }
    
    private inline fun <reified T : NetworkMessage> tryParse(rawMessage: String): T? {
        return try {
            json.decodeFromString<T>(rawMessage)
        } catch (e: Exception) {
            null
        }
    }
    
    private fun handleDiscovery(message: NetworkMessage.Discovery) {
        if (message.peer.id == localPlayer.id) return // Ignore our own broadcasts

        // Update last seen time
        peerLastSeen[message.peer.id] = currentTimeMillis()

        _discoveredPeers.update { peers ->
            val existing = peers.find { it.id == message.peer.id }
            if (existing != null) {
                peers.map { if (it.id == message.peer.id) message.peer else it }
            } else {
                peers + message.peer
            }
        }
    }
    
    private fun handleConnectionRequest(message: NetworkMessage.ConnectionRequestMsg) {
        // Also update last seen for the requester
        peerLastSeen[message.request.fromPlayer.id] = currentTimeMillis()
        _pendingRequests.update { it + message.request }
    }

    private fun handlePeerLeaving(message: NetworkMessage.PeerLeaving) {
        // Remove peer from discovered list immediately
        peerLastSeen.remove(message.peerId)
        _discoveredPeers.update { peers ->
            peers.filter { it.id != message.peerId }
        }
        _pendingRequests.update { requests ->
            requests.filter { it.fromPlayer.id != message.peerId }
        }
        _sentInvites.update { it - message.peerId }
    }
    
    private fun handleConnectionResponse(message: NetworkMessage.ConnectionResponse) {
        // Clear from sent invites
        _sentInvites.update { it - message.fromPlayerId }

        if (message.accepted) {
            _connectedPeers.update { it + message.fromPlayerId }

            val peer = _discoveredPeers.value.find { it.id == message.fromPlayerId }
            if (peer != null) {
                scope.launch {
                    transport?.connectTo(peer.address, peer.port)
                }
            }

            // If lobby state was included, notify ViewModel to join lobby
            message.lobbyState?.let { lobbyState ->
                onConnectionAccepted?.invoke(lobbyState)
            }
        }
    }
    
    private fun handleGameEvent(message: NetworkMessage.GameEventMsg) {
        gameStateManager.applyEvent(message.event)
    }
    
    private fun handleStateSync(message: NetworkMessage.StateSync) {
        gameStateManager.syncState(message.lobbyState, message.gameState)
    }
    
    private fun handlePing(message: NetworkMessage.Ping) {
        scope.launch {
            val pong = NetworkMessage.Pong(
                originalTimestamp = message.timestamp,
                responseTimestamp = currentTimeMillis()
            )
            transport?.broadcastToConnected(json.encodeToString(pong))
        }
    }
    
    fun startHosting() {
        _isHosting.value = true
        scope.launch {
            transport?.startServer()
        }
    }
    
    fun stopHosting() {
        _isHosting.value = false
        scope.launch {
            transport?.stopServer()
        }
    }
    
    fun disconnect() {
        scope.launch {
            val event = GameEvent.PlayerLeft(
                timestamp = currentTimeMillis(),
                sourcePlayerId = localPlayer.id,
                playerId = localPlayer.id
            )
            broadcastEvent(event)
            
            transport?.disconnectAll()
            _connectedPeers.value = emptySet()
        }
    }
    
    fun broadcastLeaving() {
        scope.launch {
            val message = NetworkMessage.PeerLeaving(localPlayer.id)
            transport?.broadcast(json.encodeToString(message))
        }
    }

    fun clearDiscoveredPeers() {
        _discoveredPeers.value = emptyList()
        peerLastSeen.clear()
        _pendingRequests.value = emptyList()
        _sentInvites.value = emptySet()
    }

    fun cleanup() {
        scope.cancel()
        transport?.cleanup()
    }
}

/**
 * Platform-specific network transport interface
 */
interface NetworkTransport {
    fun setMessageHandler(handler: (String) -> Unit)
    suspend fun startDiscovery()
    suspend fun stopDiscovery()
    suspend fun broadcast(message: String)
    suspend fun sendTo(address: String, port: Int, message: String)
    suspend fun broadcastToConnected(message: String)
    suspend fun connectTo(address: String, port: Int)
    suspend fun startServer()
    suspend fun stopServer()
    suspend fun disconnectAll()
    fun getLocalAddress(): String
    fun getLocalPort(): Int
    fun cleanup()
}
