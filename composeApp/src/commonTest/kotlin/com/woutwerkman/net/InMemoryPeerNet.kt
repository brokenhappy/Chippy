package com.woutwerkman.net

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/**
 * In-memory test double for the raw peer network.
 *
 * Creates multiple peers that communicate through in-memory channels
 * instead of UDP/mDNS. Peers are "connected" instantly when added.
 */
class InMemoryPeerNet {
    private val peers = mutableMapOf<String, InMemoryPeer>()
    // Bidirectional links: if (a,b) is in links, a can send to b and vice versa.
    // Empty means full mesh (all can reach all).
    private val links = mutableSetOf<Pair<String, String>>()
    private var useLinks = false

    fun addPeer(peerId: String, displayName: String): InMemoryPeer {
        val incoming = Channel<RawPeerMessage>(Channel.BUFFERED)
        val outgoing = Channel<PeerCommand>(Channel.BUFFERED)
        val rawConnection = RawPeerNetConnection(peerId, incoming, outgoing)
        val peer = InMemoryPeer(peerId, displayName, rawConnection, incoming, outgoing)
        peers[peerId] = peer
        return peer
    }

    /**
     * Declare that two peers can communicate directly.
     * If any links are added, only linked peers can exchange messages.
     * Unlinked peers are isolated from each other (like emulator vs iPhone).
     */
    fun link(peerIdA: String, peerIdB: String) {
        useLinks = true
        links.add(Pair(peerIdA, peerIdB))
        links.add(Pair(peerIdB, peerIdA))
    }

    private fun canReach(from: String, to: String): Boolean {
        if (!useLinks) return true
        return links.contains(Pair(from, to))
    }

    /**
     * Connect all peers to each other by delivering Connected events.
     * Call this after adding all peers.
     */
    suspend fun connectAll() {
        for ((id, peer) in peers) {
            for ((otherId, otherPeer) in peers) {
                if (id != otherId && canReach(id, otherId)) {
                    val peerInfo = PeerInfo(id = otherId, name = otherPeer.displayName, address = "memory", port = 0)
                    peer.incoming.send(RawPeerMessage.Event.Connected(peerInfo))
                }
            }
        }
    }

    /**
     * Start routing outgoing commands from all peers.
     * Returns a job that should be cancelled when the test is done.
     */
    fun startRouting(scope: CoroutineScope): Job {
        return scope.launch {
            peers.values.forEach { peer ->
                launch { routeOutgoing(peer) }
            }
        }
    }

    private suspend fun routeOutgoing(sender: InMemoryPeer) {
        for (command in sender.outgoing) {
            when (command) {
                is PeerCommand.Broadcast -> {
                    val payload = command.payload
                    for ((id, peer) in peers) {
                        if (id != sender.peerId && canReach(sender.peerId, id)) {
                            peer.incoming.send(RawPeerMessage.Received(sender.peerId, payload))
                        }
                    }
                }
                is PeerCommand.SendTo -> {
                    if (canReach(sender.peerId, command.peerId)) {
                        val target = peers[command.peerId]
                        if (target != null) {
                            target.incoming.send(RawPeerMessage.Received(sender.peerId, command.payload))
                        }
                    }
                }
            }
        }
    }

    fun close() {
        peers.values.forEach {
            it.incoming.close()
            it.outgoing.close()
        }
    }
}

class InMemoryPeer(
    val peerId: String,
    val displayName: String,
    val raw: RawPeerNetConnection,
    internal val incoming: Channel<RawPeerMessage>,
    internal val outgoing: Channel<PeerCommand>,
)
