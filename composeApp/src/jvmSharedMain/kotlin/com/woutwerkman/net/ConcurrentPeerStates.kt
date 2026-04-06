package com.woutwerkman.net

import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe [PeerStates] implementation backed by [ConcurrentHashMap].
 * Used on Android and JVM where Java concurrent collections are available.
 */
internal class ConcurrentPeerStates : PeerStates {
    private val map = ConcurrentHashMap<String, PeerState>()

    override fun snapshot(): Map<String, PeerState> = HashMap(map)

    override fun compute(peerId: String, transform: (PeerState?) -> PeerState?): PeerState? =
        map.compute(peerId) { _, existing -> transform(existing) }

    override fun remove(peerId: String): PeerState? = map.remove(peerId)

    override operator fun get(peerId: String): PeerState? = map[peerId]

    fun forEach(action: (String, PeerState) -> Unit) = map.forEach { (k, v) -> action(k, v) }
}

internal fun createPeerStatesImpl(): PeerStates = ConcurrentPeerStates()
