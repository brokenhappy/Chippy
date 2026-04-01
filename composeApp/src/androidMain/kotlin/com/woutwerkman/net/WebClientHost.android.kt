package com.woutwerkman.net

import kotlinx.coroutines.flow.Flow

// Android: stub for MVP — Phase 2 will add Ktor server hosting here.
actual fun createWebClientHost(
    connection: PeerNetConnection,
    stateFlow: Flow<PeerNetState>,
): WebClientHost? = null
