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
        val webHostUrl = remember { mutableStateOf<String?>(null) }

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

                launch {
                    hostingWebClient(conn) { url ->
                        webHostUrl.value = url
                        awaitCancellation()
                    }
                }

                awaitCancellation()
            }
        }

        AppContent(connectionRef.value, publicState, internalState, webHostUrl.value)
    }
}

@Composable
fun AppContent(
    connection: PeerNetConnection?,
    publicState: MutableStateFlow<PeerNetState>,
    internalState: MutableStateFlow<InternalState>,
    webHostUrl: String? = null,
) {
    // Combine into WholeState
    val wholeState by combine(publicState, internalState) { pub, int ->
        WholeState(
            localId = connection?.localId ?: "",
            publicState = pub,
            internalState = int,
        )
    }.collectAsState(WholeState(internalState = internalState.value))

    val conn = connection
    val localId = wholeState.localId
    val pub = wholeState.publicState
    val int = wholeState.internalState

    // Derive "my lobby" — the lobby the local player is in (if any)
    val myLobby = pub.lobbies.values.firstOrNull { localId in it.players }
    val myLobbyId = myLobby?.lobbyId

    // --- Side effects ---
    // Game logic (start, win, votes, countdown) is handled by deterministic cascades
    // and timed events in PeerNetState.after(). App.kt only manages screen navigation.

    val gamePhase = myLobby?.gamePhase ?: GamePhase.WAITING
    val countdownValue = myLobby?.countdownValue

    // Screen navigation based on game phase
    LaunchedEffect(gamePhase, myLobbyId) {
        when (gamePhase) {
            GamePhase.COUNTDOWN -> internalState.update { it.copy(screen = Screen.LOBBY) }
            GamePhase.PLAYING -> internalState.update { it.copy(screen = Screen.GAME) }
            GamePhase.VOTING -> internalState.update { it.copy(screen = Screen.VOTING) }
            GamePhase.ENDED -> {
                if (myLobbyId != null) conn?.submitEvent(PeerEvent.LeftLobby(myLobbyId, localId))
                internalState.update { it.copy(screen = Screen.HOME) }
            }
            GamePhase.WAITING -> {
                // After play-again vote, return to lobby
                if (int.screen == Screen.VOTING || int.screen == Screen.GAME) {
                    internalState.update { it.copy(screen = Screen.LOBBY) }
                }
            }
            else -> {}
        }
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
                    val peers = pub.discoveredPeers.values
                        .filter { it.id != localId }
                        .toList()

                    // Show lobby card only if our lobby has more than just us
                    val lobbyPlayers = myLobby?.players?.entries?.map { (id, lp) ->
                        PeerInfo(id = id, name = lp.name, address = "", port = 0)
                    } ?: emptyList()
                    val showLobby = lobbyPlayers.size > 1

                    // Foreign lobbies: lobbies with 2+ players that we're not in
                    val foreignLobbies = pub.lobbies.values
                        .filter { it.players.size > 1 && localId !in it.players }
                        .map { lobby ->
                            ForeignLobby(
                                lobbyId = lobby.lobbyId,
                                players = lobby.players.entries.map { (id, lp) ->
                                    PeerInfo(id = id, name = lp.name, address = "", port = 0)
                                },
                            )
                        }

                    HomeScreen(
                        playerName = int.playerName,
                        peers = peers,
                        lobbyPlayers = if (showLobby) lobbyPlayers else emptyList(),
                        foreignLobbies = foreignLobbies,
                        webHostUrl = webHostUrl,
                        onSettingsClick = {
                            internalState.update { it.copy(showSettings = !it.showSettings) }
                        },
                        onJoinPeer = { peerId ->
                            coroutineScope.launch {
                                // Find the lobby the target peer is in
                                val targetLobby = pub.lobbies.values.firstOrNull { peerId in it.players }
                                if (targetLobby != null) {
                                    conn?.submitEvent(PeerEvent.JoinedLobby(
                                        lobbyId = targetLobby.lobbyId,
                                        playerId = localId,
                                    ))
                                    internalState.update { it.copy(screen = Screen.LOBBY) }
                                }
                            }
                        },
                        onEnterLobby = {
                            internalState.update { it.copy(screen = Screen.LOBBY) }
                        }
                    )
                }

                Screen.LOBBY -> {
                    val lobby = myLobby
                    if (lobby != null) {
                        LobbyScreen(
                            lobby = lobby,
                            localPlayerId = localId,
                            countdownValue = countdownValue,
                            onToggleReady = {
                                coroutineScope.launch {
                                    val currentReady = lobby.players[localId]?.isReady ?: false
                                    conn?.submitEvent(PeerEvent.ReadyChanged(lobby.lobbyId, localId, !currentReady))
                                }
                            },
                            onLeaveLobby = {
                                coroutineScope.launch {
                                    conn?.submitEvent(PeerEvent.LeftLobby(lobby.lobbyId, localId))
                                    internalState.update { it.copy(screen = Screen.HOME) }
                                }
                            }
                        )
                    }
                }

                Screen.GAME -> {
                    val lobby = myLobby
                    GameScreen(
                        playerValues = lobby?.playerValues ?: emptyMap(),
                        playerNames = lobby?.players?.mapValues { it.value.name } ?: emptyMap(),
                        gamePhase = lobby?.gamePhase ?: GamePhase.WAITING,
                        localPlayerId = localId,
                        countdownValue = countdownValue,
                        onButtonPress = { targetPlayerId ->
                            coroutineScope.launch {
                                if (lobby != null && lobby.gamePhase == GamePhase.PLAYING) {
                                    val delta = if (targetPlayerId == localId) -2 else 1
                                    conn?.submitEvent(PeerEvent.ButtonPress(
                                        lobbyId = lobby.lobbyId,
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
                    val lobby = myLobby
                    if (lobby != null) {
                        VotingScreen(
                            lobbyPlayers = lobby.players,
                            votes = lobby.votes,
                            localPlayerId = localId,
                            onVote = { choice ->
                                coroutineScope.launch {
                                    conn?.submitEvent(PeerEvent.VoteCast(lobby.lobbyId, localId, choice))
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
