package com.woutwerkman

import com.woutwerkman.net.*
import kotlinx.coroutines.*
import platform.Foundation.NSLog

/**
 * Entry point for iOS connectivity test.
 * Call this from Swift when running in test mode.
 *
 * Now simplified: PeerNetConnection handles bidirectional verification internally.
 * We just wait for Joined events from all expected platforms.
 */
fun runIosConnectivityTest(
    instanceId: String,
    platforms: String,
    onComplete: (success: Boolean, message: String) -> Unit
) {
    NSLog("[iOS-Test] Starting: instanceId=$instanceId, platforms=$platforms")

    // Parse platforms, excluding iOS since we can only run one iOS instance
    val targetPlatforms = platforms.split(",").mapNotNull { platformString ->
        when (platformString.trim().lowercase()) {
            "jvm" -> "JVM"
            "android-simulator" -> "ANDROID_SIMULATOR"
            "ios-real-device" -> null  // Exclude iOS - we don't join ourselves
            else -> null
        }
    }.toSet()

    if (targetPlatforms.isEmpty()) {
        onComplete(false, "No valid platforms: $platforms")
        return
    }

    CoroutineScope(Dispatchers.Main + SupervisorJob()).launch {
        try {
            // 30 second timeout to allow time for Local Network permission prompt
            withTimeout(30_000) {
                withPeerNetConnection(
                    PeerNetConfig(
                        serviceName = "chippytest",
                        displayName = instanceId,
                        discoveryTimeoutMs = 10_000
                    )
                ) { connection ->
                    val joinedPlatforms = mutableSetOf<String>()

                    NSLog("[iOS-Test] Waiting for Joined events from: $targetPlatforms")

                    while (isActive) {
                        val message = withTimeoutOrNull(1000) {
                            connection.incoming.receiveCatching().getOrNull()
                        }

                        when (message) {
                            is PeerMessage.Event.Joined -> {
                                NSLog("[iOS-Test] Peer JOINED: ${message.peer.name} (${message.peer.id})")

                                val platform = when {
                                    message.peer.id.startsWith("jvm-") -> "JVM"
                                    message.peer.id.startsWith("ios-") -> "IOS_REAL_DEVICE"
                                    message.peer.id.startsWith("android-") -> "ANDROID_SIMULATOR"
                                    else -> null
                                }

                                if (platform != null && platform in targetPlatforms) {
                                    joinedPlatforms.add(platform)
                                    NSLog("[iOS-Test] Platform joined: $platform (${joinedPlatforms.size}/${targetPlatforms.size})")

                                    if (joinedPlatforms.containsAll(targetPlatforms)) {
                                        NSLog("[iOS-Test] SUCCESS!")
                                        onComplete(true, "All platforms connected")
                                        return@withPeerNetConnection
                                    }
                                }
                            }
                            is PeerMessage.Event.Left -> {
                                NSLog("[iOS-Test] Peer left: ${message.peerId}")
                            }
                            is PeerMessage.Received -> {
                                NSLog("[iOS-Test] Received data from ${message.fromPeerId}")
                            }
                            null -> { }
                        }
                    }

                    val missing = targetPlatforms - joinedPlatforms
                    onComplete(false, "Missing platforms: $missing")
                }
            }
        } catch (e: TimeoutCancellationException) {
            NSLog("[iOS-Test] Timeout")
            onComplete(false, "Timeout")
        } catch (e: Exception) {
            NSLog("[iOS-Test] Error: ${e.message}")
            onComplete(false, "Error: ${e.message}")
        }
    }
}
