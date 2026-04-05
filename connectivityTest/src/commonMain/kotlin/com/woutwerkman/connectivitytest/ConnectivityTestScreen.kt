package com.woutwerkman.connectivitytest

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.StateFlow

private val Green = Color(0xFF4CAF50)
private val Red = Color(0xFFF44336)
private val Amber = Color(0xFFFFC107)
private val Gray = Color(0xFF9E9E9E)

@Composable
fun ConnectivityTestScreen(uiState: StateFlow<ConnectivityTestUiState>) {
    val state by uiState.collectAsState()

    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = "Connectivity Test",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )

                if (state.instanceId.isNotEmpty()) {
                    Text(
                        text = state.instanceId,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                PhaseIndicator(state.phase)

                if (state.targets.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    TargetTable(state.targets, state.phase)
                }
            }
        }
    }
}

@Composable
private fun PhaseIndicator(phase: ConnectivityTestPhase) {
    val color by animateColorAsState(
        when (phase) {
            ConnectivityTestPhase.STARTING -> Amber
            ConnectivityTestPhase.DISCOVERING -> Amber
            ConnectivityTestPhase.DONE -> Green
        }
    )
    val label = when (phase) {
        ConnectivityTestPhase.STARTING -> "Starting"
        ConnectivityTestPhase.DISCOVERING -> "Discovering"
        ConnectivityTestPhase.DONE -> "Done"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.12f)),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusDot(color)
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun TargetTable(targets: Map<TestPlatform, Boolean>, phase: ConnectivityTestPhase) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Discovery Targets",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            for ((platform, found) in targets.entries.sortedBy { it.key.name }) {
                TargetRow(platform, found, phase)
            }
        }
    }
}

@Composable
private fun TargetRow(platform: TestPlatform, found: Boolean, phase: ConnectivityTestPhase) {
    val color = when {
        found -> Green
        phase == ConnectivityTestPhase.STARTING -> Gray
        else -> Red
    }
    val label = when {
        found -> "Found"
        phase == ConnectivityTestPhase.STARTING -> "Waiting"
        else -> "Searching"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(color)
        Spacer(Modifier.width(12.dp))
        Text(
            text = platformDisplayName(platform),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = color,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun StatusDot(color: Color) {
    val animatedColor by animateColorAsState(color)
    Box(
        modifier = Modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(animatedColor)
    )
}

private fun platformDisplayName(platform: TestPlatform): String = when (platform) {
    TestPlatform.JVM -> "JVM (Desktop)"
    TestPlatform.ANDROID_SIMULATOR -> "Android Emulator"
    TestPlatform.ANDROID_REAL_DEVICE -> "Android Device"
    TestPlatform.IOS_SIMULATOR -> "iOS Simulator"
    TestPlatform.IOS_REAL_DEVICE -> "iOS Device"
    TestPlatform.MAC_BLE_HELPER -> "Mac BLE Helper"
}
