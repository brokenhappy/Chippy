package com.woutwerkman.game.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.woutwerkman.game.model.LobbyPlayer
import com.woutwerkman.game.model.LobbyState

@Composable
fun LobbyScreen(
    lobbyState: LobbyState,
    localPlayerId: String,
    countdownValue: Int?,
    onToggleReady: () -> Unit,
    onLeaveLobby: () -> Unit
) {
    val players = lobbyState.players.values.toList()
    val localPlayer = lobbyState.players[localPlayerId]
    val isReady = localPlayer?.isReady ?: false
    val allReady = players.isNotEmpty() && players.all { it.isReady }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Lobby",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "${players.size} player${if (players.size != 1) "s" else ""}",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            TextButton(onClick = onLeaveLobby) {
                Text(
                    text = "Leave",
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Countdown overlay
        if (countdownValue != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Game starting in",
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = countdownValue.toString(),
                        fontSize = 64.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
        }
        
        // Players list
        Text(
            text = "Players",
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(12.dp))
        
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(players) { lobbyPlayer ->
                LobbyPlayerCard(
                    lobbyPlayer = lobbyPlayer,
                    isLocalPlayer = lobbyPlayer.player.id == localPlayerId
                )
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Ready button
        val buttonScale by animateFloatAsState(
            targetValue = if (isReady) 1.05f else 1f,
            label = "buttonScale"
        )
        
        val buttonColor by animateColorAsState(
            targetValue = if (isReady) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary,
            label = "buttonColor"
        )
        
        Button(
            onClick = onToggleReady,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .scale(buttonScale),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = buttonColor),
            enabled = countdownValue == null
        ) {
            Text(
                text = if (isReady) "Ready!" else "Ready Up",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        
        if (allReady && countdownValue == null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Waiting for game to start...",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }
}

@Composable
private fun LobbyPlayerCard(
    lobbyPlayer: LobbyPlayer,
    isLocalPlayer: Boolean
) {
    val backgroundColor by animateColorAsState(
        targetValue = if (lobbyPlayer.isReady) {
            Color(0xFF4CAF50).copy(alpha = 0.15f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        label = "cardBackground"
    )
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (lobbyPlayer.isReady) Color(0xFF4CAF50)
                        else MaterialTheme.colorScheme.primaryContainer
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (lobbyPlayer.isReady) "✓" else lobbyPlayer.player.name.first().uppercase(),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (lobbyPlayer.isReady) Color.White
                    else MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = lobbyPlayer.player.name,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                    if (isLocalPlayer) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "(You)",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Text(
                    text = if (lobbyPlayer.isReady) "Ready" else "Not ready",
                    fontSize = 12.sp,
                    color = if (lobbyPlayer.isReady) Color(0xFF4CAF50)
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            if (lobbyPlayer.isReady) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF4CAF50)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "✓",
                        fontSize = 14.sp,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
