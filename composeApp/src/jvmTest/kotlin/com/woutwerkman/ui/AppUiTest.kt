package com.woutwerkman.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import com.woutwerkman.AppContent
import com.woutwerkman.game.model.GamePhase
import com.woutwerkman.game.model.InternalState
import com.woutwerkman.game.model.Screen
import com.woutwerkman.game.model.VoteChoice
import com.woutwerkman.net.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration

/**
 * Test double for [PeerNetConnection] that applies events through the real
 * [PeerNetState.after] fold without any networking.
 *
 * The [publicState] flow is shared directly with [AppContent], so any call
 * to [applyEvent] (simulating a remote player) or [submitEvent] (UI click)
 * is immediately visible to the composable — no manual sync needed.
 */
class TestPeerNetConnection(
    override val localId: String,
    localName: String,
) : PeerNetConnection {
    val publicState = MutableStateFlow(PeerNetState())
    override val state: MutableStateFlow<PeerNetState> get() = publicState

    init {
        applyEvent(PeerEvent.Joined(PeerInfo(localId, localName, "test", 0)))
    }

    /** Simulate a remote player's event (or any external event). */
    fun applyEvent(event: PeerEvent) {
        val (newState, timedEvent) = publicState.value.after(event)
        publicState.value = newState
        if (timedEvent != null && timedEvent.delay == Duration.ZERO) {
            applyEvent(timedEvent.event)
        }
    }

    /** Apply event and also resolve all timed cascades regardless of delay. */
    fun applyEventWithTimedCascades(event: PeerEvent) {
        var (currentState, timedEvent) = publicState.value.after(event)
        publicState.value = currentState
        while (timedEvent != null) {
            val (next, nextTimed) = currentState.after(timedEvent.event)
            currentState = next
            publicState.value = next
            timedEvent = nextTimed
        }
    }

    fun setState(state: PeerNetState) {
        publicState.value = state
    }

    override suspend fun submitEvent(event: PeerEvent): Boolean {
        applyEvent(event)
        return true
    }
}

@OptIn(ExperimentalTestApi::class)
class AppUiTest {

    private data class TestSetup(
        val conn: TestPeerNetConnection,
        val internalState: MutableStateFlow<InternalState>,
    )

    private fun createTestSetup(
        localId: String = "local",
        localName: String = "TestPlayer",
    ): TestSetup {
        val conn = TestPeerNetConnection(localId, localName)
        val internalState = MutableStateFlow(InternalState(playerName = localName))
        return TestSetup(conn, internalState)
    }

    private fun ComposeUiTest.setAppContent(setup: TestSetup) {
        setContent {
            MaterialTheme {
                AppContent(setup.conn, setup.conn.publicState, setup.internalState)
            }
        }
    }

    // -- Remote player helper --

    /** Simulate a remote player joining the network and entering their own lobby. */
    private fun TestPeerNetConnection.addRemotePeer(
        id: String = "remote",
        name: String = "Bob",
    ) {
        applyEvent(PeerEvent.Joined(PeerInfo(id, name, "test", 0)))
        applyEvent(PeerEvent.JoinedLobby(id, id))
    }

    // -------------------------------------------------------------------------
    // Home screen
    // -------------------------------------------------------------------------

    @Test
    fun homeScreenShowsPlayerName() = runComposeUiTest {
        val setup = createTestSetup(localName = "Alice")
        setAppContent(setup)

        onNodeWithText("Playing as: Alice").assertIsDisplayed()
        onNodeWithText("Chippy").assertIsDisplayed()
    }

    @Test
    fun homeScreenShowsNearbyPeer() = runComposeUiTest {
        val setup = createTestSetup()
        setup.conn.applyEvent(PeerEvent.Joined(PeerInfo("remote", "Bob", "test", 0)))
        setAppContent(setup)

        onNodeWithText("Bob").assertIsDisplayed()
        onNodeWithText("Nearby Players").assertIsDisplayed()
    }

    @Test
    fun searchingMessageWhenNoPeers() = runComposeUiTest {
        val setup = createTestSetup()
        setAppContent(setup)

        onNodeWithText("Searching for nearby players...").assertIsDisplayed()
    }

    @Test
    fun foreignLobbyVisibleToObserver() = runComposeUiTest {
        // C sees A and B. A and B are in a lobby together.
        // C should see that lobby on the home screen (not just individual players).
        val setup = createTestSetup(localId = "C", localName = "Charlie")
        setup.conn.applyEvent(PeerEvent.Joined(PeerInfo("A", "Alice", "test", 0)))
        setup.conn.applyEvent(PeerEvent.Joined(PeerInfo("B", "Bob", "test", 0)))
        setup.conn.applyEvent(PeerEvent.JoinedLobby("A", "B"))
        setAppContent(setup)

        // C should see the foreign lobby section
        onNodeWithText("Lobbies").assertExists()
        // The lobby card should show it has 2 players
        onNodeWithText("2 players connected").assertExists()
        // Player names should appear in the lobby card
        onNodeWithText("Alice", substring = true).assertExists()
        onNodeWithText("Bob", substring = true).assertExists()
        // Should NOT show "Searching for nearby players..." since we have foreign lobbies
        onNodeWithText("Searching for nearby players...").assertDoesNotExist()
    }

    @Test
    fun foreignLobbyPeersNotDuplicatedInNearbyList() = runComposeUiTest {
        // A and B are in a lobby, D is solo. C should see the lobby card
        // for A+B and D as an individual nearby player, but NOT A or B individually.
        val setup = createTestSetup(localId = "C", localName = "Charlie")
        setup.conn.applyEvent(PeerEvent.Joined(PeerInfo("A", "Alice", "test", 0)))
        setup.conn.applyEvent(PeerEvent.Joined(PeerInfo("B", "Bob", "test", 0)))
        setup.conn.applyEvent(PeerEvent.Joined(PeerInfo("D", "Diana", "test", 0)))
        setup.conn.applyEvent(PeerEvent.JoinedLobby("A", "B"))
        setAppContent(setup)

        // Foreign lobby card should exist
        onNodeWithText("2 players connected").assertExists()
        // Diana should appear as individual nearby player
        onNodeWithText("Diana").assertExists()
        // "Tap to join" should appear (for Diana's individual card)
        onNodeWithText("Tap to join").assertExists()
    }

    @Test
    fun lobbyCardShowsWhenMultiplePlayersInLobby() = runComposeUiTest {
        val setup = createTestSetup()
        setup.conn.applyEvent(PeerEvent.Joined(PeerInfo("remote", "Bob", "test", 0)))
        setup.conn.applyEvent(PeerEvent.JoinedLobby("local", "remote"))
        setAppContent(setup)

        onNodeWithText("Your Lobby").assertIsDisplayed()
        onNodeWithTag("enter-lobby").assertIsDisplayed()
    }

    @Test
    fun joinPeerNavigatesToLobby() = runComposeUiTest {
        val setup = createTestSetup()
        setup.conn.addRemotePeer()
        setAppContent(setup)

        onNodeWithTag("join-remote").performClick()
        waitForIdle()

        onNodeWithText("Lobby").assertIsDisplayed()
    }

    // -------------------------------------------------------------------------
    // Settings
    // -------------------------------------------------------------------------

    @Test
    fun settingsButtonOpensDialog() = runComposeUiTest {
        val setup = createTestSetup(localName = "Alice")
        setAppContent(setup)

        onNodeWithTag("settings-button").performClick()
        waitForIdle()

        onNodeWithText("Settings").assertIsDisplayed()
        onNodeWithText("Player Name").assertIsDisplayed()
        onNodeWithTag("name-field").assertIsDisplayed()
        onNodeWithTag("save-button").assertIsDisplayed()
    }

    // -------------------------------------------------------------------------
    // Lobby screen
    // -------------------------------------------------------------------------

    @Test
    fun lobbyScreenShowsPlayersAndReadyButton() = runComposeUiTest {
        val setup = createTestSetup()
        setup.conn.applyEvent(PeerEvent.Joined(PeerInfo("remote", "Bob", "test", 0)))
        setup.conn.applyEvent(PeerEvent.JoinedLobby("local", "remote"))
        setup.internalState.update { it.copy(screen = Screen.LOBBY) }
        setAppContent(setup)

        onNodeWithText("Lobby").assertIsDisplayed()
        onNodeWithText("Ready Up").assertIsDisplayed()
        onNodeWithTag("ready-button").assertIsDisplayed()
        onNodeWithTag("leave-button").assertIsDisplayed()
    }

    @Test
    fun readyButtonTogglesReadyState() = runComposeUiTest {
        val setup = createTestSetup()
        setup.conn.applyEvent(PeerEvent.Joined(PeerInfo("remote", "Bob", "test", 0)))
        setup.conn.applyEvent(PeerEvent.JoinedLobby("local", "remote"))
        setup.internalState.update { it.copy(screen = Screen.LOBBY) }
        setAppContent(setup)

        onNodeWithText("Ready Up").assertIsDisplayed()

        onNodeWithTag("ready-button").performClick()
        waitForIdle()

        onNodeWithText("Ready!").assertIsDisplayed()
    }

    @Test
    fun remotePlayerReadyUpIsVisible() = runComposeUiTest {
        val setup = createTestSetup()
        setup.conn.applyEvent(PeerEvent.Joined(PeerInfo("remote", "Bob", "test", 0)))
        setup.conn.applyEvent(PeerEvent.JoinedLobby("local", "remote"))
        setup.internalState.update { it.copy(screen = Screen.LOBBY) }
        setAppContent(setup)

        // Bob readies up — simulated remote event
        setup.conn.applyEvent(PeerEvent.ReadyChanged("local", "remote", true))
        waitForIdle()

        // Bob's ready status should be visible
        onNodeWithText("Ready").assertIsDisplayed()
    }

    @Test
    fun countdownOverlayShowsInLobby() = runComposeUiTest {
        val setup = createTestSetup()
        setup.conn.setState(
            PeerNetState(
                discoveredPeers = mapOf(
                    "local" to PeerInfo("local", "TestPlayer", "test", 0),
                    "remote" to PeerInfo("remote", "Bob", "test", 0),
                ),
                lobbies = mapOf(
                    "local" to LobbyInfo(
                        lobbyId = "local",
                        hostId = "local",
                        players = mapOf(
                            "local" to LobbyPlayer("TestPlayer", isReady = true),
                            "remote" to LobbyPlayer("Bob", isReady = true),
                        ),
                        gamePhase = GamePhase.COUNTDOWN,
                        countdownValue = 3,
                    )
                ),
            )
        )
        setup.internalState.update { it.copy(screen = Screen.LOBBY) }
        setAppContent(setup)

        onNodeWithText("Game starting in").assertIsDisplayed()
        onNodeWithText("3").assertIsDisplayed()
    }

    @Test
    fun leaveButtonReturnsToHome() = runComposeUiTest {
        val setup = createTestSetup()
        setup.conn.applyEvent(PeerEvent.Joined(PeerInfo("remote", "Bob", "test", 0)))
        setup.conn.applyEvent(PeerEvent.JoinedLobby("local", "remote"))
        setup.internalState.update { it.copy(screen = Screen.LOBBY) }
        setAppContent(setup)

        onNodeWithTag("leave-button").performClick()
        waitForIdle()

        assertEquals(Screen.HOME, setup.internalState.value.screen)
    }

    // -------------------------------------------------------------------------
    // Game screen
    // -------------------------------------------------------------------------

    private fun playingState() = PeerNetState(
        discoveredPeers = mapOf(
            "local" to PeerInfo("local", "TestPlayer", "test", 0),
            "remote" to PeerInfo("remote", "Bob", "test", 0),
        ),
        lobbies = mapOf(
            "local" to LobbyInfo(
                lobbyId = "local",
                hostId = "local",
                players = mapOf(
                    "local" to LobbyPlayer("TestPlayer", isReady = true),
                    "remote" to LobbyPlayer("Bob", isReady = true),
                ),
                gamePhase = GamePhase.PLAYING,
                playerValues = mapOf("local" to 10, "remote" to 10),
            )
        ),
    )

    @Test
    fun gameScreenShowsPlayerButtons() = runComposeUiTest {
        val setup = createTestSetup()
        setup.conn.setState(playingState())
        setup.internalState.update { it.copy(screen = Screen.GAME) }
        setAppContent(setup)

        onNodeWithText("Get all to zero!").assertIsDisplayed()
        onNodeWithTag("player-button-local").assertIsDisplayed().assertTextContains("10")
        onNodeWithTag("player-button-remote").assertIsDisplayed().assertTextContains("Bob")
    }

    @Test
    fun ownButtonPressDecrementsBy2() = runComposeUiTest {
        val setup = createTestSetup()
        setup.conn.setState(playingState())
        setup.internalState.update { it.copy(screen = Screen.GAME) }
        setAppContent(setup)

        onNodeWithTag("player-button-local").performClick()
        waitForIdle()

        onNodeWithTag("player-button-local").assertTextContains("8")
    }

    @Test
    fun otherButtonPressIncrementsBy1() = runComposeUiTest {
        val setup = createTestSetup()
        setup.conn.setState(playingState())
        setup.internalState.update { it.copy(screen = Screen.GAME) }
        setAppContent(setup)

        onNodeWithTag("player-button-remote").performClick()
        waitForIdle()

        onNodeWithTag("player-button-remote").assertTextContains("11")
    }

    @Test
    fun remotePlayerButtonPressIsVisible() = runComposeUiTest {
        val setup = createTestSetup()
        setup.conn.setState(playingState())
        setup.internalState.update { it.copy(screen = Screen.GAME) }
        setAppContent(setup)

        // Bob presses his own button — simulated remote event
        setup.conn.applyEvent(PeerEvent.ButtonPress("local", "remote", "remote", -2))
        waitForIdle()

        onNodeWithTag("player-button-remote").assertTextContains("8")
    }

    // -------------------------------------------------------------------------
    // Voting screen
    // -------------------------------------------------------------------------

    private fun votingState() = PeerNetState(
        discoveredPeers = mapOf(
            "local" to PeerInfo("local", "TestPlayer", "test", 0),
            "remote" to PeerInfo("remote", "Bob", "test", 0),
        ),
        lobbies = mapOf(
            "local" to LobbyInfo(
                lobbyId = "local",
                hostId = "local",
                players = mapOf(
                    "local" to LobbyPlayer("TestPlayer", isReady = true),
                    "remote" to LobbyPlayer("Bob", isReady = true),
                ),
                gamePhase = GamePhase.VOTING,
                playerValues = mapOf("local" to 0, "remote" to 0),
            )
        ),
    )

    @Test
    fun votingScreenShowsVoteOptions() = runComposeUiTest {
        val setup = createTestSetup()
        setup.conn.setState(votingState())
        setup.internalState.update { it.copy(screen = Screen.VOTING) }
        setAppContent(setup)

        onNodeWithText("Victory!").assertIsDisplayed()
        onNodeWithText("Play Again").assertIsDisplayed()
        onNodeWithText("End Lobby").assertIsDisplayed()
        onNodeWithTag("vote-play-again").assertIsDisplayed()
        onNodeWithTag("vote-end-lobby").assertIsDisplayed()
    }

    @Test
    fun votePlayAgainSubmitsEvent() = runComposeUiTest {
        val setup = createTestSetup()
        setup.conn.setState(votingState())
        setup.internalState.update { it.copy(screen = Screen.VOTING) }
        setAppContent(setup)

        onNodeWithTag("vote-play-again").onChildren().filterToOne(hasText("Vote")).performClick()
        waitForIdle()

        val votes = setup.conn.state.value.lobbies["local"]?.votes
        assertEquals(VoteChoice.PLAY_AGAIN, votes?.get("local"))
    }

    @Test
    fun remotePlayerVoteIsVisible() = runComposeUiTest {
        val setup = createTestSetup()
        setup.conn.setState(votingState())
        setup.internalState.update { it.copy(screen = Screen.VOTING) }
        setAppContent(setup)

        // Bob votes — simulated remote event
        setup.conn.applyEvent(PeerEvent.VoteCast("local", "remote", VoteChoice.END_LOBBY))
        waitForIdle()

        onNodeWithText("Wants to end lobby").assertExists()
        onNodeWithText("Votes: 1 / 2").assertExists()
    }
}
