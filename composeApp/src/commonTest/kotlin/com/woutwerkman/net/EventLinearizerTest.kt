package com.woutwerkman.net

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class EventLinearizerTest {

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

    @Test
    fun twoPeersDiscoverEachOther() = runTest {
        val net = InMemoryPeerNet()
        val peerA = net.addPeer("peer-a", "Alice")
        val peerB = net.addPeer("peer-b", "Bob")
        val clock = testClock()

        val channelA = Channel<EventWithTime>(Channel.BUFFERED)
        val channelB = Channel<EventWithTime>(Channel.BUFFERED)
        val stateA = MutableStateFlow(PeerNetState())
        val stateB = MutableStateFlow(PeerNetState())

        val infra = launch {
            net.startRouting(this)
            launch { gossipRouter(peerA.raw, channelA, Channel(Channel.BUFFERED), "Alice", clock) }
            launch { gossipRouter(peerB.raw, channelB, Channel(Channel.BUFFERED), "Bob", clock) }
            launch { withEventLinearizer(channelA, clock) { s -> launch { s.collect { stateA.value = it } }; awaitCancellation() } }
            launch { withEventLinearizer(channelB, clock) { s -> launch { s.collect { stateB.value = it } }; awaitCancellation() } }
        }

        tick()
        net.connectAll()
        tickUntil { stateA.value.discoveredPeers.size >= 2 && stateB.value.discoveredPeers.size >= 2 }

        assertTrue(stateA.value.discoveredPeers.containsKey("peer-a"))
        assertTrue(stateA.value.discoveredPeers.containsKey("peer-b"))
        assertTrue(stateB.value.discoveredPeers.containsKey("peer-a"))
        assertTrue(stateB.value.discoveredPeers.containsKey("peer-b"))

        infra.cancel()
        net.close()
    }

    @Test
    fun threePeersAllConvergeToSameState() = runTest {
        val net = InMemoryPeerNet()
        val peerA = net.addPeer("peer-a", "Alice")
        val peerB = net.addPeer("peer-b", "Bob")
        val peerC = net.addPeer("peer-c", "Charlie")
        val clock = testClock()

        val channelA = Channel<EventWithTime>(Channel.BUFFERED)
        val channelB = Channel<EventWithTime>(Channel.BUFFERED)
        val channelC = Channel<EventWithTime>(Channel.BUFFERED)
        val stateA = MutableStateFlow(PeerNetState())
        val stateB = MutableStateFlow(PeerNetState())
        val stateC = MutableStateFlow(PeerNetState())

        val infra = launch {
            net.startRouting(this)
            launch { gossipRouter(peerA.raw, channelA, Channel(Channel.BUFFERED), "Alice", clock) }
            launch { gossipRouter(peerB.raw, channelB, Channel(Channel.BUFFERED), "Bob", clock) }
            launch { gossipRouter(peerC.raw, channelC, Channel(Channel.BUFFERED), "Charlie", clock) }
            launch { withEventLinearizer(channelA, clock) { s -> launch { s.collect { stateA.value = it } }; awaitCancellation() } }
            launch { withEventLinearizer(channelB, clock) { s -> launch { s.collect { stateB.value = it } }; awaitCancellation() } }
            launch { withEventLinearizer(channelC, clock) { s -> launch { s.collect { stateC.value = it } }; awaitCancellation() } }
        }

        tick()
        net.connectAll()
        tickUntil { stateA.value.discoveredPeers.size >= 3 }

        assertEquals(stateA.value.discoveredPeers.keys, stateB.value.discoveredPeers.keys)
        assertEquals(stateB.value.discoveredPeers.keys, stateC.value.discoveredPeers.keys)
        assertEquals(setOf("peer-a", "peer-b", "peer-c"), stateA.value.discoveredPeers.keys)

        infra.cancel()
        net.close()
    }

    @Test
    fun peerDisconnectIsReflectedInState() = runTest {
        val net = InMemoryPeerNet()
        val peerA = net.addPeer("peer-a", "Alice")
        val peerB = net.addPeer("peer-b", "Bob")
        val clock = testClock()

        val channelA = Channel<EventWithTime>(Channel.BUFFERED)
        val channelB = Channel<EventWithTime>(Channel.BUFFERED)
        val stateA = MutableStateFlow(PeerNetState())

        val infra = launch {
            net.startRouting(this)
            launch { gossipRouter(peerA.raw, channelA, Channel(Channel.BUFFERED), "Alice", clock) }
            launch { gossipRouter(peerB.raw, channelB, Channel(Channel.BUFFERED), "Bob", clock) }
            launch { withEventLinearizer(channelA, clock) { s -> launch { s.collect { stateA.value = it } }; awaitCancellation() } }
            launch { withEventLinearizer(channelB, clock) { s -> awaitCancellation() } }
        }

        tick()
        net.connectAll()
        tickUntil { stateA.value.discoveredPeers.size >= 2 }

        peerA.incoming.send(RawPeerMessage.Event.Disconnected("peer-b"))
        tickUntil { !stateA.value.discoveredPeers.containsKey("peer-b") }

        assertTrue(stateA.value.discoveredPeers.containsKey("peer-a"))
        assertEquals(1, stateA.value.discoveredPeers.size)

        infra.cancel()
        net.close()
    }

    @Test
    fun gossipRelayThroughBridge() = runTest {
        val net = InMemoryPeerNet()
        val peerA = net.addPeer("peer-a", "Emulator")
        val peerB = net.addPeer("peer-b", "JVM-Host")
        val peerC = net.addPeer("peer-c", "iPhone")
        val clock = testClock()

        net.link("peer-a", "peer-b")
        net.link("peer-b", "peer-c")

        val channelA = Channel<EventWithTime>(Channel.BUFFERED)
        val channelB = Channel<EventWithTime>(Channel.BUFFERED)
        val channelC = Channel<EventWithTime>(Channel.BUFFERED)
        val stateA = MutableStateFlow(PeerNetState())
        val stateC = MutableStateFlow(PeerNetState())

        val infra = launch {
            net.startRouting(this)
            launch { gossipRouter(peerA.raw, channelA, Channel(Channel.BUFFERED), "Emulator", clock) }
            launch { gossipRouter(peerB.raw, channelB, Channel(Channel.BUFFERED), "JVM-Host", clock) }
            launch { gossipRouter(peerC.raw, channelC, Channel(Channel.BUFFERED), "iPhone", clock) }
            launch { withEventLinearizer(channelA, clock) { s -> launch { s.collect { stateA.value = it } }; awaitCancellation() } }
            launch { withEventLinearizer(channelB, clock) { s -> awaitCancellation() } }
            launch { withEventLinearizer(channelC, clock) { s -> launch { s.collect { stateC.value = it } }; awaitCancellation() } }
        }

        tick()
        net.connectAll()
        // Bridge topology needs state sync for indirect peers
        tick(2500)
        tickUntil { stateA.value.discoveredPeers.size >= 3 && stateC.value.discoveredPeers.size >= 3 }

        assertEquals(setOf("peer-a", "peer-b", "peer-c"), stateA.value.discoveredPeers.keys)
        assertEquals(setOf("peer-a", "peer-b", "peer-c"), stateC.value.discoveredPeers.keys)

        // Verify display names survive the bridge — not just peer IDs
        assertEquals("iPhone", stateA.value.discoveredPeers["peer-c"]?.name,
            "Indirect peer should have correct display name, not peerId")
        assertEquals("Emulator", stateC.value.discoveredPeers["peer-a"]?.name,
            "Indirect peer should have correct display name, not peerId")

        infra.cancel()
        net.close()
    }
}
