package com.woutwerkman

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.woutwerkman.net.PeerNetConfig
import com.woutwerkman.net.PeerMessage
import com.woutwerkman.net.withPeerNetConnection
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException

class MainActivity : ComponentActivity() {
    private var testScope: CoroutineScope? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Check if we're in connectivity test mode
        val isTestMode = intent.getBooleanExtra("connectivity_test", false)
        val instanceId = intent.getStringExtra("instanceId") ?: "android-1"
        val platforms = intent.getStringExtra("platforms") ?: "jvm,android-simulator"

        if (isTestMode) {
            Log.i("ConnectivityTest", "[$instanceId] Starting connectivity test mode")
            runConnectivityTest(instanceId, platforms)
        } else {
            setContent {
                App()
            }
        }
    }

    private fun runConnectivityTest(instanceId: String, platforms: String) {
        testScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

        testScope?.launch {
            try {
                withTimeout(60_000) {
                    withPeerNetConnection(
                        PeerNetConfig(
                            serviceName = "chippy",
                            displayName = instanceId,
                            discoveryTimeoutMs = 30_000
                        )
                    ) { connection ->
                        val discoveredPeers = mutableMapOf<String, String>()
                        val targetPlatforms = platforms.split(",").map { it.trim().lowercase() }.toSet()
                        val discoveredPlatforms = mutableSetOf<String>()

                        val startTime = System.currentTimeMillis()
                        val timeout = 30_000L

                        Log.i("ConnectivityTest", "[$instanceId] Looking for platforms: $targetPlatforms")

                        while (isActive) {
                            if (System.currentTimeMillis() - startTime > timeout) {
                                break
                            }

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
                                    Log.i("ConnectivityTest", "[$instanceId] Discovered: ${peer.name} (${peer.id})")
                                    discoveredPeers[peer.id] = peer.name

                                    // Determine platform from peer ID prefix
                                    val platform = when {
                                        peer.id.startsWith("jvm-") -> "jvm"
                                        peer.id.startsWith("android-") -> "android-simulator"
                                        peer.id.startsWith("ios-") -> "ios-real-device" // Could be either
                                        else -> null
                                    }

                                    if (platform != null) {
                                        // Check for matching platform (ios-real-device and ios-simulator are compatible)
                                        val matchingPlatform = when {
                                            platform in targetPlatforms -> platform
                                            platform == "ios-real-device" && "ios-simulator" in targetPlatforms -> "ios-simulator"
                                            platform == "ios-simulator" && "ios-real-device" in targetPlatforms -> "ios-real-device"
                                            else -> null
                                        }
                                        if (matchingPlatform != null) {
                                            discoveredPlatforms.add(matchingPlatform)
                                            Log.i("ConnectivityTest", "[$instanceId] Platform found: $matchingPlatform (${discoveredPlatforms.size}/${targetPlatforms.size})")
                                        }
                                    }

                                    if (discoveredPlatforms.containsAll(targetPlatforms)) {
                                        Log.i("ConnectivityTest", "[$instanceId] SUCCESS: All platforms discovered!")
                                        setResult(RESULT_OK)
                                        finish()
                                        return@withPeerNetConnection
                                    }
                                }
                                is PeerMessage.Event.Lost -> {
                                    Log.i("ConnectivityTest", "[$instanceId] Lost peer: ${message.peerId}")
                                    discoveredPeers.remove(message.peerId)
                                }
                                else -> { /* ignore */ }
                            }
                        }

                        val missing = targetPlatforms - discoveredPlatforms
                        Log.e("ConnectivityTest", "[$instanceId] FAILURE: Missing platforms: $missing")
                        Log.e("ConnectivityTest", "[$instanceId] Discovered ${discoveredPeers.size} peers")
                        setResult(RESULT_CANCELED)
                        finish()
                    }
                }
            } catch (e: Exception) {
                Log.e("ConnectivityTest", "[$instanceId] ERROR: ${e.message}", e)
                setResult(RESULT_CANCELED)
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        testScope?.cancel()
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}