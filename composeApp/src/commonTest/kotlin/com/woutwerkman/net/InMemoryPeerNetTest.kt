package com.woutwerkman.net

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies InMemoryPeerNet faithfully simulates the production transport layer.
 *
 * Production transport (JVM/iOS/Android) provides:
 * - SendTo: delivers to a specific peer if reachable, silently drops otherwise
 * - Broadcast: delivers to all known peers (not self)
 * - Connected events on handshake completion (with correct PeerInfo)
 * - Disconnected events on link loss
 *
 * These tests ensure the test double matches this contract, so higher-level
 * tests (gossip, linearizer, game) can trust their foundation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InMemoryPeerNetTest {

    @Test
    fun sendToDeliversToCorrectPeer() = runTest {
        val net = InMemoryPeerNet()
        val peerA = net.addPeer("A", "Alice")
        val peerB = net.addPeer("B", "Bob")
        val peerC = net.addPeer("C", "Charlie")

        val router = net.startRouting(this)
        peerA.outgoing.send(PeerCommand.SendTo("B", "hello".encodeToByteArray()))
        advanceUntilIdle()

        val msg = peerB.incoming.receive() as RawPeerMessage.Received
        assertEquals("A", msg.fromPeerId)
        assertEquals("hello", msg.payload.decodeToString())
        assertTrue(peerC.incoming.tryReceive().isFailure, "C should not receive A→B message")

        router.cancel()
        net.close()
    }

    @Test
    fun sendToUnreachablePeerIsDropped() = runTest {
        val net = InMemoryPeerNet()
        val peerA = net.addPeer("A", "Alice")
        net.addPeer("B", "Bob")
        val peerC = net.addPeer("C", "Charlie")
        net.link("A", "B") // A↔B only, no A↔C

        val router = net.startRouting(this)
        peerA.outgoing.send(PeerCommand.SendTo("C", "hello".encodeToByteArray()))
        advanceUntilIdle()

        assertTrue(peerC.incoming.tryReceive().isFailure, "Unreachable peer should not receive message")

        router.cancel()
        net.close()
    }

    @Test
    fun broadcastDeliversToAllReachableExceptSelf() = runTest {
        val net = InMemoryPeerNet()
        val peerA = net.addPeer("A", "Alice")
        val peerB = net.addPeer("B", "Bob")
        val peerC = net.addPeer("C", "Charlie")

        val router = net.startRouting(this)
        peerA.outgoing.send(PeerCommand.Broadcast("hello".encodeToByteArray()))
        advanceUntilIdle()

        val msgB = peerB.incoming.receive() as RawPeerMessage.Received
        val msgC = peerC.incoming.receive() as RawPeerMessage.Received
        assertEquals("hello", msgB.payload.decodeToString())
        assertEquals("hello", msgC.payload.decodeToString())
        assertTrue(peerA.incoming.tryReceive().isFailure, "Sender should not receive own broadcast")

        router.cancel()
        net.close()
    }

    @Test
    fun broadcastRespectsLinkTopology() = runTest {
        val net = InMemoryPeerNet()
        val peerA = net.addPeer("A", "Alice")
        val peerB = net.addPeer("B", "Bob")
        val peerC = net.addPeer("C", "Charlie")
        net.link("A", "B")
        net.link("B", "C")

        val router = net.startRouting(this)
        peerA.outgoing.send(PeerCommand.Broadcast("hello".encodeToByteArray()))
        advanceUntilIdle()

        val msgB = peerB.incoming.receive() as RawPeerMessage.Received
        assertEquals("hello", msgB.payload.decodeToString())
        assertTrue(peerC.incoming.tryReceive().isFailure, "C should not get A's broadcast (no direct link)")

        router.cancel()
        net.close()
    }

    @Test
    fun connectAllDeliversCorrectPeerInfo() = runTest {
        val net = InMemoryPeerNet()
        val peerA = net.addPeer("A", "Alice")
        val peerB = net.addPeer("B", "Bob")

        net.connectAll()

        val msgA = peerA.incoming.receive() as RawPeerMessage.Event.Connected
        assertEquals("B", msgA.peer.id)
        assertEquals("Bob", msgA.peer.name)

        val msgB = peerB.incoming.receive() as RawPeerMessage.Event.Connected
        assertEquals("A", msgB.peer.id)
        assertEquals("Alice", msgB.peer.name)

        net.close()
    }

    @Test
    fun connectAllRespectsLinkTopology() = runTest {
        val net = InMemoryPeerNet()
        val peerA = net.addPeer("A", "Alice")
        val peerB = net.addPeer("B", "Bob")
        val peerC = net.addPeer("C", "Charlie")
        net.link("A", "B")
        net.link("B", "C")

        net.connectAll()

        // A gets only Connected(B)
        val msgA = peerA.incoming.receive() as RawPeerMessage.Event.Connected
        assertEquals("B", msgA.peer.id)
        assertTrue(peerA.incoming.tryReceive().isFailure, "A should not see C (no direct link)")

        // B gets Connected(A) and Connected(C)
        val b1 = peerB.incoming.receive() as RawPeerMessage.Event.Connected
        val b2 = peerB.incoming.receive() as RawPeerMessage.Event.Connected
        assertEquals(setOf("A", "C"), setOf(b1.peer.id, b2.peer.id))

        // C gets only Connected(B)
        val msgC = peerC.incoming.receive() as RawPeerMessage.Event.Connected
        assertEquals("B", msgC.peer.id)
        assertTrue(peerC.incoming.tryReceive().isFailure, "C should not see A (no direct link)")

        net.close()
    }

    @Test
    fun unlinkDeliversDisconnectedToBothSides() = runTest {
        val net = InMemoryPeerNet()
        val peerA = net.addPeer("A", "Alice")
        val peerB = net.addPeer("B", "Bob")
        net.link("A", "B")
        net.connectAll()

        // Drain Connected events
        peerA.incoming.receive()
        peerB.incoming.receive()

        net.unlink("A", "B")

        val disconnA = peerA.incoming.receive() as RawPeerMessage.Event.Disconnected
        assertEquals("B", disconnA.peerId)
        val disconnB = peerB.incoming.receive() as RawPeerMessage.Event.Disconnected
        assertEquals("A", disconnB.peerId)

        net.close()
    }

    @Test
    fun sendToAfterUnlinkIsDropped() = runTest {
        val net = InMemoryPeerNet()
        val peerA = net.addPeer("A", "Alice")
        val peerB = net.addPeer("B", "Bob")
        net.link("A", "B")
        net.connectAll()

        // Drain Connected events
        peerA.incoming.receive()
        peerB.incoming.receive()

        val router = net.startRouting(this)

        // Verify message works before unlink
        peerA.outgoing.send(PeerCommand.SendTo("B", "before".encodeToByteArray()))
        advanceUntilIdle()
        val msg = peerB.incoming.receive() as RawPeerMessage.Received
        assertEquals("before", msg.payload.decodeToString())

        // Unlink and drain Disconnected
        net.unlink("A", "B")
        peerA.incoming.receive() // Disconnected
        peerB.incoming.receive() // Disconnected

        // Message after unlink should be dropped
        peerA.outgoing.send(PeerCommand.SendTo("B", "after".encodeToByteArray()))
        advanceUntilIdle()
        assertTrue(peerB.incoming.tryReceive().isFailure, "Message after unlink should be dropped")

        router.cancel()
        net.close()
    }

    /**
     * Production: gossip router uses SendTo exclusively (never Broadcast).
     * Verify SendTo from one routing peer reaches another through InMemoryPeerNet.
     */
    @Test
    fun sendToAfterConnectAllWorks() = runTest {
        val net = InMemoryPeerNet()
        val peerA = net.addPeer("peer-a", "Alice")
        val peerB = net.addPeer("peer-b", "Bob")

        net.connectAll()
        // Drain Connected events
        peerA.incoming.receive()
        peerB.incoming.receive()

        val router = net.startRouting(this)

        peerA.outgoing.send(PeerCommand.SendTo("peer-b", "data".encodeToByteArray()))
        advanceUntilIdle()

        val msg = peerB.incoming.receive() as RawPeerMessage.Received
        assertEquals("peer-a", msg.fromPeerId)
        assertEquals("data", msg.payload.decodeToString())

        router.cancel()
        net.close()
    }
}
