package com.woutwerkman.game.model

import kotlinx.serialization.Serializable
import kotlin.random.Random

/**
 * Unique identifier using timestamp + random component for collision resistance
 */
fun generateId(): String {
    val timestamp = currentTimeMillis()
    val random = Random.nextLong(0, Long.MAX_VALUE).toString(36)
    return "${timestamp.toString(36)}-$random"
}

expect fun currentTimeMillis(): Long

/**
 * Player in the game
 */
@Serializable
data class Player(
    val id: String,
    val name: String,
    val isLocal: Boolean = false
)

/**
 * Peer discovered on the network
 */
@Serializable
data class DiscoveredPeer(
    val id: String,
    val name: String,
    val address: String,
    val port: Int
)

/**
 * Connection request from one peer to another
 */
@Serializable
data class ConnectionRequest(
    val fromPlayer: Player,
    val toPlayerId: String,
    val timestamp: Long
)

/**
 * CRDT-based game state using Last-Write-Wins (LWW) semantics
 */
@Serializable
data class PlayerGameState(
    val playerId: String,
    val playerName: String,
    val value: Int,
    val timestamp: Long // For LWW conflict resolution
)

/**
 * Overall game state containing all players' states
 */
@Serializable
data class GameState(
    val players: Map<String, PlayerGameState> = emptyMap(),
    val gamePhase: GamePhase = GamePhase.WAITING,
    val countdownValue: Int = 3,
    val lastUpdate: Long = 0
)

@Serializable
enum class GamePhase {
    WAITING,      // In lobby, waiting for players
    COUNTDOWN,    // 3-2-1 countdown before game starts
    PLAYING,      // Active gameplay
    WIN_COUNTDOWN, // All zeros, counting down to win
    VOTING,       // Voting to play again or end
    ENDED         // Game/lobby ended
}

/**
 * Lobby state
 */
@Serializable
data class LobbyState(
    val lobbyId: String,
    val hostId: String,
    val players: Map<String, LobbyPlayer> = emptyMap(),
    val phase: LobbyPhase = LobbyPhase.GATHERING,
    val lastUpdate: Long = 0
)

@Serializable
data class LobbyPlayer(
    val player: Player,
    val isReady: Boolean = false,
    val joinedAt: Long
)

@Serializable
enum class LobbyPhase {
    GATHERING,   // Waiting for players and ready status
    COUNTDOWN,   // All ready, counting down
    IN_GAME,     // Game is active
    VOTING       // Voting phase
}

/**
 * Vote for what to do after game ends
 */
@Serializable
data class Vote(
    val playerId: String,
    val choice: VoteChoice,
    val timestamp: Long
)

@Serializable
enum class VoteChoice {
    PLAY_AGAIN,
    END_LOBBY
}

/**
 * CRDT Events for state synchronization
 */
@Serializable
sealed class GameEvent {
    abstract val timestamp: Long
    abstract val sourcePlayerId: String
    
    @Serializable
    data class ButtonPress(
        override val timestamp: Long,
        override val sourcePlayerId: String,
        val targetPlayerId: String,
        val delta: Int // -2 for own button, +1 for others
    ) : GameEvent()
    
    @Serializable
    data class PlayerJoined(
        override val timestamp: Long,
        override val sourcePlayerId: String,
        val player: Player,
        val initialValue: Int
    ) : GameEvent()
    
    @Serializable
    data class PlayerLeft(
        override val timestamp: Long,
        override val sourcePlayerId: String,
        val playerId: String
    ) : GameEvent()
    
    @Serializable
    data class ReadyStateChanged(
        override val timestamp: Long,
        override val sourcePlayerId: String,
        val isReady: Boolean
    ) : GameEvent()
    
    @Serializable
    data class GameStarted(
        override val timestamp: Long,
        override val sourcePlayerId: String
    ) : GameEvent()
    
    @Serializable
    data class VoteCast(
        override val timestamp: Long,
        override val sourcePlayerId: String,
        val choice: VoteChoice
    ) : GameEvent()
    
    @Serializable
    data class PhaseChanged(
        override val timestamp: Long,
        override val sourcePlayerId: String,
        val newPhase: GamePhase
    ) : GameEvent()
}

/**
 * Network messages for P2P communication
 */
@Serializable
sealed class NetworkMessage {
    @Serializable
    data class Discovery(
        val peer: DiscoveredPeer
    ) : NetworkMessage()
    
    @Serializable
    data class ConnectionRequestMsg(
        val request: ConnectionRequest
    ) : NetworkMessage()
    
    @Serializable
    data class ConnectionResponse(
        val fromPlayerId: String,
        val accepted: Boolean,
        val lobbyState: LobbyState? = null // Include lobby state when accepted so requester can join
    ) : NetworkMessage()
    
    @Serializable
    data class GameEventMsg(
        val event: GameEvent
    ) : NetworkMessage()
    
    @Serializable
    data class StateSync(
        val lobbyState: LobbyState,
        val gameState: GameState
    ) : NetworkMessage()
    
    @Serializable
    data class Ping(
        val timestamp: Long
    ) : NetworkMessage()
    
    @Serializable
    data class Pong(
        val originalTimestamp: Long,
        val responseTimestamp: Long
    ) : NetworkMessage()

    @Serializable
    data class PeerLeaving(
        val peerId: String
    ) : NetworkMessage()
}

/**
 * Generate a random odd number between 10 and 20 (inclusive)
 */
fun generateRandomOddNumber(): Int {
    val oddNumbers = listOf(11, 13, 15, 17, 19)
    return oddNumbers.random()
}

/**
 * Generate a random player name with low collision chance
 */
fun generateRandomName(): String {
    val adjectives = listOf(
        "Swift", "Brave", "Clever", "Mighty", "Sneaky",
        "Jolly", "Fierce", "Noble", "Quick", "Sly",
        "Bold", "Calm", "Eager", "Gentle", "Happy"
    )
    val nouns = listOf(
        "Fox", "Bear", "Wolf", "Hawk", "Tiger",
        "Lion", "Eagle", "Shark", "Dragon", "Phoenix",
        "Otter", "Raven", "Viper", "Falcon", "Panther"
    )
    val number = Random.nextInt(100, 1000)
    return "${adjectives.random()}${nouns.random()}$number"
}
