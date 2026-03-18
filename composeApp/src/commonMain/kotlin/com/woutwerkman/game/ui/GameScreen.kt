package com.woutwerkman.game.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.woutwerkman.game.model.GamePhase
import com.woutwerkman.game.model.GameState
import com.woutwerkman.game.model.PlayerGameState
import kotlin.math.abs

@Composable
fun GameScreen(
    gameState: GameState,
    localPlayerId: String,
    countdownValue: Int?,
    onButtonPress: (String) -> Unit
) {
    val players = gameState.players.values.toList()
    val allZeros = players.isNotEmpty() && players.all { it.value == 0 }
    
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Header
            Text(
                text = "Get all to zero!",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Instructions
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(12.dp)
                ) {
                    Text(
                        text = "Your button: -2  |  Others: +1",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "Range: -25 to 25",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Player buttons grid
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(players) { playerState ->
                    PlayerButton(
                        playerState = playerState,
                        isLocalPlayer = playerState.playerId == localPlayerId,
                        enabled = gameState.gamePhase == GamePhase.PLAYING,
                        onClick = { onButtonPress(playerState.playerId) }
                    )
                }
            }
        }
        
        // Countdown overlay
        if (countdownValue != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (gameState.gamePhase == GamePhase.COUNTDOWN) {
                        Text(
                            text = "Get ready!",
                            fontSize = 24.sp,
                            color = Color.White
                        )
                    } else if (gameState.gamePhase == GamePhase.WIN_COUNTDOWN) {
                        Text(
                            text = "ALL ZEROS!",
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF4CAF50)
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = countdownValue.toString(),
                        fontSize = 96.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }
        
        // Win state glow effect
        if (allZeros && countdownValue == null) {
            val infiniteTransition = rememberInfiniteTransition(label = "winGlow")
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.3f,
                targetValue = 0.6f,
                animationSpec = infiniteRepeatable(
                    animation = tween(500),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "glowAlpha"
            )
            
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF4CAF50).copy(alpha = alpha))
            )
        }
    }
}

@Composable
private fun PlayerButton(
    playerState: PlayerGameState,
    isLocalPlayer: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val value = playerState.value
    val isZero = value == 0
    
    // Calculate color based on proximity to zero
    // Closer to 0 = greener
    val greenness = 1f - (abs(value) / 25f)
    val baseColor = lerp(
        Color(0xFFE57373), // Red when far from 0
        Color(0xFF4CAF50), // Green when at 0
        greenness
    )
    
    val backgroundColor by animateColorAsState(
        targetValue = if (isZero) Color(0xFF4CAF50) else baseColor,
        label = "buttonColor"
    )
    
    // Glow animation for zero
    val infiniteTransition = rememberInfiniteTransition(label = "zeroGlow")
    val glowScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isZero) 1.05f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowScale"
    )
    
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .scale(if (isZero) glowScale else 1f),
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = backgroundColor,
            disabledContainerColor = backgroundColor.copy(alpha = 0.6f)
        ),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = if (isZero) 8.dp else 4.dp
        )
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = playerState.playerName,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.9f)
            )
            
            Spacer(modifier = Modifier.height(4.dp))
            
            Text(
                text = value.toString(),
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            
            if (isLocalPlayer) {
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.White.copy(alpha = 0.3f))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "YOU",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }
    }
}
