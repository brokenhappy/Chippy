package com.woutwerkman.game.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.woutwerkman.game.model.VoteChoice
import com.woutwerkman.net.LobbyPlayer

@Composable
fun VotingScreen(
    lobbyPlayers: Map<String, LobbyPlayer>,
    votes: Map<String, VoteChoice>,
    localPlayerId: String,
    onVote: (VoteChoice) -> Unit
) {
    val hasVoted = votes.containsKey(localPlayerId)
    val localVote = votes[localPlayerId]
    val totalPlayers = lobbyPlayers.size
    val totalVotes = votes.size

    val playAgainVotes = votes.values.count { it == VoteChoice.PLAY_AGAIN }
    val endLobbyVotes = votes.values.count { it == VoteChoice.END_LOBBY }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        // Victory message
        Text(
            text = "🎉",
            fontSize = 64.sp
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Victory!",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF4CAF50)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "All buttons reached zero!",
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(48.dp))

        // Voting section
        Text(
            text = "What would you like to do?",
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Vote buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            VoteButton(
                text = "Play Again",
                emoji = "🔄",
                isSelected = localVote == VoteChoice.PLAY_AGAIN,
                voteCount = playAgainVotes,
                enabled = !hasVoted,
                modifier = Modifier.weight(1f).testTag("vote-play-again"),
                onClick = { onVote(VoteChoice.PLAY_AGAIN) }
            )

            VoteButton(
                text = "End Lobby",
                emoji = "🏠",
                isSelected = localVote == VoteChoice.END_LOBBY,
                voteCount = endLobbyVotes,
                enabled = !hasVoted,
                modifier = Modifier.weight(1f).testTag("vote-end-lobby"),
                onClick = { onVote(VoteChoice.END_LOBBY) }
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Vote progress
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Votes: $totalVotes / $totalPlayers",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )

                Spacer(modifier = Modifier.height(12.dp))

                LinearProgressIndicator(
                    progress = { if (totalPlayers > 0) totalVotes.toFloat() / totalPlayers.toFloat() else 0f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                )

                if (totalVotes < totalPlayers) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Waiting for ${totalPlayers - totalVotes} more vote${if (totalPlayers - totalVotes != 1) "s" else ""}...",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Player vote status
        Text(
            text = "Player Votes",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.Start)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            lobbyPlayers.forEach { (playerId, lobbyPlayer) ->
                PlayerVoteStatus(
                    playerName = lobbyPlayer.name,
                    isLocalPlayer = playerId == localPlayerId,
                    vote = votes[playerId]
                )
            }
        }
    }
}

@Composable
private fun VoteButton(
    text: String,
    emoji: String,
    isSelected: Boolean,
    voteCount: Int,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val backgroundColor by animateColorAsState(
        targetValue = when {
            isSelected -> MaterialTheme.colorScheme.primary
            !enabled -> MaterialTheme.colorScheme.surfaceVariant
            else -> MaterialTheme.colorScheme.surface
        },
        label = "voteButtonBg"
    )

    val contentColor by animateColorAsState(
        targetValue = when {
            isSelected -> MaterialTheme.colorScheme.onPrimary
            !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> MaterialTheme.colorScheme.onSurface
        },
        label = "voteButtonContent"
    )

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isSelected) 8.dp else 2.dp
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = emoji,
                fontSize = 32.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = text,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = contentColor,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "$voteCount vote${if (voteCount != 1) "s" else ""}",
                fontSize = 12.sp,
                color = contentColor.copy(alpha = 0.7f)
            )

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onClick,
                enabled = enabled && !isSelected,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isSelected) Color.White.copy(alpha = 0.2f)
                    else MaterialTheme.colorScheme.primary
                )
            ) {
                Text(
                    text = if (isSelected) "Voted" else "Vote",
                    color = if (isSelected) Color.White else MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }
}

@Composable
private fun PlayerVoteStatus(
    playerName: String,
    isLocalPlayer: Boolean,
    vote: VoteChoice?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(
                    when (vote) {
                        VoteChoice.PLAY_AGAIN -> Color(0xFF4CAF50)
                        VoteChoice.END_LOBBY -> Color(0xFFFF9800)
                        null -> MaterialTheme.colorScheme.outline
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = when (vote) {
                    VoteChoice.PLAY_AGAIN -> "🔄"
                    VoteChoice.END_LOBBY -> "🏠"
                    null -> "?"
                },
                fontSize = 14.sp
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = playerName,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                if (isLocalPlayer) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "(You)",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Text(
                text = when (vote) {
                    VoteChoice.PLAY_AGAIN -> "Wants to play again"
                    VoteChoice.END_LOBBY -> "Wants to end lobby"
                    null -> "Hasn't voted yet"
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
