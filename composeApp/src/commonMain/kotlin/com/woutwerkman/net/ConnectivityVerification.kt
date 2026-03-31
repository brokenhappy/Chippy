package com.woutwerkman.net

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
            peerId.startsWith("android-") -> ANDROID_SIMULATOR
            peerId.startsWith("ios-") -> IOS_REAL_DEVICE
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
 * Runs the connectivity verification test.
 *
 * This test verifies bidirectional connectivity by waiting for Joined events.
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

private suspend fun CoroutineScope.verifyConnectivity(
    config: ConnectivityTestConfig,
    connection: PeerNetConnection
): ConnectivityTestResult {
    val joinedPlatforms = mutableSetOf<TestPlatform>()

    println("[${config.instanceId}] Waiting for Joined events from: ${config.targetPlatforms}")

    try {
        while (isActive) {
            val message = withTimeoutOrNull(1000) {
                try {
                    connection.incoming.receive()
                } catch (e: ClosedReceiveChannelException) {
                    null
                }
            }

            when (message) {
                is PeerMessage.Event.Connected -> {
                    val peer = message.peer
                    println("[${config.instanceId}] Peer JOINED: ${peer.name} (${peer.id})")

                    val platform = TestPlatform.fromPeerId(peer.id)
                    if (platform != null) {
                        val normalizedPlatform = normalizePlatform(platform, config.targetPlatforms)
                        if (normalizedPlatform != null) {
                            joinedPlatforms.add(normalizedPlatform)
                            println("[${config.instanceId}] Platform joined: $normalizedPlatform (${joinedPlatforms.size}/${config.targetPlatforms.size})")

                            if (joinedPlatforms.containsAll(config.targetPlatforms)) {
                                delay(1000) // Give other platforms a moment to also join
                                println("[${config.instanceId}] SUCCESS: All platforms connected!")
                                return ConnectivityTestResult.Success
                            }
                        }
                    }
                }
                is PeerMessage.Event.Disconnected -> {
                    println("[${config.instanceId}] Peer left: ${message.peerId}")
                }
                is PeerMessage.Received -> {
                    println("[${config.instanceId}] Received data from ${message.fromPeerId}")
                }
                null -> { }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        return ConnectivityTestResult.Failure("Error during test: ${e.message}", e)
    }

    val missingPlatforms = config.targetPlatforms - joinedPlatforms
    return ConnectivityTestResult.Failure(
        "Failed to connect to all platforms. Missing: $missingPlatforms. Connected: $joinedPlatforms"
    )
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
