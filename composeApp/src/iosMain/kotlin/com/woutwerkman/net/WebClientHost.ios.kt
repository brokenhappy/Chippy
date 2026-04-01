package com.woutwerkman.net

import kotlinx.coroutines.flow.Flow

actual fun createWebClientHost(
    connection: PeerNetConnection,
    stateFlow: Flow<PeerNetState>,
): WebClientHost? = null
