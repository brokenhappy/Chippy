package com.woutwerkman.net

import com.woutwerkman.game.model.GamePhase
import com.woutwerkman.game.model.VoteChoice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Tests that exercise the full game lifecycle through [PeerNetState.after].
 *
 * No networking — just event sequences → state. If these pass, every peer will compute
 * the same state because the fold is deterministic.
 */
class GameFlowTest {

    private fun joined(id: String, name: String) =
        PeerEvent.Joined(PeerInfo(id, name, "", 0))

    // -----------------------------------------------------------------------
    // Joining creates a solo lobby
    // -----------------------------------------------------------------------

    @Test
    fun joiningCreatesASoloLobby() {
        val (state, _) = PeerNetState()
            .after(joined("A", "Alice"))

        assertEquals(1, state.lobbies.size)
        val lobby = state.lobbies["A"]!!
        assertEquals(setOf("A"), lobby.players.keys)
        assertEquals("Alice", lobby.players["A"]?.name)
        assertEquals("A", lobby.hostId)
    }

    // -----------------------------------------------------------------------
    // Tapping a peer joins their lobby
    // -----------------------------------------------------------------------

    @Test
    fun joiningPeerLobbyMergesThem() {
        val (state, _) = PeerNetState()
            .after(joined("A", "Alice"))
            .after(joined("B", "Bob"))
            .after(PeerEvent.JoinedLobby(lobbyId = "A", playerId = "B"))

        assertEquals(1, state.lobbies.size)
        val lobby = state.lobbies["A"]!!
        assertEquals(setOf("A", "B"), lobby.players.keys)
        assertEquals("A", lobby.hostId)
    }

    @Test
    fun thirdPlayerJoinsExistingLobby() {
        val (state, _) = PeerNetState()
            .after(joined("A", "Alice"))
            .after(joined("B", "Bob"))
            .after(joined("C", "Charlie"))
            .after(PeerEvent.JoinedLobby(lobbyId = "A", playerId = "B"))
            .after(PeerEvent.JoinedLobby(lobbyId = "A", playerId = "C"))

        assertEquals(1, state.lobbies.size)
        assertEquals(setOf("A", "B", "C"), state.lobbies["A"]!!.players.keys)
    }

    // -----------------------------------------------------------------------
    // Two independent lobbies
    // -----------------------------------------------------------------------

    @Test
    fun twoIndependentLobbiesCoexist() {
        val (state, _) = PeerNetState()
            .after(joined("A", "Alice"))
            .after(joined("B", "Bob"))
            .after(joined("C", "Charlie"))
            .after(joined("D", "Diana"))
            .after(PeerEvent.JoinedLobby(lobbyId = "A", playerId = "B"))
            .after(PeerEvent.JoinedLobby(lobbyId = "C", playerId = "D"))

        assertEquals(2, state.lobbies.size)
        assertEquals(setOf("A", "B"), state.lobbies["A"]!!.players.keys)
        assertEquals(setOf("C", "D"), state.lobbies["C"]!!.players.keys)
    }

    // -----------------------------------------------------------------------
    // Ready up → automatic game start cascade
    // -----------------------------------------------------------------------

    @Test
    fun allReadyCascadesToGameStart() {
        val (state, timedEvents) = PeerNetState()
            .after(joined("A", "Alice"))
            .after(joined("B", "Bob"))
            .after(PeerEvent.JoinedLobby(lobbyId = "A", playerId = "B"))
            .after(PeerEvent.ReadyChanged(lobbyId = "A", playerId = "A", isReady = true))
            .after(PeerEvent.ReadyChanged(lobbyId = "A", playerId = "B", isReady = true))

        val lobby = state.lobbies["A"]!!
        // Cascade: all ready → GameStarted → COUNTDOWN
        assertEquals(GamePhase.COUNTDOWN, lobby.gamePhase)
        assertTrue(lobby.playerValues.isNotEmpty(), "Player values should be set")
        assertTrue(lobby.playerValues.values.all { it in 1..23 && it % 2 == 1 }, "Values should be odd 1-23")

        // Should produce a timed event: first countdown tick (3 → 2) in 1s
        assertNotNull(timedEvents)
        assertEquals(1.seconds, timedEvents.delay)
        val tickEvent = timedEvents.event as PeerEvent.CountdownTick
        assertEquals("A", tickEvent.lobbyId)
        assertEquals(2, tickEvent.value)
    }

    @Test
    fun singlePlayerReadyDoesNotStartGame() {
        val (state, timedEvents) = PeerNetState()
            .after(joined("A", "Alice"))
            .after(joined("B", "Bob"))
            .after(PeerEvent.JoinedLobby(lobbyId = "A", playerId = "B"))
            .after(PeerEvent.ReadyChanged(lobbyId = "A", playerId = "A", isReady = true))

        assertEquals(GamePhase.WAITING, state.lobbies["A"]!!.gamePhase)
        assertEquals(null, timedEvents)
    }

    // -----------------------------------------------------------------------
    // Button presses
    // -----------------------------------------------------------------------

    @Test
    fun buttonPressesAffectValues() {
        // Get to PLAYING state by applying the countdown timed event
        val (countdownState, timedEvents) = PeerNetState()
            .after(joined("A", "Alice"))
            .after(joined("B", "Bob"))
            .after(PeerEvent.JoinedLobby(lobbyId = "A", playerId = "B"))
            .after(PeerEvent.ReadyChanged(lobbyId = "A", playerId = "A", isReady = true))
            .after(PeerEvent.ReadyChanged(lobbyId = "A", playerId = "B", isReady = true))

        // Walk through the countdown chain: tick(2) → tick(1) → PhaseChanged(PLAYING)
        val (tick2State, tick2Timed) = countdownState.after(timedEvents!!.event)
        assertEquals(2, tick2State.lobbies["A"]!!.countdownValue)
        val (tick1State, tick1Timed) = tick2State.after(tick2Timed!!.event)
        assertEquals(1, tick1State.lobbies["A"]!!.countdownValue)
        val (playingState, _) = tick1State.after(tick1Timed!!.event)
        assertEquals(GamePhase.PLAYING, playingState.lobbies["A"]!!.gamePhase)

        val startA = playingState.lobbies["A"]!!.playerValues["A"]!!
        val startB = playingState.lobbies["A"]!!.playerValues["B"]!!

        val (state, _) = playingState
            .after(PeerEvent.ButtonPress(lobbyId = "A", sourceId = "A", targetId = "A", delta = -2))
            .after(PeerEvent.ButtonPress(lobbyId = "A", sourceId = "B", targetId = "A", delta = 1))
            .after(PeerEvent.ButtonPress(lobbyId = "A", sourceId = "A", targetId = "B", delta = 1))

        assertEquals(startA - 2 + 1, state.lobbies["A"]!!.playerValues["A"])
        assertEquals(startB + 1, state.lobbies["A"]!!.playerValues["B"])
    }

    @Test
    fun valuesClampToRange() {
        // Manually submit GameStarted with extreme values (bypass cascade for this test)
        val (state, _) = PeerNetState()
            .after(joined("A", "Alice"))
            .after(joined("B", "Bob"))
            .after(PeerEvent.JoinedLobby(lobbyId = "A", playerId = "B"))
            .after(PeerEvent.GameStarted(lobbyId = "A", playerValues = mapOf("A" to 24, "B" to -24)))
            .after(PeerEvent.PhaseChanged(lobbyId = "A", newPhase = GamePhase.PLAYING))
            .after(PeerEvent.ButtonPress(lobbyId = "A", sourceId = "B", targetId = "A", delta = 1))
            .after(PeerEvent.ButtonPress(lobbyId = "A", sourceId = "B", targetId = "A", delta = 1))
            .after(PeerEvent.ButtonPress(lobbyId = "A", sourceId = "A", targetId = "B", delta = -2))
            .after(PeerEvent.ButtonPress(lobbyId = "A", sourceId = "A", targetId = "B", delta = -2))

        assertEquals(25, state.lobbies["A"]!!.playerValues["A"])
        assertEquals(-25, state.lobbies["A"]!!.playerValues["B"])
    }

    // -----------------------------------------------------------------------
    // Win detection cascade: all zeros → WIN_COUNTDOWN
    // -----------------------------------------------------------------------

    @Test
    fun allZerosCascadesToWinCountdown() {
        // Start with values of 1, play to reach 0
        val (countdownState, timedEvents) = PeerNetState()
            .after(joined("A", "Alice"))
            .after(joined("B", "Bob"))
            .after(PeerEvent.JoinedLobby(lobbyId = "A", playerId = "B"))
            .after(PeerEvent.GameStarted(lobbyId = "A", playerValues = mapOf("A" to 1, "B" to 1)))
            .after(PeerEvent.PhaseChanged(lobbyId = "A", newPhase = GamePhase.PLAYING))
            // A presses own: 1-2 = -1, B presses A: -1+1 = 0
            .after(PeerEvent.ButtonPress(lobbyId = "A", sourceId = "A", targetId = "A", delta = -2))
            .after(PeerEvent.ButtonPress(lobbyId = "A", sourceId = "B", targetId = "A", delta = 1))
            // B presses own: 1-2 = -1, A presses B: -1+1 = 0
            .after(PeerEvent.ButtonPress(lobbyId = "A", sourceId = "B", targetId = "B", delta = -2))
            .after(PeerEvent.ButtonPress(lobbyId = "A", sourceId = "A", targetId = "B", delta = 1))

        // Cascade: all zeros → WIN_COUNTDOWN
        assertEquals(GamePhase.WIN_COUNTDOWN, countdownState.lobbies["A"]!!.gamePhase)

        // Timed event: first countdown tick (3 → 2) in 1s
        assertNotNull(timedEvents)
        assertEquals(1.seconds, timedEvents.delay)
        val tickEvent = timedEvents.event as PeerEvent.CountdownTick
        assertEquals(2, tickEvent.value)
    }

    // -----------------------------------------------------------------------
    // Vote cascade: all votes → ENDED or WAITING
    // -----------------------------------------------------------------------

    @Test
    fun allVotesEndLobbyCascadesToEnded() {
        val (state, _) = PeerNetState()
            .after(joined("A", "Alice"))
            .after(joined("B", "Bob"))
            .after(PeerEvent.JoinedLobby(lobbyId = "A", playerId = "B"))
            .after(PeerEvent.GameStarted(lobbyId = "A", playerValues = mapOf("A" to 1, "B" to 1)))
            .after(PeerEvent.PhaseChanged(lobbyId = "A", newPhase = GamePhase.PLAYING))
            .after(PeerEvent.PhaseChanged(lobbyId = "A", newPhase = GamePhase.WIN_COUNTDOWN))
            .after(PeerEvent.PhaseChanged(lobbyId = "A", newPhase = GamePhase.VOTING))
            .after(PeerEvent.VoteCast(lobbyId = "A", playerId = "A", choice = VoteChoice.END_LOBBY))
            .after(PeerEvent.VoteCast(lobbyId = "A", playerId = "B", choice = VoteChoice.END_LOBBY))

        // Cascade: all votes END_LOBBY → ENDED
        assertEquals(GamePhase.ENDED, state.lobbies["A"]!!.gamePhase)
    }

    @Test
    fun allVotesPlayAgainCascadesToWaiting() {
        val (state, _) = PeerNetState()
            .after(joined("A", "Alice"))
            .after(joined("B", "Bob"))
            .after(PeerEvent.JoinedLobby(lobbyId = "A", playerId = "B"))
            .after(PeerEvent.GameStarted(lobbyId = "A", playerValues = mapOf("A" to 1, "B" to 1)))
            .after(PeerEvent.PhaseChanged(lobbyId = "A", newPhase = GamePhase.PLAYING))
            .after(PeerEvent.PhaseChanged(lobbyId = "A", newPhase = GamePhase.WIN_COUNTDOWN))
            .after(PeerEvent.PhaseChanged(lobbyId = "A", newPhase = GamePhase.VOTING))
            .after(PeerEvent.VoteCast(lobbyId = "A", playerId = "A", choice = VoteChoice.PLAY_AGAIN))
            .after(PeerEvent.VoteCast(lobbyId = "A", playerId = "B", choice = VoteChoice.PLAY_AGAIN))

        // Cascade: all votes PLAY_AGAIN → WAITING (reset)
        val lobby = state.lobbies["A"]!!
        assertEquals(GamePhase.WAITING, lobby.gamePhase)
        assertTrue(lobby.playerValues.isEmpty())
        assertTrue(lobby.votes.isEmpty())
        assertTrue(lobby.players.values.none { it.isReady })
        assertEquals(2, lobby.players.size)
        assertEquals(1, lobby.round) // round incremented
    }

    // -----------------------------------------------------------------------
    // Full game lifecycle with cascades and timed events
    // -----------------------------------------------------------------------

    @Test
    fun fullGameTwoRounds() {
        // Setup: 3 players in A's lobby
        val (lobbyState, _) = PeerNetState()
            .after(joined("A", "Alice"))
            .after(joined("B", "Bob"))
            .after(joined("C", "Charlie"))
            .after(PeerEvent.JoinedLobby(lobbyId = "A", playerId = "B"))
            .after(PeerEvent.JoinedLobby(lobbyId = "A", playerId = "C"))

        // Round 1: ready up → auto game start
        val (countdownState, countdownTimedEvents) = lobbyState
            .after(PeerEvent.ReadyChanged(lobbyId = "A", playerId = "A", isReady = true))
            .after(PeerEvent.ReadyChanged(lobbyId = "A", playerId = "B", isReady = true))
            .after(PeerEvent.ReadyChanged(lobbyId = "A", playerId = "C", isReady = true))

        assertEquals(GamePhase.COUNTDOWN, countdownState.lobbies["A"]!!.gamePhase)
        assertNotNull(countdownTimedEvents)

        // Walk through the countdown chain: tick(2) → tick(1) → PhaseChanged(PLAYING)
        val (t2, t2t) = countdownState.after(countdownTimedEvents.event)
        val (t1, t1t) = t2.after(t2t!!.event)
        val (playingState, _) = t1.after(t1t!!.event)
        assertEquals(GamePhase.PLAYING, playingState.lobbies["A"]!!.gamePhase)

        // Get all values to 0 using deterministic starting values
        val vals = playingState.lobbies["A"]!!.playerValues
        var gameState = playingState
        // Drive each value to 0 by pressing own button (or getting others to press)
        for ((playerId, value) in vals) {
            var current = value
            while (current != 0) {
                if (current > 0) {
                    val (s, _) = gameState.after(PeerEvent.ButtonPress("A", playerId, playerId, -2))
                    gameState = s
                    current -= 2
                } else {
                    // Get another player to press +1
                    val other = vals.keys.first { it != playerId }
                    val (s, _) = gameState.after(PeerEvent.ButtonPress("A", other, playerId, 1))
                    gameState = s
                    current += 1
                }
            }
        }

        // Should have cascaded to WIN_COUNTDOWN
        assertEquals(GamePhase.WIN_COUNTDOWN, gameState.lobbies["A"]!!.gamePhase)

        // Simulate win countdown → VOTING
        val (votingState, _) = gameState.after(PeerEvent.PhaseChanged("A", GamePhase.VOTING))
        assertEquals(GamePhase.VOTING, votingState.lobbies["A"]!!.gamePhase)

        // Vote: 2 play again, 1 end → WAITING (play again wins)
        val (waitingState, _) = votingState
            .after(PeerEvent.VoteCast("A", "A", VoteChoice.PLAY_AGAIN))
            .after(PeerEvent.VoteCast("A", "B", VoteChoice.PLAY_AGAIN))
            .after(PeerEvent.VoteCast("A", "C", VoteChoice.END_LOBBY))

        val lobby = waitingState.lobbies["A"]!!
        assertEquals(GamePhase.WAITING, lobby.gamePhase)
        assertEquals(3, lobby.players.size)
        assertEquals(1, lobby.round)

        // Round 2: ready up again → different values (round changed)
        val (countdown2, _) = waitingState
            .after(PeerEvent.ReadyChanged("A", "A", true))
            .after(PeerEvent.ReadyChanged("A", "B", true))
            .after(PeerEvent.ReadyChanged("A", "C", true))

        assertEquals(GamePhase.COUNTDOWN, countdown2.lobbies["A"]!!.gamePhase)
        // Round 1 values may differ from round 0 values (different seed)
    }

    // -----------------------------------------------------------------------
    // Player leaving
    // -----------------------------------------------------------------------

    @Test
    fun playerLeavingLobbyRemovesThem() {
        val (state, _) = PeerNetState()
            .after(joined("A", "Alice"))
            .after(joined("B", "Bob"))
            .after(joined("C", "Charlie"))
            .after(PeerEvent.JoinedLobby(lobbyId = "A", playerId = "B"))
            .after(PeerEvent.JoinedLobby(lobbyId = "A", playerId = "C"))
            .after(PeerEvent.LeftLobby(lobbyId = "A", playerId = "C"))

        assertEquals(setOf("A", "B"), state.lobbies["A"]!!.players.keys)
        // C got a new solo lobby
        assertEquals(setOf("C"), state.lobbies["C"]!!.players.keys)
    }

    @Test
    fun peerDisconnectRemovesFromLobby() {
        val (state, _) = PeerNetState()
            .after(joined("A", "Alice"))
            .after(joined("B", "Bob"))
            .after(PeerEvent.JoinedLobby(lobbyId = "A", playerId = "B"))
            .after(PeerEvent.Left("B"))

        assertEquals(setOf("A"), state.lobbies["A"]!!.players.keys)
    }

    @Test
    fun playerLeavingDuringCountdownCancelsGame() {
        val (state, timedEvents) = PeerNetState()
            .after(joined("A", "Alice"))
            .after(joined("B", "Bob"))
            .after(PeerEvent.JoinedLobby(lobbyId = "A", playerId = "B"))
            .after(PeerEvent.ReadyChanged(lobbyId = "A", playerId = "A", isReady = true))
            .after(PeerEvent.ReadyChanged(lobbyId = "A", playerId = "B", isReady = true))
            // Now in COUNTDOWN. B leaves.
            .after(PeerEvent.LeftLobby(lobbyId = "A", playerId = "B"))

        // Cascade: <2 players during active game → WAITING
        assertEquals(GamePhase.WAITING, state.lobbies["A"]!!.gamePhase)
    }

    // -----------------------------------------------------------------------
    // Game events don't leak between lobbies
    // -----------------------------------------------------------------------

    @Test
    fun gameEventsAreScopedToTheirLobby() {
        val (state, _) = PeerNetState()
            .after(joined("A", "Alice"))
            .after(joined("B", "Bob"))
            .after(joined("C", "Charlie"))
            .after(joined("D", "Diana"))
            .after(PeerEvent.JoinedLobby(lobbyId = "A", playerId = "B"))
            .after(PeerEvent.JoinedLobby(lobbyId = "C", playerId = "D"))
            // Ready up in A's lobby only
            .after(PeerEvent.ReadyChanged(lobbyId = "A", playerId = "A", isReady = true))
            .after(PeerEvent.ReadyChanged(lobbyId = "A", playerId = "B", isReady = true))

        // A's lobby started (COUNTDOWN), C's lobby untouched
        assertEquals(GamePhase.COUNTDOWN, state.lobbies["A"]!!.gamePhase)
        assertEquals(GamePhase.WAITING, state.lobbies["C"]!!.gamePhase)
    }

    // -----------------------------------------------------------------------
    // Edge cases
    // -----------------------------------------------------------------------

    @Test
    fun toggleReadyOnAndOff() {
        val (state, _) = PeerNetState()
            .after(joined("A", "Alice"))
            .after(joined("B", "Bob"))
            .after(PeerEvent.JoinedLobby(lobbyId = "A", playerId = "B"))
            .after(PeerEvent.ReadyChanged(lobbyId = "A", playerId = "A", isReady = true))
            .after(PeerEvent.ReadyChanged(lobbyId = "A", playerId = "A", isReady = false))

        assertEquals(false, state.lobbies["A"]!!.players["A"]!!.isReady)
        assertEquals(GamePhase.WAITING, state.lobbies["A"]!!.gamePhase) // no cascade
    }

    @Test
    fun playerCanSwitchLobbies() {
        val (state, _) = PeerNetState()
            .after(joined("A", "Alice"))
            .after(joined("B", "Bob"))
            .after(joined("C", "Charlie"))
            .after(PeerEvent.JoinedLobby(lobbyId = "A", playerId = "B"))
            .after(PeerEvent.JoinedLobby(lobbyId = "C", playerId = "B"))

        assertEquals(setOf("A"), state.lobbies["A"]!!.players.keys)
        assertEquals(setOf("C", "B"), state.lobbies["C"]!!.players.keys)
    }

    @Test
    fun deterministicPlayerValuesAreConsistent() {
        val values1 = deterministicPlayerValues("lobby1", 0, setOf("A", "B"))
        val values2 = deterministicPlayerValues("lobby1", 0, setOf("A", "B"))
        assertEquals(values1, values2, "Same inputs should produce same values")

        val values3 = deterministicPlayerValues("lobby1", 1, setOf("A", "B"))
        // Different round should (likely) produce different values
        // (not guaranteed but very likely with hash-based generation)
    }
}
