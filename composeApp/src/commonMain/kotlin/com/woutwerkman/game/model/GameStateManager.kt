package com.woutwerkman.game.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * CRDT-based game state manager using Last-Write-Wins (LWW) semantics
 */
class GameStateManager(
    private val localPlayer: Player
) {
    private val _lobbyState = MutableStateFlow<LobbyState?>(null)
    val lobbyState: StateFlow<LobbyState?> = _lobbyState.asStateFlow()
    
    private val _gameState = MutableStateFlow(GameState())
    val gameState: StateFlow<GameState> = _gameState.asStateFlow()
    
    private val _votes = MutableStateFlow<Map<String, Vote>>(emptyMap())
    val votes: StateFlow<Map<String, Vote>> = _votes.asStateFlow()
    
    // Event log for CRDT sync (ordered by timestamp)
    private val eventLog = mutableListOf<GameEvent>()
    
    fun createLobby(): LobbyState {
        val lobby = LobbyState(
            lobbyId = generateId(),
            hostId = localPlayer.id,
            players = mapOf(
                localPlayer.id to LobbyPlayer(
                    player = localPlayer,
                    isReady = false,
                    joinedAt = currentTimeMillis()
                )
            ),
            lastUpdate = currentTimeMillis()
        )
        _lobbyState.value = lobby
        return lobby
    }
    
    fun joinLobby(existingLobby: LobbyState): LobbyState {
        val updatedLobby = existingLobby.copy(
            players = existingLobby.players + (localPlayer.id to LobbyPlayer(
                player = localPlayer,
                isReady = false,
                joinedAt = currentTimeMillis()
            )),
            lastUpdate = currentTimeMillis()
        )
        _lobbyState.value = updatedLobby
        return updatedLobby
    }
    
    fun applyEvent(event: GameEvent): Boolean {
        // Check for duplicate events (idempotency)
        if (eventLog.any { it.timestamp == event.timestamp && it.sourcePlayerId == event.sourcePlayerId }) {
            return false
        }
        
        eventLog.add(event)
        eventLog.sortBy { it.timestamp }
        
        when (event) {
            is GameEvent.ButtonPress -> applyButtonPress(event)
            is GameEvent.PlayerJoined -> applyPlayerJoined(event)
            is GameEvent.PlayerLeft -> applyPlayerLeft(event)
            is GameEvent.ReadyStateChanged -> applyReadyStateChanged(event)
            is GameEvent.GameStarted -> applyGameStarted(event)
            is GameEvent.VoteCast -> applyVoteCast(event)
            is GameEvent.PhaseChanged -> applyPhaseChanged(event)
        }
        
        return true
    }
    
    private fun applyButtonPress(event: GameEvent.ButtonPress) {
        _gameState.update { state ->
            val playerState = state.players[event.targetPlayerId] ?: return@update state
            
            // LWW: Only apply if this event is newer
            if (event.timestamp <= playerState.timestamp) return@update state
            
            val newValue = (playerState.value + event.delta).coerceIn(-25, 25)
            
            state.copy(
                players = state.players + (event.targetPlayerId to playerState.copy(
                    value = newValue,
                    timestamp = event.timestamp
                )),
                lastUpdate = event.timestamp
            )
        }
    }
    
    private fun applyPlayerJoined(event: GameEvent.PlayerJoined) {
        _lobbyState.update { lobby ->
            lobby?.copy(
                players = lobby.players + (event.player.id to LobbyPlayer(
                    player = event.player,
                    isReady = false,
                    joinedAt = event.timestamp
                )),
                lastUpdate = event.timestamp
            )
        }
        
        _gameState.update { state ->
            state.copy(
                players = state.players + (event.player.id to PlayerGameState(
                    playerId = event.player.id,
                    playerName = event.player.name,
                    value = event.initialValue,
                    timestamp = event.timestamp
                )),
                lastUpdate = event.timestamp
            )
        }
    }
    
    private fun applyPlayerLeft(event: GameEvent.PlayerLeft) {
        _lobbyState.update { lobby ->
            lobby?.copy(
                players = lobby.players - event.playerId,
                lastUpdate = event.timestamp
            )
        }
        
        _gameState.update { state ->
            state.copy(
                players = state.players - event.playerId,
                lastUpdate = event.timestamp
            )
        }
    }
    
    private fun applyReadyStateChanged(event: GameEvent.ReadyStateChanged) {
        _lobbyState.update { lobby ->
            val existingPlayer = lobby?.players?.get(event.sourcePlayerId) ?: return@update lobby
            lobby.copy(
                players = lobby.players + (event.sourcePlayerId to existingPlayer.copy(
                    isReady = event.isReady
                )),
                lastUpdate = event.timestamp
            )
        }
    }
    
    private fun applyGameStarted(event: GameEvent.GameStarted) {
        _lobbyState.update { lobby ->
            lobby?.copy(
                phase = LobbyPhase.IN_GAME,
                lastUpdate = event.timestamp
            )
        }
        
        _gameState.update { state ->
            state.copy(
                gamePhase = GamePhase.COUNTDOWN,
                countdownValue = 3,
                lastUpdate = event.timestamp
            )
        }
    }
    
    private fun applyVoteCast(event: GameEvent.VoteCast) {
        _votes.update { votes ->
            val existingVote = votes[event.sourcePlayerId]
            // LWW for votes
            if (existingVote != null && existingVote.timestamp >= event.timestamp) {
                return@update votes
            }
            votes + (event.sourcePlayerId to Vote(
                playerId = event.sourcePlayerId,
                choice = event.choice,
                timestamp = event.timestamp
            ))
        }
    }
    
    private fun applyPhaseChanged(event: GameEvent.PhaseChanged) {
        _gameState.update { state ->
            if (event.timestamp <= state.lastUpdate) return@update state
            state.copy(
                gamePhase = event.newPhase,
                countdownValue = if (event.newPhase == GamePhase.COUNTDOWN || 
                    event.newPhase == GamePhase.WIN_COUNTDOWN) 3 else state.countdownValue,
                lastUpdate = event.timestamp
            )
        }
        
        if (event.newPhase == GamePhase.VOTING) {
            _lobbyState.update { lobby ->
                lobby?.copy(phase = LobbyPhase.VOTING, lastUpdate = event.timestamp)
            }
        }
    }
    
    fun pressButton(targetPlayerId: String): GameEvent.ButtonPress {
        val delta = if (targetPlayerId == localPlayer.id) -2 else 1
        val event = GameEvent.ButtonPress(
            timestamp = currentTimeMillis(),
            sourcePlayerId = localPlayer.id,
            targetPlayerId = targetPlayerId,
            delta = delta
        )
        applyEvent(event)
        return event
    }
    
    fun setReady(isReady: Boolean): GameEvent.ReadyStateChanged {
        val event = GameEvent.ReadyStateChanged(
            timestamp = currentTimeMillis(),
            sourcePlayerId = localPlayer.id,
            isReady = isReady
        )
        applyEvent(event)
        return event
    }
    
    fun startGame(): GameEvent.GameStarted {
        val event = GameEvent.GameStarted(
            timestamp = currentTimeMillis(),
            sourcePlayerId = localPlayer.id
        )
        applyEvent(event)
        return event
    }
    
    fun castVote(choice: VoteChoice): GameEvent.VoteCast {
        val event = GameEvent.VoteCast(
            timestamp = currentTimeMillis(),
            sourcePlayerId = localPlayer.id,
            choice = choice
        )
        applyEvent(event)
        return event
    }
    
    fun changePhase(newPhase: GamePhase): GameEvent.PhaseChanged {
        val event = GameEvent.PhaseChanged(
            timestamp = currentTimeMillis(),
            sourcePlayerId = localPlayer.id,
            newPhase = newPhase
        )
        applyEvent(event)
        return event
    }
    
    fun updateCountdown(value: Int) {
        _gameState.update { it.copy(countdownValue = value) }
    }
    
    fun initializeGameState() {
        val lobby = _lobbyState.value ?: return
        val players = lobby.players.mapValues { (playerId, lobbyPlayer) ->
            PlayerGameState(
                playerId = playerId,
                playerName = lobbyPlayer.player.name,
                value = generateRandomOddNumber(),
                timestamp = currentTimeMillis()
            )
        }
        _gameState.value = GameState(
            players = players,
            gamePhase = GamePhase.COUNTDOWN,
            countdownValue = 3,
            lastUpdate = currentTimeMillis()
        )
    }
    
    fun syncState(lobbyState: LobbyState, gameState: GameState) {
        // Merge using LWW
        _lobbyState.update { current ->
            if (current == null || lobbyState.lastUpdate > current.lastUpdate) {
                lobbyState
            } else {
                current
            }
        }
        
        _gameState.update { current ->
            if (gameState.lastUpdate > current.lastUpdate) {
                gameState
            } else {
                current
            }
        }
    }
    
    fun resetForNewGame() {
        _votes.value = emptyMap()
        initializeGameState()
    }
    
    fun leaveLobby() {
        _lobbyState.value = null
        _gameState.value = GameState()
        _votes.value = emptyMap()
        eventLog.clear()
    }
    
    fun checkAllZeros(): Boolean {
        val state = _gameState.value
        return state.players.isNotEmpty() && state.players.values.all { it.value == 0 }
    }
    
    fun checkVoteResult(): VoteChoice? {
        val currentVotes = _votes.value
        val lobby = _lobbyState.value ?: return null
        
        // All players must vote
        if (currentVotes.size < lobby.players.size) return null
        
        val playAgainCount = currentVotes.values.count { it.choice == VoteChoice.PLAY_AGAIN }
        val endCount = currentVotes.values.count { it.choice == VoteChoice.END_LOBBY }
        
        // Majority wins, tie goes to play again
        return if (endCount > playAgainCount) VoteChoice.END_LOBBY else VoteChoice.PLAY_AGAIN
    }
}
