package com.woutwerkman.connectivitytest

import com.woutwerkman.net.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException

/**
 * Platform identifiers for connectivity testing.
 */
enum class TestPlatform {
    JVM,
    ANDROID_SIMULATOR,
    ANDROID_REAL_DEVICE,
    IOS_SIMULATOR,
    IOS_REAL_DEVICE;

    companion object {
        fun fromString(s: String): TestPlatform? = when (s.lowercase().trim()) {
            "jvm" -> JVM
            "android-simulator" -> ANDROID_SIMULATOR
            "android-real-device" -> ANDROID_REAL_DEVICE
            "ios-simulator" -> IOS_SIMULATOR
            "ios-real-device" -> IOS_REAL_DEVICE
            else -> null
        }

        fun fromPeerId(peerId: String): TestPlatform? = when {
            peerId.startsWith("jvm-") -> JVM
            peerId.startsWith("android-") -> ANDROID_SIMULATOR // Could be either, treating as simulator
            peerId.startsWith("ios-") -> IOS_REAL_DEVICE // Could be either, treating as real device
            // If peer ID doesn't match any known pattern, assume it's from iOS
            // (the old UdpTransport code uses random names without platform prefix)
            else -> IOS_REAL_DEVICE
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
 * Runs the connectivity verification test.
 *
 * This test:
 * 1. Starts peer discovery
 * 2. Waits to discover peers from the expected platforms
 * 3. Verifies that we can see all expected peer types
 *
 * @throws Exception if connectivity test fails
 */
suspend fun runConnectivityTest(config: ConnectivityTestConfig): ConnectivityTestResult {
    println("[${config.instanceId}] Starting connectivity test")
    println("[${config.instanceId}] Looking for platforms: ${config.targetPlatforms}")

    return try {
        withTimeout(config.testTimeoutMs) {
            withPeerNetConnection(
                PeerNetConfig(
                    serviceName = "chippy",
                    displayName = config.instanceId,
                    discoveryTimeoutMs = config.discoveryTimeoutMs
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

private suspend fun CoroutineScope.verifyConnectivity(
    config: ConnectivityTestConfig,
    connection: PeerNetConnection
): ConnectivityTestResult {
    val discoveredPeers = mutableMapOf<String, PeerInfo>()
    val discoveredPlatforms = mutableSetOf<TestPlatform>()

    // We need to discover at least one peer from each target platform (excluding our own)
    // Since we run 2 instances per platform, we should see at least 1 other instance of our own platform
    // plus instances from other platforms

    val expectedPeerCount = config.targetPlatforms.size * 2 - 1 // Total instances minus ourselves
    println("[${config.instanceId}] Expecting to discover at least $expectedPeerCount peers")

    val startTime = System.currentTimeMillis()
    val discoveryTimeout = config.discoveryTimeoutMs

    try {
        while (isActive) {
            val elapsed = System.currentTimeMillis() - startTime
            if (elapsed > discoveryTimeout) {
                break
            }

            // Use withTimeoutOrNull to avoid blocking forever
            val message = withTimeoutOrNull(1000) {
                try {
                    connection.incoming.receive()
                } catch (e: ClosedReceiveChannelException) {
                    null
                }
            }

            when (message) {
                is PeerMessage.Event.Discovered -> {
                    val peer = message.peer
                    println("[${config.instanceId}] Discovered peer: ${peer.name} (${peer.id}) at ${peer.address}")
                    discoveredPeers[peer.id] = peer

                    // Determine platform from peer ID prefix
                    val platform = TestPlatform.fromPeerId(peer.id)

                    if (platform != null) {
                        // Check if this platform or a compatible one is in target platforms
                        // iOS real device and simulator are treated as compatible
                        val matchingPlatform = when (platform) {
                            TestPlatform.IOS_REAL_DEVICE ->
                                if (TestPlatform.IOS_REAL_DEVICE in config.targetPlatforms) TestPlatform.IOS_REAL_DEVICE
                                else if (TestPlatform.IOS_SIMULATOR in config.targetPlatforms) TestPlatform.IOS_SIMULATOR
                                else null
                            TestPlatform.IOS_SIMULATOR ->
                                if (TestPlatform.IOS_SIMULATOR in config.targetPlatforms) TestPlatform.IOS_SIMULATOR
                                else if (TestPlatform.IOS_REAL_DEVICE in config.targetPlatforms) TestPlatform.IOS_REAL_DEVICE
                                else null
                            else -> if (platform in config.targetPlatforms) platform else null
                        }

                        if (matchingPlatform != null) {
                            discoveredPlatforms.add(matchingPlatform)
                            println("[${config.instanceId}] Discovered platform: $matchingPlatform (${discoveredPlatforms.size}/${config.targetPlatforms.size})")
                        }
                    }

                    // Check if we've discovered all target platforms
                    if (discoveredPlatforms.containsAll(config.targetPlatforms)) {
                        println("[${config.instanceId}] All target platforms discovered!")
                        return ConnectivityTestResult.Success
                    }
                }

                is PeerMessage.Event.Lost -> {
                    println("[${config.instanceId}] Lost peer: ${message.peerId}")
                    discoveredPeers.remove(message.peerId)
                }

                is PeerMessage.Data -> {
                    println("[${config.instanceId}] Received data from ${message.fromPeerId}: ${message.payload.size} bytes")
                }

                null -> {
                    // Timeout, continue loop
                }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        return ConnectivityTestResult.Failure("Error during discovery: ${e.message}", e)
    }

    // Check results
    val missingPlatforms = config.targetPlatforms - discoveredPlatforms
    return if (missingPlatforms.isEmpty()) {
        ConnectivityTestResult.Success
    } else {
        ConnectivityTestResult.Failure(
            "Failed to discover all platforms. Missing: $missingPlatforms. " +
                    "Discovered ${discoveredPeers.size} peers from platforms: $discoveredPlatforms"
        )
    }
}
