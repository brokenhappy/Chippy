package com.woutwerkman.game.network

import com.woutwerkman.game.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Manages P2P network communication for the game.
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

    // Message deduplication: bounded set of recently seen messageIds
    private val seenMessageIds = LinkedHashSet<String>()
    private val MAX_SEEN_MESSAGES = 500

    fun setTransport(transport: NetworkTransport) {
        this.transport = transport
        transport.setMessageHandler { message ->
            handleMessage(message)
        }
    }

    fun startDiscovery() {
        scope.launch {
            transport?.startDiscovery()
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
                    _pendingRequests.update { requests ->
                        requests.filter { it.fromPlayer.id !in timedOutPeers }
                    }
                }
            }
        }

        // Periodic state sync heartbeat for CRDT convergence
        scope.launch {
            while (isActive) {
                delay(5000)
                if (_connectedPeers.value.isNotEmpty()) {
                    broadcastStateSync()
                }
            }
        }
    }

    fun stopDiscovery() {
        scope.launch {
            transport?.stopDiscovery()
        }
    }

    fun requestConnection(peer: DiscoveredPeer) {
        scope.launch {
            val request = ConnectionRequest(
                fromPlayer = localPlayer,
                toPlayerId = peer.id,
                timestamp = currentTimeMillis()
            )
            val message = NetworkMessage.ConnectionRequestMsg(request)
            sendWithRetry(peer.address, peer.port, json.encodeToString(message))

            _sentInvites.update { it + peer.id }
        }
    }

    fun acceptConnection(request: ConnectionRequest) {
        scope.launch {
            _pendingRequests.update { it - request }
            _connectedPeers.update { it + request.fromPlayer.id }

            val event = GameEvent.PlayerJoined(
                timestamp = currentTimeMillis(),
                sourcePlayerId = localPlayer.id,
                player = request.fromPlayer,
                initialValue = generateRandomOddNumber()
            )
            gameStateManager.applyEvent(event)

            val response = NetworkMessage.ConnectionResponse(
                fromPlayerId = localPlayer.id,
                accepted = true,
                lobbyState = gameStateManager.lobbyState.value,
                gameState = gameStateManager.gameState.value
            )

            val peer = _discoveredPeers.value.find { it.id == request.fromPlayer.id }
            if (peer != null) {
                sendWithRetry(peer.address, peer.port, json.encodeToString(response))
                transport?.connectTo(peer.address, peer.port)
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
                sendWithRetry(peer.address, peer.port, json.encodeToString(response))
            }
        }
    }

    fun broadcastEvent(event: GameEvent) {
        scope.launch {
            val message = NetworkMessage.GameEventMsg(event)
            val encoded = json.encodeToString(message)
            // Retry critical events (non-ButtonPress)
            if (event !is GameEvent.ButtonPress) {
                broadcastWithRetry(encoded)
            } else {
                transport?.broadcastToConnected(encoded)
            }
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

    /**
     * Send a pre-encoded message to a specific peer with retries for reliability.
     */
    private suspend fun sendWithRetry(address: String, port: Int, encoded: String, retries: Int = 3) {
        repeat(retries) { attempt ->
            transport?.sendTo(address, port, encoded)
            if (attempt < retries - 1) delay(500)
        }
    }

    /**
     * Broadcast a message with retries for reliability.
     */
    private suspend fun broadcastWithRetry(encoded: String, retries: Int = 3) {
        repeat(retries) { attempt ->
            transport?.broadcastToConnected(encoded)
            if (attempt < retries - 1) delay(500)
        }
    }

    /**
     * Check if a message has already been seen. Returns true if it's a duplicate.
     */
    private fun isDuplicate(messageId: String): Boolean {
        if (messageId in seenMessageIds) return true
        seenMessageIds.add(messageId)
        // Keep the set bounded
        if (seenMessageIds.size > MAX_SEEN_MESSAGES) {
            val iterator = seenMessageIds.iterator()
            iterator.next()
            iterator.remove()
        }
        return false
    }

    private fun handleMessage(rawMessage: String) {
        try {
            val message = parseMessage(rawMessage)
            if (message == null) {
                println("NetworkManager: Failed to parse message: ${rawMessage.take(100)}...")
                return
            }

            // Deduplicate (skip for Discovery since it's high-frequency heartbeat)
            if (message !is NetworkMessage.Discovery && isDuplicate(message.messageId)) {
                return
            }

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
            println("Error handling message: ${e.message}")
        }
    }

    private fun parseMessage(rawMessage: String): NetworkMessage? {
        return try {
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
        if (message.peer.id == localPlayer.id) return

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
        println("NetworkManager: handleConnectionRequest from ${message.request.fromPlayer.name} (${message.request.fromPlayer.id})")
        peerLastSeen[message.request.fromPlayer.id] = currentTimeMillis()
        _pendingRequests.update { requests ->
            // Avoid duplicate requests from the same player
            if (requests.any { it.fromPlayer.id == message.request.fromPlayer.id }) {
                requests
            } else {
                requests + message.request
            }
        }
    }

    private fun handlePeerLeaving(message: NetworkMessage.PeerLeaving) {
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
        _sentInvites.update { it - message.fromPlayerId }

        if (message.accepted) {
            _connectedPeers.update { it + message.fromPlayerId }

            val peer = _discoveredPeers.value.find { it.id == message.fromPlayerId }
            if (peer != null) {
                scope.launch {
                    transport?.connectTo(peer.address, peer.port)
                }
            }

            // Sync lobby and game state from the accepting peer
            message.lobbyState?.let { lobbyState ->
                val gameState = message.gameState
                if (gameState != null) {
                    gameStateManager.syncState(lobbyState, gameState)
                }
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
