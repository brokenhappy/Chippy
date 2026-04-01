package com.woutwerkman.net

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectivityRoutingTest {

    private fun TestScope.testClock(): Clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(testScheduler.currentTime)
    }

    private fun TestScope.tick(ms: Long = 1) {
        advanceTimeBy(ms)
        testScheduler.runCurrent()
    }

    private fun TestScope.tickUntil(maxTicks: Int = 100, predicate: () -> Boolean) {
        repeat(maxTicks) {
            if (predicate()) return
            tick()
        }
        assertTrue(predicate(), "Condition not met after $maxTicks ticks")
    }

    /** Helper to set up N peers with event linearizers and return states. */
    private data class PeerSetup(
        val net: InMemoryPeerNet,
        val peers: Map<String, InMemoryPeer>,
        val channels: Map<String, Channel<EventWithTime>>,
        val localChannels: Map<String, Channel<EventWithTime>>,
        val states: Map<String, MutableStateFlow<PeerNetState>>,
    )

    private fun createPeers(vararg nameById: Pair<String, String>): PeerSetup {
        val net = InMemoryPeerNet()
        val peers = mutableMapOf<String, InMemoryPeer>()
        val channels = mutableMapOf<String, Channel<EventWithTime>>()
        val localChannels = mutableMapOf<String, Channel<EventWithTime>>()
        val states = mutableMapOf<String, MutableStateFlow<PeerNetState>>()
        for ((id, name) in nameById) {
            peers[id] = net.addPeer(id, name)
            channels[id] = Channel(Channel.BUFFERED)
            localChannels[id] = Channel(Channel.BUFFERED)
            states[id] = MutableStateFlow(PeerNetState())
        }
        return PeerSetup(net, peers, channels, localChannels, states)
    }

    private fun CoroutineScope.launchInfra(
        setup: PeerSetup,
        clock: Clock,
    ): Job = launch {
        setup.net.startRouting(this)
        for ((id, peer) in setup.peers) {
            val ch = setup.channels[id]!!
            val st = setup.states[id]!!
            val localEv = setup.localChannels[id]!!
            launch { gossipRouter(peer.raw, ch, localEv, peer.displayName, clock) }
            launch { withEventLinearizer(ch, clock) { s -> launch { s.collect { st.value = it } }; awaitCancellation() } }
        }
    }

    @Test
    fun indirectPeerDiscoveryViaReachability() = runTest {
        // A ↔ B ↔ C (A and C can't reach each other directly)
        val setup = createPeers("A" to "Alice", "B" to "Bob", "C" to "Charlie")
        val clock = testClock()
        setup.net.link("A", "B")
        setup.net.link("B", "C")

        val infra = launchInfra(setup, clock)
        tick()
        setup.net.connectAll()
        // Wait for reachability announcements to propagate
        tick(2500)
        tickUntil { setup.states["A"]!!.value.discoveredPeers.size >= 3 }

        assertEquals(setOf("A", "B", "C"), setup.states["A"]!!.value.discoveredPeers.keys)
        assertEquals(setOf("A", "B", "C"), setup.states["C"]!!.value.discoveredPeers.keys)

        infra.cancel()
        setup.net.close()
    }

    @Test
    fun mediumDropoutEmitsLeftForUnreachablePeers() = runTest {
        // A ↔ B ↔ C — then B disconnects from both
        val setup = createPeers("A" to "Alice", "B" to "Bob", "C" to "Charlie")
        val clock = testClock()
        setup.net.link("A", "B")
        setup.net.link("B", "C")

        val infra = launchInfra(setup, clock)
        tick()
        setup.net.connectAll()
        tick(2500)
        tickUntil { setup.states["A"]!!.value.discoveredPeers.size >= 3 }

        // Now remove B ↔ C link — C becomes unreachable from A
        setup.net.unlink("B", "C")
        tick(2500)
        tickUntil { !setup.states["A"]!!.value.discoveredPeers.containsKey("C") }

        assertTrue(setup.states["A"]!!.value.discoveredPeers.containsKey("A"))
        assertTrue(setup.states["A"]!!.value.discoveredPeers.containsKey("B"))
        assertFalse(setup.states["A"]!!.value.discoveredPeers.containsKey("C"))

        infra.cancel()
        setup.net.close()
    }

    @Test
    fun routedMessageReachesTargetThroughMedium() = runTest {
        // A ↔ B ↔ C, game events from A should reach C via B
        val setup = createPeers("A" to "Alice", "B" to "Bob", "C" to "Charlie")
        val clock = testClock()
        setup.net.link("A", "B")
        setup.net.link("B", "C")

        val infra = launchInfra(setup, clock)
        tick()
        setup.net.connectAll()
        tick(2500)
        tickUntil { setup.states["A"]!!.value.discoveredPeers.size >= 3 }

        // C joins A's lobby — event must reach A through B
        val ewt = EventWithTime(clock.now(), "C", PeerEvent.JoinedLobby("A", "C"))
        setup.channels["C"]!!.send(ewt)
        setup.localChannels["C"]!!.send(ewt)
        tick(2500)
        tickUntil {
            setup.states["A"]!!.value.lobbies["A"]?.players?.containsKey("C") == true
        }

        assertTrue(setup.states["A"]!!.value.lobbies["A"]!!.players.containsKey("C"))

        infra.cancel()
        setup.net.close()
    }

    @Test
    fun multiHopRoutingWithFourPeers() = runTest {
        // A ↔ B ↔ C ↔ D (chain topology)
        val setup = createPeers("A" to "Alice", "B" to "Bob", "C" to "Charlie", "D" to "Diana")
        val clock = testClock()
        setup.net.link("A", "B")
        setup.net.link("B", "C")
        setup.net.link("C", "D")

        val infra = launchInfra(setup, clock)
        tick()
        setup.net.connectAll()
        // Multi-hop needs multiple sync cycles for reachability to propagate
        tick(5000)
        tickUntil(maxTicks = 200) { setup.states["A"]!!.value.discoveredPeers.size >= 4 }

        assertEquals(setOf("A", "B", "C", "D"), setup.states["A"]!!.value.discoveredPeers.keys)
        assertEquals(setOf("A", "B", "C", "D"), setup.states["D"]!!.value.discoveredPeers.keys)

        infra.cancel()
        setup.net.close()
    }

    @Test
    fun subnetIsolationBothSidesAgreeOnLeft() = runTest {
        // A ↔ B ↔ C — remove B entirely, A and C become isolated
        val setup = createPeers("A" to "Alice", "B" to "Bob", "C" to "Charlie")
        val clock = testClock()
        setup.net.link("A", "B")
        setup.net.link("B", "C")

        val infra = launchInfra(setup, clock)
        tick()
        setup.net.connectAll()
        tick(2500)
        tickUntil { setup.states["A"]!!.value.discoveredPeers.size >= 3 }

        // Disconnect B from both A and C
        setup.net.unlink("A", "B")
        setup.net.unlink("B", "C")
        tick(2500)

        // A should only see itself
        tickUntil { setup.states["A"]!!.value.discoveredPeers.size == 1 }
        assertEquals(setOf("A"), setup.states["A"]!!.value.discoveredPeers.keys)

        // C should only see itself
        tickUntil { setup.states["C"]!!.value.discoveredPeers.size == 1 }
        assertEquals(setOf("C"), setup.states["C"]!!.value.discoveredPeers.keys)

        infra.cancel()
        setup.net.close()
    }

    @Test
    fun mediumReElectionWhenBetterPathAvailable() = runTest {
        // Initially: A ↔ B ↔ C
        // Then add: A ↔ C (direct link)
        // After direct link, A should see C directly
        val setup = createPeers("A" to "Alice", "B" to "Bob", "C" to "Charlie")
        val clock = testClock()
        setup.net.link("A", "B")
        setup.net.link("B", "C")

        val infra = launchInfra(setup, clock)
        tick()
        setup.net.connectAll()
        tick(2500)
        tickUntil { setup.states["A"]!!.value.discoveredPeers.size >= 3 }

        // Add direct A ↔ C link
        setup.net.link("A", "C")
        val peerInfoC = PeerInfo(id = "C", name = "Charlie", address = "memory", port = 0)
        setup.peers["A"]!!.incoming.send(RawPeerMessage.Event.Connected(peerInfoC))
        val peerInfoA = PeerInfo(id = "A", name = "Alice", address = "memory", port = 0)
        setup.peers["C"]!!.incoming.send(RawPeerMessage.Event.Connected(peerInfoA))
        tick(2500)

        // All three should still see each other
        assertEquals(setOf("A", "B", "C"), setup.states["A"]!!.value.discoveredPeers.keys)
        assertEquals(setOf("A", "B", "C"), setup.states["C"]!!.value.discoveredPeers.keys)

        // Now remove B — A and C should still see each other through direct link
        setup.net.unlink("A", "B")
        setup.net.unlink("B", "C")
        tick(2500)

        tickUntil { !setup.states["A"]!!.value.discoveredPeers.containsKey("B") }
        assertTrue(setup.states["A"]!!.value.discoveredPeers.containsKey("C"))
        assertTrue(setup.states["C"]!!.value.discoveredPeers.containsKey("A"))

        infra.cancel()
        setup.net.close()
    }
}
