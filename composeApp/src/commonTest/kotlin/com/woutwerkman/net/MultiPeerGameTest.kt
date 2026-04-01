package com.woutwerkman.net

import com.woutwerkman.game.model.GamePhase
import com.woutwerkman.game.model.VoteChoice
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * End-to-end game tests using [InMemoryPeerNet], [gossipRouter], and [withEventLinearizer].
 *
 * Game logic (start, win, phase transitions) is fully automatic via
 * deterministic cascades and timed events — tests never submit
 * GameStarted or PhaseChanged events manually.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MultiPeerGameTest {

    private fun TestScope.testClock(): Clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(testScheduler.currentTime)
    }

    private fun TestScope.tick(ms: Long = 1) {
        advanceTimeBy(ms)
        testScheduler.runCurrent()
    }

    /** Tick until the predicate is true, or fail after maxTicks. */
    private fun TestScope.tickUntil(maxTicks: Int = 100, predicate: () -> Boolean) {
        repeat(maxTicks) {
            if (predicate()) return
            tick()
        }
        assertTrue(predicate(), "Condition not met after $maxTicks ticks")
    }

    @Test
    fun threePlayersFullGameThroughNetwork() = runTest {
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
            net.startRouting(this)
            launch { gossipRouter(peerA.raw, channelA, localA, "Alice", clock) }
            launch { gossipRouter(peerB.raw, channelB, localB, "Bob", clock) }
            launch { gossipRouter(peerC.raw, channelC, localC, "Charlie", clock) }
            launch { withEventLinearizer(channelA, clock) { s -> launch { s.collect { stateA.value = it } }; awaitCancellation() } }
            launch { withEventLinearizer(channelB, clock) { s -> launch { s.collect { stateB.value = it } }; awaitCancellation() } }
            launch { withEventLinearizer(channelC, clock) { s -> launch { s.collect { stateC.value = it } }; awaitCancellation() } }
        }

        suspend fun submit(peerId: String, event: PeerEvent) {
            val ewt = EventWithTime(clock.now(), peerId, event)
            val channel = when (peerId) { "A" -> channelA; "B" -> channelB; else -> channelC }
            val local = when (peerId) { "A" -> localA; "B" -> localB; else -> localC }
            channel.send(ewt)
            local.send(ewt)
        }

        tick()
        net.connectAll()
        tickUntil { stateA.value.discoveredPeers.size >= 3 }

        // -- B and C join A's lobby --
        submit("B", PeerEvent.JoinedLobby(lobbyId = "A", playerId = "B"))
        tickUntil { stateA.value.lobbies["A"]?.players?.size == 2 }

        submit("C", PeerEvent.JoinedLobby(lobbyId = "A", playerId = "C"))
        tickUntil { stateA.value.lobbies["A"]?.players?.size == 3 }

        for (state in listOf(stateA, stateB, stateC)) {
            assertEquals(setOf("A", "B", "C"), state.value.lobbies["A"]!!.players.keys)
        }

        // -- All ready → auto-cascade to COUNTDOWN --
        submit("A", PeerEvent.ReadyChanged("A", "A", true))
        tick()
        submit("B", PeerEvent.ReadyChanged("A", "B", true))
        tick()
        submit("C", PeerEvent.ReadyChanged("A", "C", true))
        tickUntil { stateA.value.lobbies["A"]?.gamePhase == GamePhase.COUNTDOWN }

        // -- Timed events: countdown ticks (3s total) --
        tick(3000)
        tickUntil { stateA.value.lobbies["A"]?.gamePhase == GamePhase.PLAYING }

        // -- Drive all values to 0 --
        val values = stateA.value.lobbies["A"]!!.playerValues
        for ((playerId, value) in values) {
            repeat(value / 2) {
                submit(playerId, PeerEvent.ButtonPress("A", playerId, playerId, -2))
                tick()
            }
        }

        val remaining = stateA.value.lobbies["A"]!!.playerValues
        for ((playerId, value) in remaining) {
            if (value > 0) {
                val other = listOf("A", "B", "C").first { it != playerId }
                submit(playerId, PeerEvent.ButtonPress("A", playerId, playerId, -2))
                tick()
                submit(other, PeerEvent.ButtonPress("A", other, playerId, 1))
                tick()
            }
        }

        tickUntil { stateA.value.lobbies["A"]?.gamePhase == GamePhase.WIN_COUNTDOWN }

        // -- Win countdown ticks (3s total) --
        tick(3000)
        tickUntil { stateA.value.lobbies["A"]?.gamePhase == GamePhase.VOTING }

        // -- All vote to end --
        submit("A", PeerEvent.VoteCast("A", "A", VoteChoice.END_LOBBY))
        tick()
        submit("B", PeerEvent.VoteCast("A", "B", VoteChoice.END_LOBBY))
        tick()
        submit("C", PeerEvent.VoteCast("A", "C", VoteChoice.PLAY_AGAIN))
        tickUntil { stateA.value.lobbies["A"]?.gamePhase == GamePhase.ENDED }

        infra.cancel()
        net.close()
    }

    @Test
    fun gameWorksThroughGossipBridge() = runTest {
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
        val localA = Channel<EventWithTime>(Channel.BUFFERED)
        val localB = Channel<EventWithTime>(Channel.BUFFERED)
        val localC = Channel<EventWithTime>(Channel.BUFFERED)
        val stateA = MutableStateFlow(PeerNetState())
        val stateB = MutableStateFlow(PeerNetState())
        val stateC = MutableStateFlow(PeerNetState())

        val infra = launch {
            net.startRouting(this)
            launch { gossipRouter(peerA.raw, channelA, localA, "Alice", clock) }
            launch { gossipRouter(peerB.raw, channelB, localB, "Bob", clock) }
            launch { gossipRouter(peerC.raw, channelC, localC, "Charlie", clock) }
            launch { withEventLinearizer(channelA, clock) { s -> launch { s.collect { stateA.value = it } }; awaitCancellation() } }
            launch { withEventLinearizer(channelB, clock) { s -> launch { s.collect { stateB.value = it } }; awaitCancellation() } }
            launch { withEventLinearizer(channelC, clock) { s -> launch { s.collect { stateC.value = it } }; awaitCancellation() } }
        }

        suspend fun submit(peerId: String, event: PeerEvent) {
            val ewt = EventWithTime(clock.now(), peerId, event)
            val channel = when (peerId) { "A" -> channelA; "B" -> channelB; else -> channelC }
            val local = when (peerId) { "A" -> localA; "B" -> localB; else -> localC }
            channel.send(ewt)
            local.send(ewt)
        }

        tick()
        net.connectAll()
        // With bridge topology, need state sync for full propagation
        tick(2500)
        tickUntil { stateA.value.discoveredPeers.size >= 3 }

        submit("B", PeerEvent.JoinedLobby("A", "B"))
        tick()
        submit("C", PeerEvent.JoinedLobby("A", "C"))
        tickUntil { stateA.value.lobbies["A"]?.players?.size == 3 }

        submit("A", PeerEvent.ReadyChanged("A", "A", true))
        tick()
        submit("B", PeerEvent.ReadyChanged("A", "B", true))
        tick()
        submit("C", PeerEvent.ReadyChanged("A", "C", true))
        tickUntil { stateA.value.lobbies["A"]?.gamePhase == GamePhase.COUNTDOWN }

        tick(3000)
        tickUntil { stateA.value.lobbies["A"]?.gamePhase == GamePhase.PLAYING }

        submit("A", PeerEvent.ButtonPress("A", "A", "C", 1))
        tick()
        submit("C", PeerEvent.ButtonPress("A", "C", "A", 1))
        // Wait for gossip to propagate through bridge
        tick(2500)
        tickUntil {
            stateA.value.lobbies["A"]?.playerValues == stateB.value.lobbies["A"]?.playerValues &&
            stateB.value.lobbies["A"]?.playerValues == stateC.value.lobbies["A"]?.playerValues
        }

        assertEquals(stateA.value.lobbies["A"]?.playerValues, stateB.value.lobbies["A"]?.playerValues)
        assertEquals(stateB.value.lobbies["A"]?.playerValues, stateC.value.lobbies["A"]?.playerValues)

        infra.cancel()
        net.close()
    }
}
