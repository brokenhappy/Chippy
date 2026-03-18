package com.woutwerkman.game.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.woutwerkman.game.ConnectedGroup
import com.woutwerkman.game.model.ConnectionRequest
import com.woutwerkman.game.model.DiscoveredPeer
import com.woutwerkman.game.model.Player

/**
 * Represents a peer with their invite status for unified display
 */
private data class PeerDisplayInfo(
    val peer: DiscoveredPeer,
    val hasIncomingInvite: Boolean,
    val hasSentInvite: Boolean,
    val incomingRequest: ConnectionRequest?
)

@Composable
fun HomeScreen(
    playerName: String,
    discoveredPeers: List<DiscoveredPeer>,
    connectedGroups: List<ConnectedGroup>,
    pendingRequests: List<ConnectionRequest>,
    sentInvites: Set<String>,
    onSettingsClick: () -> Unit,
    onPeerClick: (DiscoveredPeer) -> Unit,
    onAcceptRequest: (ConnectionRequest) -> Unit,
    onRejectRequest: (ConnectionRequest) -> Unit,
    onEnterLobby: () -> Unit
) {
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
                    text = "Chippy",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Playing as: $playerName",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(
                onClick = onSettingsClick,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Text("⚙", fontSize = 24.sp)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Connected groups (lobbies)
        if (connectedGroups.isNotEmpty()) {
            Text(
                text = "Your Lobby",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))

            connectedGroups.forEach { group ->
                ConnectedGroupCard(
                    group = group,
                    onClick = onEnterLobby
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // Nearby peers section
        Text(
            text = "Nearby Players",
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(8.dp))

        // Build unified peer list with invite status
        val peersInLobby = connectedGroups.flatMap { it.players.map { p -> p.id } }.toSet()
        val pendingRequestMap = pendingRequests.associateBy { it.fromPlayer.id }

        // Create display info for discovered peers
        val peerDisplayList = discoveredPeers
            .filter { it.id !in peersInLobby }
            .map { peer ->
                PeerDisplayInfo(
                    peer = peer,
                    hasIncomingInvite = peer.id in pendingRequestMap,
                    hasSentInvite = peer.id in sentInvites,
                    incomingRequest = pendingRequestMap[peer.id]
                )
            }
            // Sort: incoming invites first, then sent invites, then regular peers
            .sortedWith(compareByDescending<PeerDisplayInfo> { it.hasIncomingInvite }
                .thenByDescending { it.hasSentInvite })

        // Add peers who sent invites but aren't in discovered peers list yet
        val discoveredPeerIds = discoveredPeers.map { it.id }.toSet()
        val additionalInvites = pendingRequests
            .filter { it.fromPlayer.id !in discoveredPeerIds && it.fromPlayer.id !in peersInLobby }
            .map { request ->
                PeerDisplayInfo(
                    peer = DiscoveredPeer(
                        id = request.fromPlayer.id,
                        name = request.fromPlayer.name,
                        address = "unknown",
                        port = 0
                    ),
                    hasIncomingInvite = true,
                    hasSentInvite = false,
                    incomingRequest = request
                )
            }

        val allPeers = additionalInvites + peerDisplayList

        if (allPeers.isEmpty() && connectedGroups.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "🔍",
                            fontSize = 32.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Searching for nearby players...",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Make sure you're on the same WiFi network",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        } else if (allPeers.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "All nearby players are in your lobby!",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(allPeers, key = { it.peer.id }) { peerInfo ->
                    PeerCard(
                        peerInfo = peerInfo,
                        onInvite = { onPeerClick(peerInfo.peer) },
                        onAccept = { peerInfo.incomingRequest?.let { onAcceptRequest(it) } },
                        onReject = { peerInfo.incomingRequest?.let { onRejectRequest(it) } }
                    )
                }
            }
        }
    }
}

@Composable
private fun PeerCard(
    peerInfo: PeerDisplayInfo,
    onInvite: () -> Unit,
    onAccept: () -> Unit,
    onReject: () -> Unit
) {
    val containerColor = when {
        peerInfo.hasIncomingInvite -> MaterialTheme.colorScheme.tertiaryContainer
        peerInfo.hasSentInvite -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surface
    }

    val avatarColor = when {
        peerInfo.hasIncomingInvite -> MaterialTheme.colorScheme.tertiary
        peerInfo.hasSentInvite -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.primaryContainer
    }

    val avatarTextColor = when {
        peerInfo.hasIncomingInvite -> MaterialTheme.colorScheme.onTertiary
        peerInfo.hasSentInvite -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onPrimaryContainer
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (!peerInfo.hasIncomingInvite && !peerInfo.hasSentInvite) {
                    Modifier.clickable(onClick = onInvite)
                } else {
                    Modifier
                }
            ),
        colors = CardDefaults.cardColors(containerColor = containerColor),
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
                    .background(avatarColor),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = peerInfo.peer.name.first().uppercase(),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = avatarTextColor
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = peerInfo.peer.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = when {
                        peerInfo.hasIncomingInvite -> "Wants to play with you"
                        peerInfo.hasSentInvite -> "Invite sent - waiting..."
                        else -> "Tap to invite"
                    },
                    fontSize = 12.sp,
                    color = when {
                        peerInfo.hasIncomingInvite -> MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
                        peerInfo.hasSentInvite -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            when {
                peerInfo.hasIncomingInvite -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = onReject,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text("Decline", fontSize = 12.sp)
                        }
                        Button(
                            onClick = onAccept,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text("Accept", fontSize = 12.sp)
                        }
                    }
                }
                peerInfo.hasSentInvite -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                else -> {
                    Text(
                        text = "+",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectedGroupCard(
    group: ConnectedGroup,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${group.players.size} players connected",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "Enter",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Player avatars row
            Row(
                horizontalArrangement = Arrangement.spacedBy((-8).dp)
            ) {
                group.players.take(5).forEach { player ->
                    PlayerAvatar(player = player)
                }
                if (group.players.size > 5) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "+${group.players.size - 5}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Player names
            Text(
                text = group.players.joinToString(", ") { it.name },
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun PlayerAvatar(player: Player) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Color(0xFF4CAF50)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = player.name.first().uppercase(),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
    }
}
