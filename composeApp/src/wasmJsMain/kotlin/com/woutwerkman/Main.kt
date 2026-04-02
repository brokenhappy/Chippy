package com.woutwerkman

import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.woutwerkman.game.model.InternalState
import com.woutwerkman.net.PeerNetConnection
import com.woutwerkman.net.PeerNetState
import com.woutwerkman.net.WebSocketPeerNetConnection
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.flow.MutableStateFlow

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

    // Derive WebSocket URL from the page URL (same host, /ws path)
    val wsUrl = remember {
        val location = window.location
        val wsProtocol = if (location.protocol == "https:") "wss:" else "ws:"
        "$wsProtocol//${location.host}/ws"
    }

    LaunchedEffect(wsUrl) {
        WebSocketPeerNetConnection.connect(wsUrl) { conn ->
            connectionState.value = conn
            conn.state.collect { publicState.value = it }
        }
    }

    AppContent(
        connection = connectionState.value,
        publicState = publicState,
        internalState = internalState,
    )
}
