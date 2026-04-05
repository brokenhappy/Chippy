package com.woutwerkman.connectivitytest

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.flow.StateFlow

/**
 * Shows a Compose Desktop window for the connectivity test.
 * Blocks until the window is closed.
 */
fun showConnectivityTestWindow(uiState: StateFlow<ConnectivityTestUiState>) {
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Connectivity Test",
            state = rememberWindowState(width = 400.dp, height = 500.dp),
        ) {
            ConnectivityTestScreen(uiState)
        }
    }
}
