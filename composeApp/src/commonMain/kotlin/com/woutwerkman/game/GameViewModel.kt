package com.woutwerkman.game

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.woutwerkman.game.model.*
import com.woutwerkman.game.network.NetworkManager
import com.woutwerkman.game.network.createUdpNetworkTransport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class Screen {
    HOME,
    LOBBY,
    GAME,
    VOTING
}

/**
 * Represents a group of connected players (a lobby shown on home screen)
 */
data class ConnectedGroup(
    val lobbyId: String,
    val players: List<Player>,
    val hostId: String
)

class GameViewModel : ViewModel() {

    private val _playerName = MutableStateFlow(generateRandomName())
    val playerName: StateFlow<String> = _playerName.asStateFlow()

    private val _currentScreen = MutableStateFlow(Screen.HOME)
    val currentScreen: StateFlow<Screen> = _currentScreen.asStateFlow()

    private val _showSettings = MutableStateFlow(false)
    val showSettings: StateFlow<Boolean> = _showSettings.asStateFlow()

    private val _countdownValue = MutableStateFlow<Int?>(null)
    val countdownValue: StateFlow<Int?> = _countdownValue.asStateFlow()

    // Connected groups (lobbies) visible on home screen
    private val _connectedGroups = MutableStateFlow<List<ConnectedGroup>>(emptyList())
    val connectedGroups: StateFlow<List<ConnectedGroup>> = _connectedGroups.asStateFlow()

    private var localPlayer: Player? = null
    private var gameStateManager: GameStateManager? = null
    private var networkManager: NetworkManager? = null

    private var countdownJob: Job? = null

    // Exposed flows from managers
    val discoveredPeers: StateFlow<List<DiscoveredPeer>>
        get() = networkManager?.discoveredPeers ?: MutableStateFlow(emptyList())

    val connectedPeers: StateFlow<Set<String>>
        get() = networkManager?.connectedPeers ?: MutableStateFlow(emptySet())

    val pendingRequests: StateFlow<List<ConnectionRequest>>
        get() = networkManager?.pendingRequests ?: MutableStateFlow(emptyList())

    val sentInvites: StateFlow<Set<String>>
        get() = networkManager?.sentInvites ?: MutableStateFlow(emptySet())

    val lobbyState: StateFlow<LobbyState?>
        get() = gameStateManager?.lobbyState ?: MutableStateFlow(null)

    val gameState: StateFlow<GameState>
        get() = gameStateManager?.gameState ?: MutableStateFlow(GameState())

    val votes: StateFlow<Map<String, Vote>>
        get() = gameStateManager?.votes ?: MutableStateFlow(emptyMap())

    init {
        initializePlayer()
    }

    private fun initializePlayer() {
        val playerId = generateId()
        localPlayer = Player(
            id = playerId,
            name = _playerName.value,
            isLocal = true
        )

        gameStateManager = GameStateManager(localPlayer!!)
        networkManager = NetworkManager(localPlayer!!, gameStateManager!!)

        // Set up callback for when our invite is accepted
        networkManager?.onConnectionAccepted = { lobbyState ->
            onConnectionAccepted(lobbyState)
        }

        // Set up real UDP transport for network discovery
        val transport = createUdpNetworkTransport(playerId, _playerName.value)
        networkManager?.setTransport(transport)

        // Start peer discovery
        networkManager?.startDiscovery()

        // Watch for game phase changes
        viewModelScope.launch {
            gameStateManager?.gameState?.collect { state ->
                handleGamePhaseChange(state)
            }
        }

        // Watch for lobby state changes to update connected groups
        viewModelScope.launch {
            gameStateManager?.lobbyState?.collect { lobby ->
                updateConnectedGroups(lobby)
            }
        }
    }

    private fun updateConnectedGroups(lobby: LobbyState?) {
        if (lobby != null && _currentScreen.value == Screen.HOME) {
            val group = ConnectedGroup(
                lobbyId = lobby.lobbyId,
                players = lobby.players.values.map { it.player },
                hostId = lobby.hostId
            )
            _connectedGroups.value = listOf(group)
        } else if (_currentScreen.value == Screen.HOME) {
            _connectedGroups.value = emptyList()
        }
    }

    private fun handleGamePhaseChange(state: GameState) {
        when (state.gamePhase) {
            GamePhase.COUNTDOWN -> {
                if (_currentScreen.value == Screen.LOBBY) {
                    startCountdown(3) {
                        gameStateManager?.changePhase(GamePhase.PLAYING)?.let {
                            networkManager?.broadcastEvent(it)
                        }
                        _currentScreen.value = Screen.GAME
                    }
                }
            }
            GamePhase.PLAYING -> {
                _currentScreen.value = Screen.GAME
                checkForWinCondition()
            }
            GamePhase.WIN_COUNTDOWN -> {
                startCountdown(3) {
                    gameStateManager?.changePhase(GamePhase.VOTING)?.let {
                        networkManager?.broadcastEvent(it)
                    }
                    _currentScreen.value = Screen.VOTING
                }
            }
            GamePhase.VOTING -> {
                _currentScreen.value = Screen.VOTING
            }
            GamePhase.ENDED -> {
                goToHome()
            }
            else -> {}
        }
    }

    private fun checkForWinCondition() {
        viewModelScope.launch {
            gameStateManager?.gameState?.collect { state ->
                if (state.gamePhase == GamePhase.PLAYING && gameStateManager?.checkAllZeros() == true) {
                    gameStateManager?.changePhase(GamePhase.WIN_COUNTDOWN)?.let {
                        networkManager?.broadcastEvent(it)
                    }
                }
            }
        }
    }

    fun updatePlayerName(name: String) {
        _playerName.value = name

        // Broadcast that we're leaving (so others can remove our old name)
        networkManager?.broadcastLeaving()

        // Clear discovered peers (so we don't see our old name)
        networkManager?.clearDiscoveredPeers()

        // Cleanup old network manager
        networkManager?.cleanup()

        // Reinitialize with new name and ID
        initializePlayer()
    }

    fun toggleSettings() {
        _showSettings.update { !it }
    }

    fun closeSettings() {
        _showSettings.value = false
    }

    /**
     * Request to connect with a peer - this initiates lobby creation
     */
    fun requestConnection(peer: DiscoveredPeer) {
        networkManager?.requestConnection(peer)
    }

    /**
     * Accept a connection request - creates a lobby with both players
     */
    fun acceptConnection(request: ConnectionRequest) {
        // Create lobby with ourselves as host
        if (lobbyState.value == null) {
            gameStateManager?.createLobby()
        }

        networkManager?.acceptConnection(request)

        // Go to lobby screen
        _currentScreen.value = Screen.LOBBY
    }

    fun rejectConnection(request: ConnectionRequest) {
        networkManager?.rejectConnection(request)
    }

    /**
     * Enter an existing lobby (after connection was accepted)
     */
    fun enterLobby() {
        if (lobbyState.value != null) {
            _currentScreen.value = Screen.LOBBY
        }
    }

    /**
     * Called when we receive confirmation that our connection request was accepted
     */
    fun onConnectionAccepted(lobbyState: LobbyState) {
        gameStateManager?.joinLobby(lobbyState)
        _currentScreen.value = Screen.LOBBY
    }

    fun toggleReady() {
        val currentLobby = lobbyState.value ?: return
        val currentPlayer = currentLobby.players[localPlayer?.id] ?: return
        val newReadyState = !currentPlayer.isReady

        gameStateManager?.setReady(newReadyState)?.let { event ->
            networkManager?.broadcastEvent(event)
        }

        // Check if all players are ready
        viewModelScope.launch {
            delay(100) // Wait for state update
            checkAllReady()
        }
    }

    private fun checkAllReady() {
        val lobby = lobbyState.value ?: return
        if (lobby.players.size >= 2 && lobby.players.values.all { it.isReady }) {
            // All players ready (need at least 2), start countdown
            gameStateManager?.initializeGameState()
            gameStateManager?.startGame()?.let { event ->
                networkManager?.broadcastEvent(event)
            }
        }
    }

    private fun startCountdown(from: Int, onComplete: () -> Unit) {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            for (i in from downTo 1) {
                _countdownValue.value = i
                gameStateManager?.updateCountdown(i)
                delay(1000)
            }
            _countdownValue.value = null
            onComplete()
        }
    }

    fun pressButton(targetPlayerId: String) {
        val state = gameState.value
        if (state.gamePhase != GamePhase.PLAYING) return

        gameStateManager?.pressButton(targetPlayerId)?.let { event ->
            networkManager?.broadcastEvent(event)
        }

        // Check win condition
        if (gameStateManager?.checkAllZeros() == true && state.gamePhase == GamePhase.PLAYING) {
            gameStateManager?.changePhase(GamePhase.WIN_COUNTDOWN)?.let {
                networkManager?.broadcastEvent(it)
            }
        }
    }

    fun castVote(choice: VoteChoice) {
        gameStateManager?.castVote(choice)?.let { event ->
            networkManager?.broadcastEvent(event)
        }

        // Check vote result
        viewModelScope.launch {
            delay(100)
            checkVoteResult()
        }
    }

    private fun checkVoteResult() {
        val result = gameStateManager?.checkVoteResult() ?: return

        when (result) {
            VoteChoice.PLAY_AGAIN -> {
                gameStateManager?.resetForNewGame()
                gameStateManager?.changePhase(GamePhase.COUNTDOWN)?.let {
                    networkManager?.broadcastEvent(it)
                }
                _currentScreen.value = Screen.LOBBY

                // Reset ready states
                lobbyState.value?.players?.keys?.forEach { playerId ->
                    if (playerId == localPlayer?.id) {
                        gameStateManager?.setReady(false)?.let { event ->
                            networkManager?.broadcastEvent(event)
                        }
                    }
                }
            }
            VoteChoice.END_LOBBY -> {
                gameStateManager?.changePhase(GamePhase.ENDED)?.let {
                    networkManager?.broadcastEvent(it)
                }
                goToHome()
            }
        }
    }

    fun leaveLobby() {
        countdownJob?.cancel()
        networkManager?.disconnect()
        gameStateManager?.leaveLobby()
        _currentScreen.value = Screen.HOME
        _countdownValue.value = null
        _connectedGroups.value = emptyList()
    }

    private fun goToHome() {
        countdownJob?.cancel()
        networkManager?.disconnect()
        networkManager?.stopHosting()
        gameStateManager?.leaveLobby()
        _currentScreen.value = Screen.HOME
        _countdownValue.value = null
        _connectedGroups.value = emptyList()
    }

    fun getLocalPlayerId(): String = localPlayer?.id ?: ""

    override fun onCleared() {
        super.onCleared()
        networkManager?.cleanup()
    }
}
