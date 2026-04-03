package com.woutwerkman.net

import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/**
 * Platform identifiers for connectivity testing.
 */
enum class TestPlatform {
    JVM,
    ANDROID_SIMULATOR,
    ANDROID_REAL_DEVICE,
    IOS_SIMULATOR,
    IOS_REAL_DEVICE,
    MAC_BLE_HELPER;

    fun toPlatformString(): String = name.lowercase().replace('_', '-')

    companion object {
        fun fromString(s: String): TestPlatform? = when (s.lowercase().trim()) {
            "jvm" -> JVM
            "android-simulator" -> ANDROID_SIMULATOR
            "android-real-device" -> ANDROID_REAL_DEVICE
            "ios-simulator" -> IOS_SIMULATOR
            "ios-real-device" -> IOS_REAL_DEVICE
            "mac-ble-helper" -> MAC_BLE_HELPER
            else -> null
        }

        fun fromPeerId(peerId: String): TestPlatform? = when {
            peerId.startsWith("jvm-") -> JVM
            peerId.startsWith("android-sim-") -> ANDROID_SIMULATOR
            peerId.startsWith("android-device-") -> ANDROID_REAL_DEVICE
            peerId.startsWith("android-") -> ANDROID_SIMULATOR // fallback for generic android IDs
            peerId.startsWith("ios-sim") -> IOS_SIMULATOR
            peerId.startsWith("ios-device") -> IOS_REAL_DEVICE
            peerId.startsWith("ios-") -> IOS_REAL_DEVICE // fallback for generic ios IDs
            peerId.startsWith("mac-ble-") -> MAC_BLE_HELPER
            else -> null
        }
    }
}

/**
 * Configuration for the connectivity test.
 */
data class ConnectivityTestConfig(
    val instanceId: String,
    val targetPlatforms: Set<TestPlatform>,
    val discoveryTimeoutMs: Long = 30_000,
    val testTimeoutMs: Long = 60_000
)

/**
 * Result of the connectivity test.
 */
sealed class ConnectivityTestResult {
    data object Success : ConnectivityTestResult()
    data class Failure(val message: String, val cause: Throwable? = null) : ConnectivityTestResult()
}

/**
 * Runs the connectivity verification test using the linearized peer network.
 *
 * Verifies that all target platforms join the network and appear in the
 * collectively agreed-upon state (discoveredPeers).
 */
suspend fun runConnectivityTest(config: ConnectivityTestConfig): ConnectivityTestResult {
    println("[${config.instanceId}] Starting connectivity test")
    println("[${config.instanceId}] Looking for platforms: ${config.targetPlatforms}")

    return try {
        withTimeout(config.testTimeoutMs) {
            withPeerNetConnection(
                PeerNetConfig(
                    serviceName = "chippy-test",
                    displayName = config.instanceId
                )
            ) { connection ->
                verifyConnectivity(config, connection)
            }
        }
    } catch (e: TimeoutCancellationException) {
        ConnectivityTestResult.Failure("Test timed out after ${config.testTimeoutMs}ms", e)
    } catch (e: Exception) {
        ConnectivityTestResult.Failure("Test failed: ${e.message}", e)
    }
}

private suspend fun verifyConnectivity(
    config: ConnectivityTestConfig,
    connection: PeerNetConnection
): ConnectivityTestResult {
    println("[${config.instanceId}] Waiting for all target platforms in linearized state...")

    try {
        // Wait until the linearized state contains all target platforms
        val finalState = connection.state.first { state ->
            val joinedPlatforms = matchedPlatforms(state, config)
            println("[${config.instanceId}] Linearized state has ${state.discoveredPeers.size} peers, matched platforms: $joinedPlatforms (need: ${config.targetPlatforms})")
            joinedPlatforms.containsAll(config.targetPlatforms)
        }

        println("[${config.instanceId}] All platforms found in linearized state!")
        println("[${config.instanceId}] Peers: ${finalState.discoveredPeers.values.map { "${it.name} (${it.id})" }}")

        // Keep connection alive so slower peers can complete their handshakes
        delay(5.seconds)
        println("[${config.instanceId}] SUCCESS: All platforms connected via linearized peer network!")
        return ConnectivityTestResult.Success
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        return ConnectivityTestResult.Failure("Error during test: ${e.message}", e)
    }
}

private fun matchedPlatforms(state: PeerNetState, config: ConnectivityTestConfig): Set<TestPlatform> {
    val matched = mutableSetOf<TestPlatform>()
    for ((peerId, peer) in state.discoveredPeers) {
        // Skip ourselves
        if (peer.name == config.instanceId) continue

        val platform = TestPlatform.fromPeerId(peerId) ?: continue
        val normalized = normalizePlatform(platform, config.targetPlatforms)
        if (normalized != null) {
            matched.add(normalized)
        }
    }
    return matched
}

private fun normalizePlatform(platform: TestPlatform, targets: Set<TestPlatform>): TestPlatform? {
    if (platform in targets) return platform

    // If we're looking for any iOS platform, accept both real and simulator
    if (platform == TestPlatform.IOS_REAL_DEVICE || platform == TestPlatform.IOS_SIMULATOR) {
        if (TestPlatform.IOS_REAL_DEVICE in targets) return TestPlatform.IOS_REAL_DEVICE
        if (TestPlatform.IOS_SIMULATOR in targets) return TestPlatform.IOS_SIMULATOR
    }

    // If we're looking for any Android platform, accept both real and simulator
    if (platform == TestPlatform.ANDROID_REAL_DEVICE || platform == TestPlatform.ANDROID_SIMULATOR) {
        if (TestPlatform.ANDROID_REAL_DEVICE in targets) return TestPlatform.ANDROID_REAL_DEVICE
        if (TestPlatform.ANDROID_SIMULATOR in targets) return TestPlatform.ANDROID_SIMULATOR
    }

    return null
}
