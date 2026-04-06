package com.woutwerkman.connectivitytest

import com.woutwerkman.net.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

enum class ConnectivityTestPhase { STARTING, DISCOVERING, WAITING_FOR_SHUTDOWN, DONE }

data class ConnectivityTestUiState(
    val instanceId: String = "",
    val phase: ConnectivityTestPhase = ConnectivityTestPhase.STARTING,
    val targets: Map<TestPlatform, Boolean> = emptyMap(),
)

/**
 * Runs the READY → START → FOUND → DONE protocol that all platform test runners share.
 *
 * Each platform provides its own transport via [sendLine]/[readLine] (TCP, Kotlin channels, etc.)
 * but the discovery logic and UI state updates are identical.
 */
suspend fun runConnectivityTestProtocol(
    instanceId: String,
    targets: Set<TestPlatform>,
    uiState: MutableStateFlow<ConnectivityTestUiState>,
    sendLine: suspend (String) -> Unit,
    readLine: suspend () -> String?,
) {
    withPeerNetConnection(
        PeerNetConfig(serviceName = "chippy-test", displayName = instanceId)
    ) { conn ->
        sendLine("READY")

        val startLine = readLine()
        if (startLine != "START") {
            sendLine("ERROR:Expected START, got: $startLine")
            return@withPeerNetConnection
        }

        uiState.update { it.copy(phase = ConnectivityTestPhase.DISCOVERING) }

        val found = mutableSetOf<TestPlatform>()
        conn.state.first { state ->
            val matched = matchedPlatforms(state, instanceId, targets)
            for (p in matched - found) {
                found.add(p)
                sendLine("FOUND:${p.toPlatformString()}")
                uiState.update { it.copy(targets = it.targets + (p to true)) }
            }
            found.containsAll(targets)
        }

        sendLine("DONE")

        // Keep the peer connection alive until the coordinator signals SHUTDOWN.
        // Without this, fast platforms tear down their connection before slow platforms
        // have discovered them — the Left event removes the peer from state before the
        // slow platform's state.first { } can observe it.
        uiState.update { it.copy(phase = ConnectivityTestPhase.WAITING_FOR_SHUTDOWN) }
        readLine() // SHUTDOWN
        uiState.update { it.copy(phase = ConnectivityTestPhase.DONE) }
    }
}

fun matchedPlatforms(
    state: PeerNetState,
    instanceId: String,
    targets: Set<TestPlatform>,
): Set<TestPlatform> {
    val matched = mutableSetOf<TestPlatform>()
    for ((peerId, peer) in state.discoveredPeers) {
        if (peer.name == instanceId) continue
        // Prefer display name for platform detection (the test coordinator sets distinctive
        // instanceIds like "ios-sim", "ios-device", "jvm-1"), fall back to peer ID prefix.
        val platform = TestPlatform.fromPeerId(peer.name) ?: TestPlatform.fromPeerId(peerId) ?: continue
        val normalized = normalizePlatform(platform, targets)
        if (normalized != null) matched.add(normalized)
    }
    return matched
}

fun normalizePlatform(platform: TestPlatform, targets: Set<TestPlatform>): TestPlatform? {
    if (platform in targets) return platform
    if (platform == TestPlatform.IOS_REAL_DEVICE || platform == TestPlatform.IOS_SIMULATOR) {
        if (TestPlatform.IOS_REAL_DEVICE in targets) return TestPlatform.IOS_REAL_DEVICE
        if (TestPlatform.IOS_SIMULATOR in targets) return TestPlatform.IOS_SIMULATOR
    }
    if (platform == TestPlatform.ANDROID_REAL_DEVICE || platform == TestPlatform.ANDROID_SIMULATOR) {
        if (TestPlatform.ANDROID_REAL_DEVICE in targets) return TestPlatform.ANDROID_REAL_DEVICE
        if (TestPlatform.ANDROID_SIMULATOR in targets) return TestPlatform.ANDROID_SIMULATOR
    }
    return null
}
