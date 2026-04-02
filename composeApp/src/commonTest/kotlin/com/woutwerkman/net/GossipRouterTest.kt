package com.woutwerkman.net

import com.woutwerkman.game.model.GamePhase
import com.woutwerkman.game.model.VoteChoice
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Tests for the gossip router layer.
 *
 * The gossip router bridges raw transport (Connected/Disconnected/Received)
 * and the event linearizer (EventWithTime channel). These tests verify:
 * - Self-join emitted at startup
 * - Connected/Disconnected correctly converted to Joined/Left
 * - Local events (from submitEvent) broadcast to peers and included in state sync
 * - Event serialization round-trips correctly over the wire
 * - Late joiners catch up via periodic state sync
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GossipRouterTest {

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

    // -------------------------------------------------------------------
    // Basic gossip router behavior (raw channels, no InMemoryPeerNet)
    // -------------------------------------------------------------------

    @Test
    fun selfJoinEmittedOnStart() = runTest {
        val incoming = Channel<RawPeerMessage>(Channel.UNLIMITED)
        val outgoing = Channel<PeerCommand>(Channel.UNLIMITED)
        val raw = RawPeerNetConnection("peer-a", incoming, outgoing)
        val eventChannel = Channel<EventWithTime>(Channel.BUFFERED)
        val localEvents = Channel<EventWithTime>(Channel.BUFFERED)
        val clock = testClock()

        val job = launch { gossipRouter(raw, eventChannel, localEvents, "Alice", clock) }
        tick()

        val event = eventChannel.receive()
        assertEquals("peer-a", event.peerId)
        val joined = event.event as PeerEvent.Joined
        assertEquals("peer-a", joined.peer.id)
        assertEquals("Alice", joined.peer.name)

        job.cancel()
    }

    @Test
    fun connectedEmitsJoinedWithCorrectPeerInfo() = runTest {
        val incoming = Channel<RawPeerMessage>(Channel.UNLIMITED)
        val outgoing = Channel<PeerCommand>(Channel.UNLIMITED)
        val raw = RawPeerNetConnection("peer-a", incoming, outgoing)
        val eventChannel = Channel<EventWithTime>(Channel.BUFFERED)
        val localEvents = Channel<EventWithTime>(Channel.BUFFERED)
        val clock = testClock()

        val job = launch { gossipRouter(raw, eventChannel, localEvents, "Alice", clock) }
        tick()
        eventChannel.receive() // self-join

        incoming.send(RawPeerMessage.Event.Connected(PeerInfo("peer-b", "Bob", "192.168.1.2", 47391)))
        tick()

        val event = eventChannel.receive()
        assertEquals("peer-a", event.peerId)
        val joined = event.event as PeerEvent.Joined
        assertEquals("peer-b", joined.peer.id)
        assertEquals("Bob", joined.peer.name)

        job.cancel()
    }

    @Test
    fun disconnectedEmitsLeft() = runTest {
        val incoming = Channel<RawPeerMessage>(Channel.UNLIMITED)
        val outgoing = Channel<PeerCommand>(Channel.UNLIMITED)
        val raw = RawPeerNetConnection("peer-a", incoming, outgoing)
        val eventChannel = Channel<EventWithTime>(Channel.BUFFERED)
        val localEvents = Channel<EventWithTime>(Channel.BUFFERED)
        val clock = testClock()

        val job = launch { gossipRouter(raw, eventChannel, localEvents, "Alice", clock) }
        tick()
        eventChannel.receive() // self-join

        incoming.send(RawPeerMessage.Event.Connected(PeerInfo("peer-b", "Bob", "memory", 0)))
        tick()
        eventChannel.receive() // Joined(B)

        incoming.send(RawPeerMessage.Event.Disconnected("peer-b"))
        tick()

        val event = eventChannel.receive()
        val left = event.event as PeerEvent.Left
        assertEquals("peer-b", left.peerId)

        job.cancel()
    }

    // -------------------------------------------------------------------
    // Serialization — gossip breaks silently if these fail
    // -------------------------------------------------------------------

    @Test
    fun eventSerializationRoundTrip() {
        val events = listOf(
            PeerEvent.Joined(PeerInfo("p1", "Alice", "1.2.3.4", 1234)),
            PeerEvent.Left("p1"),
            PeerEvent.JoinedLobby("lobby", "player"),
            PeerEvent.LeftLobby("lobby", "player"),
            PeerEvent.ReadyChanged("lobby", "player", true),
            PeerEvent.ButtonPress("lobby", "src", "tgt", -2),
            PeerEvent.VoteCast("lobby", "player", VoteChoice.PLAY_AGAIN),
            PeerEvent.GameStarted("lobby", mapOf("p1" to 5, "p2" to 3)),
            PeerEvent.PhaseChanged("lobby", GamePhase.PLAYING),
            PeerEvent.CountdownTick("lobby", 2),
        )
        for (event in events) {
            val ewt = EventWithTime(Instant.fromEpochMilliseconds(1000), "peer", event)
            val json = linJson.encodeToString(EventWithTime.serializer(), ewt)
            val decoded = linJson.decodeFromString(EventWithTime.serializer(), json)
            assertEquals(ewt, decoded, "Round-trip failed for ${event::class.simpleName}")
        }
    }

    @Test
    fun instantSerializationPreservesMillisecondPrecision() {
        val instants = listOf(
            Instant.fromEpochMilliseconds(0),
            Instant.fromEpochMilliseconds(1),
            Instant.fromEpochMilliseconds(1719849600000), // a real timestamp
            Instant.fromEpochMilliseconds(Long.MAX_VALUE / 2),
        )
        for (instant in instants) {
            val ewt = EventWithTime(instant, "peer", PeerEvent.Left("x"))
            val json = linJson.encodeToString(EventWithTime.serializer(), ewt)
            val decoded = linJson.decodeFromString(EventWithTime.serializer(), json)
            assertEquals(instant, decoded.time, "Instant round-trip failed for $instant")
        }
    }

    // -------------------------------------------------------------------
    // Integration: localEvents → gossip broadcast → remote peer receives
    // -------------------------------------------------------------------

    /**
     * This test verifies the exact flow of withPeerNetConnection.submitEvent:
     * submitEvent sends to eventChannel AND localEvents.
     * The gossip router reads localEvents, adds to eventLog, broadcasts.
     * The remote peer's gossip router receives, feeds to its linearizer.
     *
     * This catches the lobby-join-not-propagating bug that was recently fixed.
     */
    @Test
    fun localEventPropagatedToRemotePeer() = runTest {
        val net = InMemoryPeerNet()
        val peerA = net.addPeer("A", "Alice")
        val peerB = net.addPeer("B", "Bob")
        val clock = testClock()

        val channelA = Channel<EventWithTime>(Channel.BUFFERED)
        val localA = Channel<EventWithTime>(Channel.BUFFERED)
        val channelB = Channel<EventWithTime>(Channel.BUFFERED)
        val localB = Channel<EventWithTime>(Channel.BUFFERED)
        val stateA = MutableStateFlow(PeerNetState())
        val stateB = MutableStateFlow(PeerNetState())

        val infra = launch {
            launch { net.runRouting() }
            launch { gossipRouter(peerA.raw, channelA, localA, "Alice", clock) }
            launch { gossipRouter(peerB.raw, channelB, localB, "Bob", clock) }
            launch { withEventLinearizer(channelA, clock) { s -> launch { s.collect { stateA.value = it } }; awaitCancellation() } }
            launch { withEventLinearizer(channelB, clock) { s -> launch { s.collect { stateB.value = it } }; awaitCancellation() } }
        }

        tick()
        net.connectAll()
        tickUntil { stateA.value.discoveredPeers.size >= 2 && stateB.value.discoveredPeers.size >= 2 }

        // Replicate submitEvent: send to both eventChannel and localEvents
        suspend fun submit(peerId: String, event: PeerEvent) {
            val ewt = EventWithTime(clock.now(), peerId, event)
            val ch = if (peerId == "A") channelA else channelB
            val local = if (peerId == "A") localA else localB
            ch.send(ewt)
            local.send(ewt)
        }

        // B joins A's lobby — this must reach A
        submit("B", PeerEvent.JoinedLobby("A", "B"))
        tickUntil { stateA.value.lobbies["A"]?.players?.containsKey("B") == true }

        // Both peers should agree on lobby state
        assertEquals(
            stateA.value.lobbies["A"]?.players?.keys,
            stateB.value.lobbies["A"]?.players?.keys,
        )

        infra.cancel()
        net.close()
    }

    /**
     * Verify that locally submitted events are included in periodic state sync.
     * A submits a local event. After state sync period (2s), B receives it
     * even if the initial gossip broadcast was somehow missed.
     */
    @Test
    fun localEventIncludedInStateSync() = runTest {
        val net = InMemoryPeerNet()
        val peerA = net.addPeer("A", "Alice")
        val peerB = net.addPeer("B", "Bob")
        val clock = testClock()

        val channelA = Channel<EventWithTime>(Channel.BUFFERED)
        val localA = Channel<EventWithTime>(Channel.BUFFERED)
        val channelB = Channel<EventWithTime>(Channel.BUFFERED)
        val localB = Channel<EventWithTime>(Channel.BUFFERED)
        val stateB = MutableStateFlow(PeerNetState())

        val infra = launch {
            launch { net.runRouting() }
            launch { gossipRouter(peerA.raw, channelA, localA, "Alice", clock) }
            launch { gossipRouter(peerB.raw, channelB, localB, "Bob", clock) }
            launch { withEventLinearizer(channelB, clock) { s -> launch { s.collect { stateB.value = it } }; awaitCancellation() } }
        }

        tick()
        net.connectAll()
        tickUntil { stateB.value.discoveredPeers.size >= 2 }

        // Submit via localEvents only (gossip router adds to eventLog + broadcasts)
        val ewt = EventWithTime(clock.now(), "A", PeerEvent.JoinedLobby("A", "B"))
        channelA.send(ewt)
        localA.send(ewt)
        tick()

        // Wait for state sync period (gossip router syncs every 2s)
        tick(2500)
        tickUntil { stateB.value.lobbies["A"]?.players?.containsKey("B") == true }

        infra.cancel()
        net.close()
    }

    // -------------------------------------------------------------------
    // Late joiner catches up via state sync
    // -------------------------------------------------------------------

    @Test
    fun lateJoinerCatchesUpViaStateSync() = runTest {
        val net = InMemoryPeerNet()
        val peerA = net.addPeer("A", "Alice")
        val peerB = net.addPeer("B", "Bob")
        val clock = testClock()

        val channelA = Channel<EventWithTime>(Channel.BUFFERED)
        val localA = Channel<EventWithTime>(Channel.BUFFERED)
        val channelB = Channel<EventWithTime>(Channel.BUFFERED)
        val localB = Channel<EventWithTime>(Channel.BUFFERED)
        val stateA = MutableStateFlow(PeerNetState())
        val stateB = MutableStateFlow(PeerNetState())

        val infra = launch {
            launch { net.runRouting() }
            launch { gossipRouter(peerA.raw, channelA, localA, "Alice", clock) }
            launch { withEventLinearizer(channelA, clock) { s -> launch { s.collect { stateA.value = it } }; awaitCancellation() } }
        }

        tick()
        tickUntil { stateA.value.discoveredPeers.containsKey("A") }

        // Now B joins the network (late joiner) — launched inside infra so it gets cancelled
        val lateJoiner = launch {
            launch { gossipRouter(peerB.raw, channelB, localB, "Bob", clock) }
            launch { withEventLinearizer(channelB, clock) { s -> launch { s.collect { stateB.value = it } }; awaitCancellation() } }
        }

        // Connect B to the network
        val peerInfoB = PeerInfo("B", "Bob", "memory", 0)
        val peerInfoA = PeerInfo("A", "Alice", "memory", 0)
        peerA.incoming.send(RawPeerMessage.Event.Connected(peerInfoB))
        peerB.incoming.send(RawPeerMessage.Event.Connected(peerInfoA))
        tick()

        // Wait for state sync to propagate
        tick(2500)
        tickUntil { stateB.value.discoveredPeers.size >= 2 }

        assertEquals(setOf("A", "B"), stateB.value.discoveredPeers.keys)
        assertEquals(setOf("A", "B"), stateA.value.discoveredPeers.keys)

        lateJoiner.cancel()
        infra.cancel()
        net.close()
    }

    // -------------------------------------------------------------------
    // Event ordering: concurrent events deterministically ordered
    // -------------------------------------------------------------------

    @Test
    fun concurrentEventsFromDifferentPeersOrderedConsistently() {
        // Two events at the exact same time from different peers
        val ewtA = EventWithTime(
            Instant.fromEpochMilliseconds(1000), "peer-a",
            PeerEvent.ReadyChanged("lobby", "peer-a", true),
        )
        val ewtB = EventWithTime(
            Instant.fromEpochMilliseconds(1000), "peer-b",
            PeerEvent.ReadyChanged("lobby", "peer-b", true),
        )

        // Both orderings should produce the same result
        val logAFirst = listOf(ewtA, ewtB).sortedWith(eventComparator)
        val logBFirst = listOf(ewtB, ewtA).sortedWith(eventComparator)

        assertEquals(logAFirst, logBFirst, "Event ordering should be deterministic regardless of insertion order")

        // Replay both orderings — same state
        val (stateAFirst, _) = logAFirst.replayAndSchedule(Instant.fromEpochMilliseconds(2000))
        val (stateBFirst, _) = logBFirst.replayAndSchedule(Instant.fromEpochMilliseconds(2000))
        assertEquals(stateAFirst, stateBFirst, "Same events in any order should produce identical state")
    }

    @Test
    fun fnv1aProducesDifferentHashesForDifferentInputs() {
        val hash1 = fnv1a("peer-a1000")
        val hash2 = fnv1a("peer-b1000")
        assertTrue(hash1 != hash2, "fnv1a should produce different hashes for different inputs")
    }

    /**
     * When an event with an earlier timestamp arrives after events with later timestamps,
     * the linearizer re-folds from scratch. The final state must be identical
     * to folding all events in the correct order from the start.
     */
    @Test
    fun lateEventCausesCorrectRefold() = runTest {
        val clock = testClock()
        val channel = Channel<EventWithTime>(Channel.BUFFERED)
        val state = MutableStateFlow(PeerNetState())

        val job = launch {
            withEventLinearizer(channel, clock) { s ->
                launch { s.collect { state.value = it } }
                awaitCancellation()
            }
        }
        tick()

        // Event at T=100 arrives first
        channel.send(EventWithTime(
            Instant.fromEpochMilliseconds(100), "A",
            PeerEvent.Joined(PeerInfo("A", "Alice", "", 0))
        ))
        tick()
        assertEquals(1, state.value.discoveredPeers.size)

        // Event at T=50 arrives late — forces re-fold with correct order
        channel.send(EventWithTime(
            Instant.fromEpochMilliseconds(50), "B",
            PeerEvent.Joined(PeerInfo("B", "Bob", "", 0))
        ))
        tick()
        assertEquals(2, state.value.discoveredPeers.size)
        assertEquals("Alice", state.value.discoveredPeers["A"]?.name)
        assertEquals("Bob", state.value.discoveredPeers["B"]?.name)

        // Both lobbies should exist (each peer gets a solo lobby on Joined)
        assertEquals(setOf("A", "B"), state.value.lobbies.keys)

        job.cancel()
    }

    /**
     * Verify that all three peers converge to identical state when events
     * are submitted through the production-like submitEvent path.
     */
    @Test
    fun threePeersConvergeWithSubmitEventFlow() = runTest {
        val net = InMemoryPeerNet()
        val peerA = net.addPeer("A", "Alice")
        val peerB = net.addPeer("B", "Bob")
        val peerC = net.addPeer("C", "Charlie")
        val clock = testClock()

        val channelA = Channel<EventWithTime>(Channel.BUFFERED)
        val channelB = Channel<EventWithTime>(Channel.BUFFERED)
        val channelC = Channel<EventWithTime>(Channel.BUFFERED)
        val localA = Channel<EventWithTime>(Channel.BUFFERED)
        val localB = Channel<EventWithTime>(Channel.BUFFERED)
        val localC = Channel<EventWithTime>(Channel.BUFFERED)
        val stateA = MutableStateFlow(PeerNetState())
        val stateB = MutableStateFlow(PeerNetState())
        val stateC = MutableStateFlow(PeerNetState())

        val infra = launch {
            launch { net.runRouting() }
            launch { gossipRouter(peerA.raw, channelA, localA, "Alice", clock) }
            launch { gossipRouter(peerB.raw, channelB, localB, "Bob", clock) }
            launch { gossipRouter(peerC.raw, channelC, localC, "Charlie", clock) }
            launch { withEventLinearizer(channelA, clock) { s -> launch { s.collect { stateA.value = it } }; awaitCancellation() } }
            launch { withEventLinearizer(channelB, clock) { s -> launch { s.collect { stateB.value = it } }; awaitCancellation() } }
            launch { withEventLinearizer(channelC, clock) { s -> launch { s.collect { stateC.value = it } }; awaitCancellation() } }
        }

        tick()
        net.connectAll()
        tickUntil { stateA.value.discoveredPeers.size >= 3 }

        suspend fun submit(peerId: String, event: PeerEvent) {
            val ewt = EventWithTime(clock.now(), peerId, event)
            val ch = when (peerId) { "A" -> channelA; "B" -> channelB; else -> channelC }
            val local = when (peerId) { "A" -> localA; "B" -> localB; else -> localC }
            ch.send(ewt)
            local.send(ewt)
        }

        // B and C join A's lobby
        submit("B", PeerEvent.JoinedLobby("A", "B"))
        tick()
        submit("C", PeerEvent.JoinedLobby("A", "C"))
        tickUntil { stateA.value.lobbies["A"]?.players?.size == 3 }

        // All three ready up
        submit("A", PeerEvent.ReadyChanged("A", "A", true))
        tick()
        submit("B", PeerEvent.ReadyChanged("A", "B", true))
        tick()
        submit("C", PeerEvent.ReadyChanged("A", "C", true))
        tickUntil { stateA.value.lobbies["A"]?.gamePhase == GamePhase.COUNTDOWN }

        // Wait for state sync to ensure convergence
        tick(2500)

        // All three peers must agree on lobby state
        val lobbyA = stateA.value.lobbies["A"]!!
        val lobbyB = stateB.value.lobbies["A"]!!
        val lobbyC = stateC.value.lobbies["A"]!!

        assertEquals(lobbyA.players.keys, lobbyB.players.keys)
        assertEquals(lobbyB.players.keys, lobbyC.players.keys)
        assertEquals(lobbyA.gamePhase, lobbyB.gamePhase)
        assertEquals(lobbyB.gamePhase, lobbyC.gamePhase)
        assertEquals(lobbyA.playerValues, lobbyB.playerValues)
        assertEquals(lobbyB.playerValues, lobbyC.playerValues)

        infra.cancel()
        net.close()
    }

    // -------------------------------------------------------------------
    // Display name preservation through gossip bridge
    // -------------------------------------------------------------------

    /**
     * In a bridge topology (A↔B↔C), C discovers A indirectly via reachability.
     * The reachability-based Joined event would use peerId as name, but state sync
     * from B should deliver A's original Joined event with the correct name.
     *
     * This catches the "Android shows as android-blablabla" bug.
     */
    @Test
    fun displayNamePreservedThroughBridge() = runTest {
        val net = InMemoryPeerNet()
        val peerA = net.addPeer("A", "Alice")
        val peerB = net.addPeer("B", "Bob")
        val peerC = net.addPeer("C", "Charlie")
        val clock = testClock()

        net.link("A", "B")
        net.link("B", "C")

        val channelA = Channel<EventWithTime>(Channel.BUFFERED)
        val channelB = Channel<EventWithTime>(Channel.BUFFERED)
        val channelC = Channel<EventWithTime>(Channel.BUFFERED)
        val stateA = MutableStateFlow(PeerNetState())
        val stateC = MutableStateFlow(PeerNetState())

        val infra = launch {
            launch { net.runRouting() }
            launch { gossipRouter(peerA.raw, channelA, Channel(Channel.BUFFERED), "Alice", clock) }
            launch { gossipRouter(peerB.raw, channelB, Channel(Channel.BUFFERED), "Bob", clock) }
            launch { gossipRouter(peerC.raw, channelC, Channel(Channel.BUFFERED), "Charlie", clock) }
            launch { withEventLinearizer(channelA, clock) { s -> launch { s.collect { stateA.value = it } }; awaitCancellation() } }
            launch { withEventLinearizer(channelB, clock) { s -> awaitCancellation() } }
            launch { withEventLinearizer(channelC, clock) { s -> launch { s.collect { stateC.value = it } }; awaitCancellation() } }
        }

        tick()
        net.connectAll()
        tick(2500)
        tickUntil { stateC.value.discoveredPeers.size >= 3 }

        // C should see A with name "Alice", not "A" (the peerId)
        assertEquals("Alice", stateC.value.discoveredPeers["A"]?.name,
            "Indirect peer should have correct display name, not peerId")
        assertEquals("Bob", stateC.value.discoveredPeers["B"]?.name)

        // A should see C with name "Charlie"
        assertEquals("Charlie", stateA.value.discoveredPeers["C"]?.name,
            "Indirect peer should have correct display name, not peerId")

        infra.cancel()
        net.close()
    }

    // -------------------------------------------------------------------
    // Reconnection after disconnect
    // -------------------------------------------------------------------

    @Test
    fun peerReconnectsAfterDisconnect() = runTest {
        val net = InMemoryPeerNet()
        val peerA = net.addPeer("A", "Alice")
        val peerB = net.addPeer("B", "Bob")
        val clock = testClock()

        val channelA = Channel<EventWithTime>(Channel.BUFFERED)
        val localA = Channel<EventWithTime>(Channel.BUFFERED)
        val channelB = Channel<EventWithTime>(Channel.BUFFERED)
        val localB = Channel<EventWithTime>(Channel.BUFFERED)
        val stateA = MutableStateFlow(PeerNetState())

        val infra = launch {
            launch { net.runRouting() }
            launch { gossipRouter(peerA.raw, channelA, localA, "Alice", clock) }
            launch { gossipRouter(peerB.raw, channelB, localB, "Bob", clock) }
            launch { withEventLinearizer(channelA, clock) { s -> launch { s.collect { stateA.value = it } }; awaitCancellation() } }
        }

        tick()
        net.connectAll()
        tickUntil { stateA.value.discoveredPeers.size >= 2 }
        assertEquals(setOf("A", "B"), stateA.value.discoveredPeers.keys)

        // Disconnect B
        peerA.incoming.send(RawPeerMessage.Event.Disconnected("B"))
        tickUntil { !stateA.value.discoveredPeers.containsKey("B") }

        // Reconnect B
        peerA.incoming.send(RawPeerMessage.Event.Connected(PeerInfo("B", "Bob", "memory", 0)))
        tickUntil { stateA.value.discoveredPeers.containsKey("B") }
        assertEquals("Bob", stateA.value.discoveredPeers["B"]?.name)

        infra.cancel()
        net.close()
    }

    // -------------------------------------------------------------------
    // Gossip deduplication
    // -------------------------------------------------------------------

    /**
     * When the same event arrives via both direct gossip and state sync,
     * the linearizer should not process it twice.
     */
    @Test
    fun duplicateEventFromStateSyncNotDoubleProcessed() = runTest {
        val net = InMemoryPeerNet()
        val peerA = net.addPeer("A", "Alice")
        val peerB = net.addPeer("B", "Bob")
        val clock = testClock()

        val channelA = Channel<EventWithTime>(Channel.BUFFERED)
        val localA = Channel<EventWithTime>(Channel.BUFFERED)
        val channelB = Channel<EventWithTime>(Channel.BUFFERED)
        val localB = Channel<EventWithTime>(Channel.BUFFERED)
        val stateA = MutableStateFlow(PeerNetState())
        val stateB = MutableStateFlow(PeerNetState())

        val infra = launch {
            launch { net.runRouting() }
            launch { gossipRouter(peerA.raw, channelA, localA, "Alice", clock) }
            launch { gossipRouter(peerB.raw, channelB, localB, "Bob", clock) }
            launch { withEventLinearizer(channelA, clock) { s -> launch { s.collect { stateA.value = it } }; awaitCancellation() } }
            launch { withEventLinearizer(channelB, clock) { s -> launch { s.collect { stateB.value = it } }; awaitCancellation() } }
        }

        tick()
        net.connectAll()
        tickUntil { stateA.value.discoveredPeers.size >= 2 }

        // Submit event from B
        suspend fun submit(peerId: String, event: PeerEvent) {
            val ewt = EventWithTime(clock.now(), peerId, event)
            val ch = if (peerId == "A") channelA else channelB
            val local = if (peerId == "A") localA else localB
            ch.send(ewt)
            local.send(ewt)
        }

        submit("B", PeerEvent.JoinedLobby("A", "B"))
        tick()

        // Wait for state sync to also deliver it
        tick(2500)
        tickUntil { stateA.value.lobbies["A"]?.players?.containsKey("B") == true }

        // B should appear exactly once in A's lobby (not duplicated)
        assertEquals(setOf("A", "B"), stateA.value.lobbies["A"]?.players?.keys)
        // State should be consistent across both peers
        assertEquals(
            stateA.value.lobbies["A"]?.players?.keys,
            stateB.value.lobbies["A"]?.players?.keys,
        )

        infra.cancel()
        net.close()
    }
}
