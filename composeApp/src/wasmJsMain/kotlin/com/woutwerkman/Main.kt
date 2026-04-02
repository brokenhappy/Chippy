package com.woutwerkman

import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.woutwerkman.game.model.InternalState
import com.woutwerkman.net.*
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val body = document.body ?: return
    ComposeViewport(body) {
        WasmApp()
    }
}

@Composable
fun WasmApp() {
    val connectionState = remember { mutableStateOf<PeerNetConnection?>(null) }
    val publicState = remember { MutableStateFlow(PeerNetState()) }
    val internalState = remember { MutableStateFlow(InternalState()) }

    // Check for reconnection info from a page redirect (sessionStorage or URL params)
    val reconnectInfo = remember { parseReconnectInfo() }

    val initialWsUrl = remember {
        val location = window.location
        val wsProtocol = if (location.protocol == "https:") "wss:" else "ws:"
        "$wsProtocol//${location.host}/ws"
    }

    LaunchedEffect(Unit) {
        var wsUrl = initialWsUrl
        var reconnectId = reconnectInfo?.localId
        var playerName = reconnectInfo?.playerName ?: internalState.value.playerName
        val failedUrls = mutableSetOf<String>()

        while (isActive) {
            try {
                WebSocketPeerNetConnection.connect(
                    wsUrl = wsUrl,
                    reconnectId = reconnectId,
                    playerName = playerName,
                ) { conn ->
                    connectionState.value = conn
                    reconnectId = conn.localId
                    playerName = internalState.value.playerName
                    failedUrls.clear()
                    conn.state.collect { publicState.value = it }
                }
                break // block completed normally
            } catch (_: WebSocketDisconnectException) {
                connectionState.value = null
                failedUrls.add(wsUrl)

                val lastState = publicState.value
                val nextUrl = findAlternativeWsUrl(lastState, failedUrls)

                if (nextUrl != null) {
                    wsUrl = nextUrl
                    delay(1000)
                } else {
                    // All WebSocket attempts failed — try page redirect
                    redirectToAlternativeHost(lastState, reconnectId, playerName, failedUrls)
                    break
                }
            }
        }
    }

    // Restore screen from reconnect info if available
    LaunchedEffect(reconnectInfo) {
        val info = reconnectInfo ?: return@LaunchedEffect
        if (info.playerName.isNotEmpty()) {
            internalState.value = internalState.value.copy(playerName = info.playerName)
        }
    }

    AppContent(
        connection = connectionState.value,
        publicState = publicState,
        internalState = internalState,
    )
}

// --- Reconnection helpers ---

private data class ReconnectInfo(
    val localId: String,
    val playerName: String,
)

private fun parseReconnectInfo(): ReconnectInfo? {
    val search = window.location.search
    if (!search.contains("reconnectId=")) return null
    val params = search.removePrefix("?").split("&").associate {
        val parts = it.split("=", limit = 2)
        if (parts.size == 2) parts[0] to urlDecode(parts[1]) else parts[0] to ""
    }
    val id = params["reconnectId"]?.takeIf { it.isNotEmpty() } ?: return null
    val name = params["playerName"] ?: "Web Player"
    return ReconnectInfo(id, name)
}

private fun findAlternativeWsUrl(state: PeerNetState, failedUrls: Set<String>): String? {
    return state.discoveredPeers.values
        .filter { it.webPort > 0 }
        .sortedBy { platformPriority(it.platform) }
        .map { peer ->
            val scheme = if (peer.webSecure) "wss:" else "ws:"
            "$scheme//${peer.address}:${peer.webPort}/ws"
        }
        .firstOrNull { it !in failedUrls }
}

private fun redirectToAlternativeHost(
    state: PeerNetState,
    reconnectId: String?,
    playerName: String,
    failedUrls: Set<String>,
) {
    val peer = state.discoveredPeers.values
        .filter { it.webPort > 0 }
        .filter { peer ->
            val scheme = if (peer.webSecure) "wss:" else "ws:"
            "$scheme//${peer.address}:${peer.webPort}/ws" !in failedUrls
        }
        .sortedBy { platformPriority(it.platform) }
        .firstOrNull() ?: return

    val scheme = if (peer.webSecure) "https" else "http"
    val baseUrl = "$scheme://${peer.address}:${peer.webPort}"

    val params = buildString {
        if (reconnectId != null) {
            append("?reconnectId=")
            append(urlEncode(reconnectId))
            append("&playerName=")
            append(urlEncode(playerName))
        }
    }

    window.location.href = "$baseUrl/$params"
}

/**
 * Platform reconnection priority: JVM (most stable) > Android > iOS.
 */
private fun platformPriority(platform: String): Int = when {
    platform.startsWith("Java", ignoreCase = true) -> 0
    platform.startsWith("Android", ignoreCase = true) -> 1
    else -> 2
}

private fun urlEncode(str: String): String = buildString {
    for (c in str) {
        when {
            c.isLetterOrDigit() || c in "-_.~" -> append(c)
            else -> {
                for (b in c.toString().encodeToByteArray()) {
                    val hex = (b.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0')
                    append('%')
                    append(hex)
                }
            }
        }
    }
}

private fun urlDecode(str: String): String = buildString {
    var i = 0
    while (i < str.length) {
        when {
            str[i] == '%' && i + 2 < str.length -> {
                val hex = str.substring(i + 1, i + 3)
                append(hex.toInt(16).toChar())
                i += 3
            }
            str[i] == '+' -> {
                append(' ')
                i++
            }
            else -> {
                append(str[i])
                i++
            }
        }
    }
}
