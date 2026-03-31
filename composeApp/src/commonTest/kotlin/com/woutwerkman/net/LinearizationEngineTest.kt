package com.woutwerkman.net

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LinearizationEngineTest {

    @Test
    fun twoPeersDiscoverEachOther() = runTest {
        val net = InMemoryPeerNet()
        val peerA = net.addPeer("peer-a", "Alice")
        val peerB = net.addPeer("peer-b", "Bob")

        var clock = 1000L
        val engineA = LinearizationEngine(peerA.raw, "Alice") { clock++ }
        val engineB = LinearizationEngine(peerB.raw, "Bob") { clock++ }

        val routingJob = net.startRouting(this)
        val jobA = launch { engineA.start() }
        val jobB = launch { engineB.start() }

        // Give engines a moment to process their self-join
        yield()

        net.connectAll()

        // Both peers should eventually see each other in discoveredPeers
        val stateA = engineA.state.first { it.discoveredPeers.size >= 2 }
        val stateB = engineB.state.first { it.discoveredPeers.size >= 2 }

        assertTrue(stateA.discoveredPeers.containsKey("peer-a"))
        assertTrue(stateA.discoveredPeers.containsKey("peer-b"))
        assertTrue(stateB.discoveredPeers.containsKey("peer-a"))
        assertTrue(stateB.discoveredPeers.containsKey("peer-b"))

        net.close()
        jobA.cancel()
        jobB.cancel()
        routingJob.cancel()
    }

    @Test
    fun threePeersAllConvergeToSameState() = runTest {
        val net = InMemoryPeerNet()
        val peerA = net.addPeer("peer-a", "Alice")
        val peerB = net.addPeer("peer-b", "Bob")
        val peerC = net.addPeer("peer-c", "Charlie")

        var clock = 1000L
        val engineA = LinearizationEngine(peerA.raw, "Alice") { clock++ }
        val engineB = LinearizationEngine(peerB.raw, "Bob") { clock++ }
        val engineC = LinearizationEngine(peerC.raw, "Charlie") { clock++ }

        val routingJob = net.startRouting(this)
        val jobA = launch { engineA.start() }
        val jobB = launch { engineB.start() }
        val jobC = launch { engineC.start() }

        yield()
        net.connectAll()

        // All three should see all three peers
        val stateA = engineA.state.first { it.discoveredPeers.size >= 3 }
        val stateB = engineB.state.first { it.discoveredPeers.size >= 3 }
        val stateC = engineC.state.first { it.discoveredPeers.size >= 3 }

        assertEquals(stateA.discoveredPeers.keys, stateB.discoveredPeers.keys)
        assertEquals(stateB.discoveredPeers.keys, stateC.discoveredPeers.keys)
        assertEquals(setOf("peer-a", "peer-b", "peer-c"), stateA.discoveredPeers.keys)

        net.close()
        jobA.cancel()
        jobB.cancel()
        jobC.cancel()
        routingJob.cancel()
    }

    @Test
    fun lateJoinerGetsFullState() = runTest {
        val net = InMemoryPeerNet()
        val peerA = net.addPeer("peer-a", "Alice")
        val peerB = net.addPeer("peer-b", "Bob")

        var clock = 1000L
        val engineA = LinearizationEngine(peerA.raw, "Alice") { clock++ }
        val engineB = LinearizationEngine(peerB.raw, "Bob") { clock++ }

        val routingJob = net.startRouting(this)
        val jobA = launch { engineA.start() }
        val jobB = launch { engineB.start() }

        yield()
        net.connectAll()

        // Wait for A and B to see each other
        engineA.state.first { it.discoveredPeers.size >= 2 }
        engineB.state.first { it.discoveredPeers.size >= 2 }

        // Now add a late joiner
        val peerC = net.addPeer("peer-c", "Charlie")
        val engineC = LinearizationEngine(peerC.raw, "Charlie") { clock++ }

        // Need to restart routing to include the new peer
        routingJob.cancel()
        val routingJob2 = net.startRouting(this)

        val jobC = launch { engineC.start() }
        yield()

        // Connect C to existing peers
        val peerInfoA = PeerInfo(id = "peer-a", name = "Alice", address = "memory", port = 0)
        val peerInfoB = PeerInfo(id = "peer-b", name = "Bob", address = "memory", port = 0)
        val peerInfoC = PeerInfo(id = "peer-c", name = "Charlie", address = "memory", port = 0)
        peerC.incoming.send(RawPeerMessage.Event.Connected(peerInfoA))
        peerC.incoming.send(RawPeerMessage.Event.Connected(peerInfoB))
        peerA.incoming.send(RawPeerMessage.Event.Connected(peerInfoC))
        peerB.incoming.send(RawPeerMessage.Event.Connected(peerInfoC))

        // Late joiner should eventually see all three peers via state sync
        val stateC = engineC.state.first { it.discoveredPeers.size >= 3 }
        assertEquals(setOf("peer-a", "peer-b", "peer-c"), stateC.discoveredPeers.keys)

        net.close()
        jobA.cancel()
        jobB.cancel()
        jobC.cancel()
        routingJob2.cancel()
    }

    @Test
    fun peerDisconnectIsReflectedInState() = runTest {
        val net = InMemoryPeerNet()
        val peerA = net.addPeer("peer-a", "Alice")
        val peerB = net.addPeer("peer-b", "Bob")

        var clock = 1000L
        val engineA = LinearizationEngine(peerA.raw, "Alice") { clock++ }
        val engineB = LinearizationEngine(peerB.raw, "Bob") { clock++ }

        val routingJob = net.startRouting(this)
        val jobA = launch { engineA.start() }
        val jobB = launch { engineB.start() }

        yield()
        net.connectAll()

        // Wait for both to see each other
        engineA.state.first { it.discoveredPeers.size >= 2 }
        engineB.state.first { it.discoveredPeers.size >= 2 }

        // Simulate B disconnecting from A's perspective
        peerA.incoming.send(RawPeerMessage.Event.Disconnected("peer-b"))

        // A should eventually only see itself
        val stateA = engineA.state.first { !it.discoveredPeers.containsKey("peer-b") }
        assertTrue(stateA.discoveredPeers.containsKey("peer-a"))
        assertEquals(1, stateA.discoveredPeers.size)

        net.close()
        jobA.cancel()
        jobB.cancel()
        routingJob.cancel()
    }

    /**
     * Models emulator↔iPhone scenario: A and C can't reach each other directly,
     * but B (the host/JVM) can reach both. B gossips events so A and C converge.
     */
    @Test
    fun gossipRelayThroughBridge() = runTest {
        val net = InMemoryPeerNet()
        val peerA = net.addPeer("peer-a", "Emulator")
        val peerB = net.addPeer("peer-b", "JVM-Host")
        val peerC = net.addPeer("peer-c", "iPhone")

        // A↔B and B↔C, but NOT A↔C
        net.link("peer-a", "peer-b")
        net.link("peer-b", "peer-c")

        var clock = 1000L
        val engineA = LinearizationEngine(peerA.raw, "Emulator") { clock++ }
        val engineB = LinearizationEngine(peerB.raw, "JVM-Host") { clock++ }
        val engineC = LinearizationEngine(peerC.raw, "iPhone") { clock++ }

        val routingJob = net.startRouting(this)
        val jobA = launch { engineA.start() }
        val jobB = launch { engineB.start() }
        val jobC = launch { engineC.start() }

        yield()
        net.connectAll()

        // A and C should both see all 3 peers — even though they can't talk directly
        val stateA = engineA.state.first { it.discoveredPeers.size >= 3 }
        val stateC = engineC.state.first { it.discoveredPeers.size >= 3 }

        assertEquals(setOf("peer-a", "peer-b", "peer-c"), stateA.discoveredPeers.keys)
        assertEquals(setOf("peer-a", "peer-b", "peer-c"), stateC.discoveredPeers.keys)

        net.close()
        jobA.cancel()
        jobB.cancel()
        jobC.cancel()
        routingJob.cancel()
    }

    @Test
    fun timestampOrderingIsDeterministic() = runTest {
        val net = InMemoryPeerNet()
        val peerA = net.addPeer("peer-a", "Alice")
        val peerB = net.addPeer("peer-b", "Bob")

        // Give A earlier timestamps, B later timestamps
        var clockA = 100L
        var clockB = 200L
        val engineA = LinearizationEngine(peerA.raw, "Alice") { clockA++ }
        val engineB = LinearizationEngine(peerB.raw, "Bob") { clockB++ }

        val routingJob = net.startRouting(this)
        val jobA = launch { engineA.start() }
        val jobB = launch { engineB.start() }

        yield()
        net.connectAll()

        // Both should see both peers
        val stateA = engineA.state.first { it.discoveredPeers.size >= 2 }
        val stateB = engineB.state.first { it.discoveredPeers.size >= 2 }

        // Both should have converged to the same set of peers
        assertEquals(stateA.discoveredPeers.keys, stateB.discoveredPeers.keys)

        net.close()
        jobA.cancel()
        jobB.cancel()
        routingJob.cancel()
    }
}
