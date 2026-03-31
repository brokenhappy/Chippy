package com.woutwerkman

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.woutwerkman.game.model.*
import com.woutwerkman.game.ui.*
import com.woutwerkman.net.*
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Composable
fun App() {
    MaterialTheme {
        val internalState = remember { MutableStateFlow(InternalState()) }
        val publicState = remember { MutableStateFlow(PeerNetState()) }
        val connectionRef = remember { mutableStateOf<PeerNetConnection?>(null) }

        val playerName by remember {
            internalState
        }.collectAsState()
            .let { derivedStateOf { it.value.playerName } }

        // Network connection lifecycle tied to playerName
        LaunchedEffect(playerName) {
            val config = PeerNetConfig(serviceName = "chippy", displayName = playerName)
            withPeerNetConnection(config) { conn ->
                connectionRef.value = conn
                launch { conn.state.collect { publicState.value = it } }
                awaitCancellation()
            }
        }

        // Combine into WholeState
        val wholeState by combine(publicState, internalState) { pub, int ->
            WholeState(
                localId = connectionRef.value?.localId ?: "",
                publicState = pub,
                internalState = int,
            )
        }.collectAsState(WholeState(internalState = internalState.value))

        val conn = connectionRef.value
        val localId = wholeState.localId
        val pub = wholeState.publicState
        val int = wholeState.internalState

        // --- Side effects ---

        // Countdown timer (auto-cancels when gamePhase changes)
        val gamePhase = pub.gamePhase
        LaunchedEffect(gamePhase) {
            if (gamePhase == GamePhase.COUNTDOWN || gamePhase == GamePhase.WIN_COUNTDOWN) {
                for (i in 3 downTo 1) {
                    internalState.update { it.copy(countdownValue = i) }
                    delay(1000)
                }
                internalState.update { it.copy(countdownValue = null) }

                // Host initiates phase transition after countdown
                val isHost = pub.lobby?.hostId == localId
                if (isHost && conn != null) {
                    when (gamePhase) {
                        GamePhase.COUNTDOWN -> conn.submitEvent(PeerEvent.PhaseChanged(GamePhase.PLAYING))
                        GamePhase.WIN_COUNTDOWN -> conn.submitEvent(PeerEvent.PhaseChanged(GamePhase.VOTING))
                        else -> {}
                    }
                }
            } else {
                internalState.update { it.copy(countdownValue = null) }
            }
        }

        // Navigate to game screen when game starts
        LaunchedEffect(gamePhase) {
            when (gamePhase) {
                GamePhase.PLAYING -> internalState.update { it.copy(screen = Screen.GAME) }
                GamePhase.VOTING -> internalState.update { it.copy(screen = Screen.VOTING) }
                GamePhase.ENDED -> {
                    // Leave lobby and go home
                    conn?.submitEvent(PeerEvent.LeftLobby(localId))
                    internalState.update { it.copy(screen = Screen.HOME) }
                }
                else -> {}
            }
        }

        // Win detection: host checks if all values are zero
        val allZeros = pub.playerValues.isNotEmpty() && pub.playerValues.values.all { it == 0 }
        LaunchedEffect(allZeros, gamePhase) {
            if (allZeros && gamePhase == GamePhase.PLAYING) {
                val isHost = pub.lobby?.hostId == localId
                if (isHost && conn != null) {
                    conn.submitEvent(PeerEvent.PhaseChanged(GamePhase.WIN_COUNTDOWN))
                }
            }
        }

        // Vote tallying: host checks if all votes are in
        val voteCount = pub.votes.size
        val lobbyPlayerCount = pub.lobby?.players?.size ?: 0
        LaunchedEffect(voteCount, lobbyPlayerCount) {
            if (gamePhase == GamePhase.VOTING && voteCount >= lobbyPlayerCount && lobbyPlayerCount > 0) {
                val isHost = pub.lobby?.hostId == localId
                if (isHost && conn != null) {
                    val playAgain = pub.votes.values.count { it == VoteChoice.PLAY_AGAIN }
                    val endLobby = pub.votes.values.count { it == VoteChoice.END_LOBBY }
                    if (endLobby > playAgain) {
                        conn.submitEvent(PeerEvent.PhaseChanged(GamePhase.ENDED))
                    } else {
                        // Play again: reset and start new game
                        conn.submitEvent(PeerEvent.PhaseChanged(GamePhase.WAITING))
                        internalState.update { it.copy(screen = Screen.LOBBY) }
                    }
                }
            }
        }

        // --- Event handlers ---

        val onSubmitEvent: suspend (PeerEvent) -> Unit = { event ->
            conn?.submitEvent(event)
        }

        // --- Render ---

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .safeContentPadding()
        ) {
            val coroutineScope = rememberCoroutineScope()

            AnimatedContent(
                targetState = int.screen,
                transitionSpec = {
                    fadeIn() + slideInHorizontally { it } togetherWith
                            fadeOut() + slideOutHorizontally { -it }
                },
                label = "screenTransition"
            ) { screen ->
                when (screen) {
                    Screen.HOME -> {
                        // Derive home screen data from public state
                        val peers = pub.discoveredPeers.values
                            .filter { it.id != localId }
                            .toList()
                        val incomingInvites = pub.pendingInvites.filter { it.toId == localId }
                        val sentInviteIds = pub.pendingInvites.filter { it.fromId == localId }.map { it.toId }.toSet()
                        val lobbyPlayers = pub.lobby?.players?.entries?.map { (id, lp) ->
                            PeerInfo(id = id, name = lp.name, address = "", port = 0)
                        } ?: emptyList()

                        HomeScreen(
                            playerName = int.playerName,
                            peers = peers,
                            lobbyPlayers = lobbyPlayers,
                            incomingInvites = incomingInvites,
                            sentInviteIds = sentInviteIds,
                            onSettingsClick = {
                                internalState.update { it.copy(showSettings = !it.showSettings) }
                            },
                            onInvitePeer = { peerId ->
                                coroutineScope.launch {
                                    conn?.submitEvent(PeerEvent.InviteSent(fromId = localId, toId = peerId))
                                }
                            },
                            onAcceptInvite = { invite ->
                                coroutineScope.launch {
                                    val lobbyId = generateId()
                                    conn?.submitEvent(PeerEvent.InviteAccepted(
                                        fromId = invite.fromId,
                                        toId = invite.toId,
                                        lobbyId = lobbyId,
                                    ))
                                    internalState.update { it.copy(screen = Screen.LOBBY) }
                                }
                            },
                            onRejectInvite = { invite ->
                                coroutineScope.launch {
                                    conn?.submitEvent(PeerEvent.InviteRejected(
                                        fromId = invite.fromId,
                                        toId = invite.toId,
                                    ))
                                }
                            },
                            onEnterLobby = {
                                internalState.update { it.copy(screen = Screen.LOBBY) }
                            }
                        )
                    }

                    Screen.LOBBY -> {
                        val lobby = pub.lobby
                        if (lobby != null) {
                            LobbyScreen(
                                lobby = lobby,
                                localPlayerId = localId,
                                countdownValue = int.countdownValue,
                                onToggleReady = {
                                    coroutineScope.launch {
                                        val currentReady = lobby.players[localId]?.isReady ?: false
                                        conn?.submitEvent(PeerEvent.ReadyChanged(localId, !currentReady))

                                        // Check if all ready after toggling to ready
                                        if (!currentReady) {
                                            // Small delay to let event propagate
                                            delay(200)
                                            val updatedLobby = publicState.value.lobby
                                            if (updatedLobby != null &&
                                                updatedLobby.players.size >= 2 &&
                                                updatedLobby.players.values.all { it.isReady }
                                            ) {
                                                val isHost = updatedLobby.hostId == localId
                                                if (isHost) {
                                                    // Generate initial values and start game
                                                    val values = updatedLobby.players.keys.associateWith {
                                                        generateRandomOddNumber()
                                                    }
                                                    conn?.submitEvent(PeerEvent.GameStarted(values))
                                                }
                                            }
                                        }
                                    }
                                },
                                onLeaveLobby = {
                                    coroutineScope.launch {
                                        conn?.submitEvent(PeerEvent.LeftLobby(localId))
                                        internalState.update { it.copy(screen = Screen.HOME, countdownValue = null) }
                                    }
                                }
                            )
                        }
                    }

                    Screen.GAME -> {
                        GameScreen(
                            playerValues = pub.playerValues,
                            playerNames = pub.lobby?.players?.mapValues { it.value.name } ?: emptyMap(),
                            gamePhase = pub.gamePhase,
                            localPlayerId = localId,
                            countdownValue = int.countdownValue,
                            onButtonPress = { targetPlayerId ->
                                coroutineScope.launch {
                                    if (pub.gamePhase == GamePhase.PLAYING) {
                                        val delta = if (targetPlayerId == localId) -2 else 1
                                        conn?.submitEvent(PeerEvent.ButtonPress(
                                            sourceId = localId,
                                            targetId = targetPlayerId,
                                            delta = delta,
                                        ))
                                    }
                                }
                            }
                        )
                    }

                    Screen.VOTING -> {
                        val lobby = pub.lobby
                        if (lobby != null) {
                            VotingScreen(
                                lobbyPlayers = lobby.players,
                                votes = pub.votes,
                                localPlayerId = localId,
                                onVote = { choice ->
                                    coroutineScope.launch {
                                        conn?.submitEvent(PeerEvent.VoteCast(localId, choice))
                                    }
                                }
                            )
                        }
                    }
                }
            }

            // Settings dialog
            if (int.showSettings) {
                SettingsDialog(
                    currentName = int.playerName,
                    onNameChange = { newName ->
                        internalState.update { it.copy(playerName = newName, showSettings = false) }
                    },
                    onDismiss = {
                        internalState.update { it.copy(showSettings = false) }
                    }
                )
            }
        }
    }
}
