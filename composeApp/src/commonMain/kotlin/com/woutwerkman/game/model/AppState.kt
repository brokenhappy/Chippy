package com.woutwerkman.game.model

import com.woutwerkman.net.PeerNetState

/**
 * Purely local UI state — not shared over the network.
 */
data class InternalState(
    val screen: Screen = Screen.HOME,
    val playerName: String = generateRandomName(),
    val showSettings: Boolean = false,
)

/**
 * The complete state of the app: public network state + local UI state.
 * The entire view is a pure function of this.
 */
data class WholeState(
    val localId: String = "",
    val publicState: PeerNetState = PeerNetState(),
    val internalState: InternalState = InternalState(),
)
