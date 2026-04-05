package com.woutwerkman.connectivitytest

import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.coroutines.flow.StateFlow

fun ConnectivityTestViewController(uiState: StateFlow<ConnectivityTestUiState>) =
    ComposeUIViewController { ConnectivityTestScreen(uiState) }
