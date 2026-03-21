package com.woutwerkman

import androidx.compose.ui.window.ComposeUIViewController
import com.woutwerkman.net.PeerNetConfig
import com.woutwerkman.net.PeerMessage
import com.woutwerkman.net.withPeerNetConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import platform.Foundation.NSLog

// Global scope for peer discovery that starts immediately
private val globalDiscoveryScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
private var discoveryStarted = false

private fun startGlobalPeerDiscovery() {
    if (discoveryStarted) return
    discoveryStarted = true

    NSLog("[iOS] Starting global peer discovery")

    globalDiscoveryScope.launch {
        try {
            NSLog("[iOS] Launching withPeerNetConnection")
            withPeerNetConnection(
                PeerNetConfig(
                    serviceName = "chippy",
                    displayName = "iOS-Device",
                    discoveryTimeoutMs = 300_000 // 5 minutes
                )
            ) { connection ->
                NSLog("[iOS] Peer discovery active, listening for events...")
                while (true) {
                    val message = connection.incoming.receiveCatching().getOrNull()
                    if (message == null) {
                        NSLog("[iOS] Channel closed")
                        break
                    }
                    when (message) {
                        is PeerMessage.Event.Discovered -> {
                            NSLog("[iOS] Discovered peer: ${message.peer.name} (${message.peer.id})")
                        }
                        is PeerMessage.Event.Lost -> {
                            NSLog("[iOS] Lost peer: ${message.peerId}")
                        }
                        is PeerMessage.Data -> {
                            NSLog("[iOS] Received data from: ${message.fromPeerId}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            NSLog("[iOS] Peer discovery error: ${e.message}")
            e.printStackTrace()
        }
    }
}

fun MainViewController() = ComposeUIViewController {
    // Start peer discovery immediately when the view controller is created
    startGlobalPeerDiscovery()

    App()
}