package com.woutwerkman.game.model

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

enum class Screen {
    HOME,
    LOBBY,
    GAME,
    VOTING
}

enum class GamePhase {
    WAITING,
    COUNTDOWN,
    PLAYING,
    WIN_COUNTDOWN,
    VOTING,
    ENDED
}

enum class VoteChoice {
    PLAY_AGAIN,
    END_LOBBY
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
