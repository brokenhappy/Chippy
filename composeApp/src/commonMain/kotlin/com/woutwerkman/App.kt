package com.woutwerkman

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.woutwerkman.game.GameViewModel
import com.woutwerkman.game.Screen
import com.woutwerkman.game.ui.*

@Composable
fun App() {
    MaterialTheme {
        val viewModel: GameViewModel = viewModel { GameViewModel() }

        val currentScreen by viewModel.currentScreen.collectAsState()
        val playerName by viewModel.playerName.collectAsState()
        val showSettings by viewModel.showSettings.collectAsState()
        val countdownValue by viewModel.countdownValue.collectAsState()

        val discoveredPeers by viewModel.discoveredPeers.collectAsState()
        val connectedGroups by viewModel.connectedGroups.collectAsState()
        val pendingRequests by viewModel.pendingRequests.collectAsState()
        val sentInvites by viewModel.sentInvites.collectAsState()
        val lobbyState by viewModel.lobbyState.collectAsState()
        val gameState by viewModel.gameState.collectAsState()
        val votes by viewModel.votes.collectAsState()

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .safeContentPadding()
        ) {
            AnimatedContent(
                targetState = currentScreen,
                transitionSpec = {
                    fadeIn() + slideInHorizontally { it } togetherWith
                    fadeOut() + slideOutHorizontally { -it }
                },
                label = "screenTransition"
            ) { screen ->
                when (screen) {
                    Screen.HOME -> {
                        HomeScreen(
                            playerName = playerName,
                            discoveredPeers = discoveredPeers,
                            connectedGroups = connectedGroups,
                            pendingRequests = pendingRequests,
                            sentInvites = sentInvites,
                            onSettingsClick = { viewModel.toggleSettings() },
                            onPeerClick = { peer -> viewModel.requestConnection(peer) },
                            onAcceptRequest = { request -> viewModel.acceptConnection(request) },
                            onRejectRequest = { request -> viewModel.rejectConnection(request) },
                            onEnterLobby = { viewModel.enterLobby() }
                        )
                    }

                    Screen.LOBBY -> {
                        lobbyState?.let { lobby ->
                            LobbyScreen(
                                lobbyState = lobby,
                                localPlayerId = viewModel.getLocalPlayerId(),
                                countdownValue = countdownValue,
                                onToggleReady = { viewModel.toggleReady() },
                                onLeaveLobby = { viewModel.leaveLobby() }
                            )
                        }
                    }

                    Screen.GAME -> {
                        GameScreen(
                            gameState = gameState,
                            localPlayerId = viewModel.getLocalPlayerId(),
                            countdownValue = countdownValue,
                            onButtonPress = { playerId -> viewModel.pressButton(playerId) }
                        )
                    }

                    Screen.VOTING -> {
                        lobbyState?.let { lobby ->
                            VotingScreen(
                                lobbyState = lobby,
                                votes = votes,
                                localPlayerId = viewModel.getLocalPlayerId(),
                                onVote = { choice -> viewModel.castVote(choice) }
                            )
                        }
                    }
                }
            }

            // Settings dialog
            if (showSettings) {
                SettingsDialog(
                    currentName = playerName,
                    onNameChange = { newName -> viewModel.updatePlayerName(newName) },
                    onDismiss = { viewModel.closeSettings() }
                )
            }
        }
    }
}
