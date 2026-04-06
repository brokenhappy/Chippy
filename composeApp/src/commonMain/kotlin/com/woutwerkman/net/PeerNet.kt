package com.woutwerkman.net

import com.woutwerkman.game.model.GamePhase
import com.woutwerkman.game.model.VoteChoice
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.time.Clock
import kotlin.time.Instant

// ---------------------------------------------------------------------------
// Timed events — deterministic results of folding, scheduled by the engine
// ---------------------------------------------------------------------------

/**
 * A delayed event derived deterministically from the state after folding.
 *
 * Because the fold is deterministic, every peer derives the same timed events
 * from the same event log. They don't need to be shared over the network —
 * each peer schedules them independently.
 */
data class TimedEvent(
    val delay: Duration,
    val event: PeerEvent,
)

// ---------------------------------------------------------------------------
// Public state — the result of deterministically folding all linearized events
// ---------------------------------------------------------------------------

/**
 * The shared state of the peer network.
 *
 * Every peer independently folds the same ordered event log into this structure.
 * Because the fold is deterministic and the event order is agreed upon (timestamp + peerId),
 * all peers converge to the exact same [PeerNetState].
 *
 * ## What PeerNet is for
 *
 * The peer network layer exists for two reasons:
 *
 * 1. **Discovery & gossip** — peers find each other on the LAN via mDNS/Bonjour,
 *    then gossip events so that peers which can't reach each other directly
 *    (e.g. Android emulator ↔ iPhone) can still communicate through any bridge peer.
 *
 * 2. **Resilience** — individual peers can leave (crash, close app, lose WiFi) without
 *    disrupting the rest of the network. The event log is replicated on every peer,
 *    so surviving peers keep full state. When a peer reconnects it catches up via
 *    periodic state sync.
 *
 * PeerNet does **not** own UI navigation or countdown display.
 * Those live in the app layer ([App.kt]) which reads [PeerNetState] as a pure input.
 * Game rules (start, win detection, vote tallying) ARE expressed here as deterministic
 * cascades and timed events, so all peers agree on state transitions automatically.
 *
 * ## State derivation
 *
 * State is derived by folding events one at a time via [after]. The linearization engine
 * calls `state = state.after(event)` for each event in timestamp order.
 * Tests can do the same without any networking:
 * ```
 * val (state, timedEvents) = PeerNetState()
 *     .after(PeerEvent.Joined(peerA))
 *     .after(PeerEvent.Joined(peerB))
 *     .after(PeerEvent.JoinedLobby("A", "B"))  // B joins A's lobby
 * ```
 */
@Serializable
data class PeerNetState(
    /** All peers currently visible on the network. */
    val discoveredPeers: Map<String, PeerInfo> = emptyMap(),
    /** All active lobbies, keyed by lobbyId. A player may be in at most one. */
    val lobbies: Map<String, LobbyInfo> = emptyMap(),
)

// ---------------------------------------------------------------------------
// State transitions — the core fold
// ---------------------------------------------------------------------------

/**
 * Apply a single event and return the new state plus any delayed [TimedEvent]s.
 *
 * Immediate cascades (all-ready → game start, all-zeros → win, all-votes → result)
 * are resolved inline. Only delayed transitions (countdown timers) are returned
 * as [TimedEvent]s for the engine to schedule.
 */
fun PeerNetState.after(event: PeerEvent): Pair<PeerNetState, TimedEvent?> {
    val oldState = this
    val applied = applyEvent(event)
    val resolved = applied.resolveImmediateCascades()
    val timedEvent = resolved.deriveNewTimedEvent(oldState)
    return resolved to timedEvent
}

/** Chaining helper so you can write `state.after(e1).after(e2).after(e3)`. */
fun Pair<PeerNetState, TimedEvent?>.after(event: PeerEvent): Pair<PeerNetState, TimedEvent?> =
    first.after(event)

// ---------------------------------------------------------------------------
// Pure event application (no cascades)
// ---------------------------------------------------------------------------

private fun PeerNetState.applyEvent(event: PeerEvent): PeerNetState = when (event) {
    is PeerEvent.Joined -> {
        val peer = event.peer
        val isWebClient = peer.id.startsWith("web-")
        val updatedLobbies = if (isWebClient) {
            // Web clients are thin clients — they don't host their own lobby.
            lobbies
        } else if (peer.id in lobbies) {
            // Lobby already exists (possibly created by an early JoinedLobby due to clock skew).
            // Update the host's player name in case it was a placeholder.
            val existing = lobbies[peer.id]!!
            val updatedPlayers = if (peer.id in existing.players) {
                existing.players + (peer.id to existing.players[peer.id]!!.copy(name = peer.name))
            } else {
                existing.players + (peer.id to LobbyPlayer(peer.name))
            }
            lobbies + (peer.id to existing.copy(players = updatedPlayers))
        } else {
            val soloLobby = LobbyInfo(
                lobbyId = peer.id,
                hostId = peer.id,
                players = mapOf(peer.id to LobbyPlayer(peer.name)),
            )
            lobbies + (peer.id to soloLobby)
        }
        copy(
            discoveredPeers = discoveredPeers + (peer.id to peer),
            lobbies = updatedLobbies,
        )
    }
    is PeerEvent.Left -> {
        // Also remove any web client virtual IDs hosted by this peer.
        // Web client IDs follow the pattern "web-{hostId}-{session}".
        val webClientPrefix = "web-${event.peerId}-"
        val webClientIds = discoveredPeers.keys.filter { it.startsWith(webClientPrefix) }
        val allRemovedIds = setOf(event.peerId) + webClientIds
        val updatedLobbies = lobbies
            .mapValues { (_, l) -> l.copy(players = l.players.filterKeys { it !in allRemovedIds }) }
            .filterValues { it.players.isNotEmpty() }
        copy(
            discoveredPeers = discoveredPeers.filterKeys { it !in allRemovedIds },
            lobbies = updatedLobbies.filterKeys { it !in allRemovedIds },
        )
    }
    is PeerEvent.JoinedLobby -> {
        val targetLobby = lobbies[event.lobbyId] ?: run {
            // Lobby doesn't exist yet — host's Joined event may arrive later (clock skew).
            // Create a placeholder lobby so the join isn't lost.
            val hostName = discoveredPeers[event.lobbyId]?.name ?: event.lobbyId
            LobbyInfo(
                lobbyId = event.lobbyId,
                hostId = event.lobbyId,
                players = mapOf(event.lobbyId to LobbyPlayer(hostName)),
            )
        }
        val name = discoveredPeers[event.playerId]?.name ?: "Unknown"
        val afterLeave = lobbies
            .mapValues { (_, l) -> l.copy(players = l.players - event.playerId) }
            .filterValues { it.players.isNotEmpty() }
        val base = afterLeave[event.lobbyId] ?: targetLobby
        val updatedTarget = base.copy(
            players = base.players + (event.playerId to LobbyPlayer(name)),
        )
        copy(lobbies = afterLeave + (event.lobbyId to updatedTarget))
    }
    is PeerEvent.LeftLobby -> {
        val lobby = lobbies[event.lobbyId] ?: return this
        val updated = lobby.copy(players = lobby.players - event.playerId)
        val afterLeave = if (updated.players.isEmpty()) lobbies - event.lobbyId
                         else lobbies + (event.lobbyId to updated)
        val name = discoveredPeers[event.playerId]?.name ?: "Unknown"
        val soloLobby = LobbyInfo(
            lobbyId = event.playerId,
            hostId = event.playerId,
            players = mapOf(event.playerId to LobbyPlayer(name)),
        )
        copy(lobbies = afterLeave + (event.playerId to soloLobby))
    }
    is PeerEvent.ReadyChanged -> withLobby(event.lobbyId) { lobby ->
        val player = lobby.players[event.playerId] ?: return@withLobby lobby
        lobby.copy(players = lobby.players + (event.playerId to player.copy(isReady = event.isReady)))
    }
    is PeerEvent.GameStarted -> withLobby(event.lobbyId) { lobby ->
        if (lobby.gamePhase != GamePhase.WAITING) return@withLobby lobby
        lobby.copy(gamePhase = GamePhase.COUNTDOWN, playerValues = event.playerValues, votes = emptyMap(), countdownValue = 3)
    }
    is PeerEvent.ButtonPress -> withLobby(event.lobbyId) { lobby ->
        val cur = lobby.playerValues[event.targetId] ?: return@withLobby lobby
        lobby.copy(playerValues = lobby.playerValues + (event.targetId to (cur + event.delta).coerceIn(-25, 25)))
    }
    is PeerEvent.PhaseChanged -> withLobby(event.lobbyId) { lobby ->
        val valid = when (event.newPhase) {
            GamePhase.PLAYING -> lobby.gamePhase == GamePhase.COUNTDOWN
            GamePhase.VOTING -> lobby.gamePhase == GamePhase.WIN_COUNTDOWN
            GamePhase.WAITING -> true
            else -> true
        }
        if (!valid) return@withLobby lobby
        val updated = lobby.copy(gamePhase = event.newPhase)
        when (event.newPhase) {
            GamePhase.WIN_COUNTDOWN -> updated.copy(countdownValue = 3)
            GamePhase.PLAYING, GamePhase.VOTING -> updated.copy(countdownValue = null)
            GamePhase.WAITING -> updated.copy(
                playerValues = emptyMap(), votes = emptyMap(), countdownValue = null,
                players = updated.players.mapValues { (_, p) -> p.copy(isReady = false) },
                round = updated.round + 1,
            )
            else -> updated
        }
    }
    is PeerEvent.CountdownTick -> withLobby(event.lobbyId) { lobby ->
        lobby.copy(countdownValue = event.value)
    }
    is PeerEvent.VoteCast -> withLobby(event.lobbyId) { lobby ->
        lobby.copy(votes = lobby.votes + (event.playerId to event.choice))
    }
    is PeerEvent.WebPortChanged -> {
        val peer = discoveredPeers[event.peerId] ?: return this
        copy(discoveredPeers = discoveredPeers + (event.peerId to peer.copy(
            webPort = event.webPort,
            webSecure = event.webSecure,
            platform = event.platform,
        )))
    }
}

private inline fun PeerNetState.withLobby(lobbyId: String, transform: (LobbyInfo) -> LobbyInfo): PeerNetState {
    val lobby = lobbies[lobbyId] ?: return this
    return copy(lobbies = lobbies + (lobbyId to transform(lobby)))
}

// ---------------------------------------------------------------------------
// Immediate cascades — deterministic state transitions applied inline
// ---------------------------------------------------------------------------

private fun PeerNetState.resolveImmediateCascades(): PeerNetState {
    var state = this
    var changed = true
    while (changed) {
        changed = false
        for ((lobbyId, lobby) in state.lobbies) {
            val cascade = lobby.findImmediateCascade(lobbyId) ?: continue
            state = state.applyEvent(cascade)
            changed = true
            break // restart — state changed
        }
    }
    return state
}

private fun LobbyInfo.findImmediateCascade(lobbyId: String): PeerEvent? = when {
    // All ready (≥2 players) → start game
    gamePhase == GamePhase.WAITING &&
    players.size >= 2 &&
    players.values.all { it.isReady } ->
        PeerEvent.GameStarted(lobbyId, deterministicPlayerValues(lobbyId, round, players.keys))

    // All values zero during play → win countdown
    gamePhase == GamePhase.PLAYING &&
    playerValues.isNotEmpty() &&
    playerValues.values.all { it == 0 } ->
        PeerEvent.PhaseChanged(lobbyId, GamePhase.WIN_COUNTDOWN)

    // All votes in → resolve
    gamePhase == GamePhase.VOTING &&
    players.isNotEmpty() &&
    votes.size >= players.size -> {
        val endCount = votes.values.count { it == VoteChoice.END_LOBBY }
        val playCount = votes.values.count { it == VoteChoice.PLAY_AGAIN }
        PeerEvent.PhaseChanged(lobbyId, if (endCount > playCount) GamePhase.ENDED else GamePhase.WAITING)
    }

    // Not enough players during active game → cancel
    gamePhase != GamePhase.WAITING &&
    gamePhase != GamePhase.ENDED &&
    players.size < 2 ->
        PeerEvent.PhaseChanged(lobbyId, GamePhase.WAITING)

    else -> null
}

// ---------------------------------------------------------------------------
// Delayed timed events — derived from phase changes
// ---------------------------------------------------------------------------

private fun PeerNetState.deriveNewTimedEvent(oldState: PeerNetState): TimedEvent? {
    for ((lobbyId, lobby) in lobbies) {
        val oldLobby = oldState.lobbies[lobbyId]

        // Countdown value changed → schedule next tick or phase transition
        val oldCountdown = oldLobby?.countdownValue
        if (lobby.countdownValue != null && lobby.countdownValue != oldCountdown) {
            val nextPhase = when (lobby.gamePhase) {
                GamePhase.COUNTDOWN -> GamePhase.PLAYING
                GamePhase.WIN_COUNTDOWN -> GamePhase.VOTING
                else -> null
            }
            if (nextPhase != null) {
                return if (lobby.countdownValue > 1) {
                    TimedEvent(1.seconds, PeerEvent.CountdownTick(lobbyId, lobby.countdownValue - 1))
                } else {
                    TimedEvent(1.seconds, PeerEvent.PhaseChanged(lobbyId, nextPhase))
                }
            }
        }
    }
    return null
}

internal object InstantSerializer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.LONG)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeLong(value.toEpochMilliseconds())
    override fun deserialize(decoder: Decoder): Instant = Instant.fromEpochMilliseconds(decoder.decodeLong())
}

@Serializable
data class EventWithTime(
    @Serializable(with = InstantSerializer::class) val time: Instant,
    val peerId: String,
    val event: PeerEvent,
)

suspend fun <T> withEventLinearizer(
    events: ReceiveChannel<EventWithTime>,
    clock: Clock = Clock.System,
    block: suspend CoroutineScope.(StateFlow<PeerNetState>) -> T,
): T = coroutineScope {
    val state = MutableStateFlow(PeerNetState())

    val linearizer = launch {
        val log = mutableListOf<EventWithTime>()
        var scheduled = emptyList<EventWithTime>()
        while (true) {
            val next = select {
                events.onReceive { it }
                scheduled.firstOrNull()?.let { s ->
                    val delay = (s.time - clock.now()).coerceAtLeast(Duration.ZERO)
                    @OptIn(ExperimentalCoroutinesApi::class)
                    onTimeout(delay) { s }
                }
            }
            // Deduplicate — skip if already in log
            if (next in log) continue
            // Insert sorted by event comparator (time, then fair hash, then peerId)
            val rawIdx = log.binarySearch(comparison = { eventComparator.compare(it, next) })
            val idx = if (rawIdx >= 0) rawIdx else (-rawIdx - 1)
            log.add(idx, next)
            // Re-fold
            val (newState, newScheduled) = log.replayAndSchedule(clock.now())
            state.value = newState
            scheduled = newScheduled
        }
    }
    try {
        block(state)
    } finally {
        linearizer.cancel()
    }
}

fun List<EventWithTime>.replayAndSchedule(
    now: Instant,
    initial: PeerNetState = PeerNetState(),
): Pair<PeerNetState, List<EventWithTime>> {
    var state = initial
    val pending = mutableListOf<EventWithTime>() // sorted by time

    for (ewt in this) {
        state = applyPendingBefore(state, pending, ewt.time)
        state = applyWithTimedEvent(state, ewt, pending)
    }
    state = applyPendingBefore(state, pending, now)
    return state to pending.toList()
}

private fun applyWithTimedEvent(
    state: PeerNetState,
    ewt: EventWithTime,
    pending: MutableList<EventWithTime>,
): PeerNetState {
    val (newState, timedEvent) = state.after(ewt.event)
    if (timedEvent == null) return newState
    val fireTime = ewt.time + timedEvent.delay
    val timedEwt = EventWithTime(fireTime, ewt.peerId, timedEvent.event)
    return if (timedEvent.delay == Duration.ZERO) {
        applyWithTimedEvent(newState, timedEwt, pending)
    } else {
        insertSorted(pending, timedEwt)
        newState
    }
}

private fun applyPendingBefore(
    initialState: PeerNetState,
    pending: MutableList<EventWithTime>,
    before: Instant,
): PeerNetState {
    var state = initialState
    while (pending.isNotEmpty() && pending.first().time <= before) {
        val next = pending.removeAt(0)
        state = applyWithTimedEvent(state, next, pending)
    }
    return state
}

internal fun fnv1a(input: String): Int =
    input.encodeToByteArray().fold(0x811c9dc5.toInt()) { acc, b ->
        (acc xor b.toInt()) * 0x01000193
    }

internal val eventComparator: Comparator<EventWithTime> =
    compareBy<EventWithTime> { it.time }
        .thenBy { fnv1a(it.peerId + it.time.toEpochMilliseconds()) }
        .thenBy { it.peerId }

private fun insertSorted(list: MutableList<EventWithTime>, ewt: EventWithTime) {
    val rawIdx = list.binarySearch(comparison = { eventComparator.compare(it, ewt) })
    val idx = if (rawIdx >= 0) rawIdx else (-rawIdx - 1)
    list.add(idx, ewt)
}

// ---------------------------------------------------------------------------
// Deterministic player values — same inputs always produce same outputs
// ---------------------------------------------------------------------------

internal fun deterministicPlayerValues(lobbyId: String, round: Int, playerIds: Set<String>): Map<String, Int> {
    return playerIds.associateWith { playerId ->
        val seed = "$lobbyId:$round:$playerId"
        val hash = seed.fold(0) { acc, c -> acc * 31 + c.code }
        val positive = (hash and 0x7FFFFFFF) % 12
        positive * 2 + 1 // Odd number in range 1..23
    }
}

// ---------------------------------------------------------------------------
// Data classes
// ---------------------------------------------------------------------------

/**
 * A lobby — a group of players who intend to play together.
 *
 * Every peer starts in their own solo lobby. Tapping another peer joins their lobby.
 * Game-phase state is scoped per-lobby so multiple independent lobbies can coexist.
 */
@Serializable
data class LobbyInfo(
    val lobbyId: String,
    val hostId: String,
    val players: Map<String, LobbyPlayer> = emptyMap(),
    val gamePhase: GamePhase = GamePhase.WAITING,
    val playerValues: Map<String, Int> = emptyMap(),
    val votes: Map<String, VoteChoice> = emptyMap(),
    val round: Int = 0,
    val countdownValue: Int? = null,
)

/** A player in a lobby. */
@Serializable
data class LobbyPlayer(val name: String, val isReady: Boolean = false)

// ---------------------------------------------------------------------------
// Connection interface
// ---------------------------------------------------------------------------

/**
 * A connection to the peer network with collective event linearization.
 *
 * All peers in the network agree on the same ordered sequence of events.
 * The [state] flow emits the current state derived by folding all committed events.
 */
interface PeerNetConnection {
    val localId: String
    val state: Flow<PeerNetState>
    suspend fun submitEvent(event: PeerEvent): Boolean
}

// ---------------------------------------------------------------------------
// Events — the inputs to the fold
// ---------------------------------------------------------------------------

/**
 * Events in the peer network.
 *
 * System events ([Joined], [Left]) are generated by the network layer.
 * Game-logic events ([GameStarted], [PhaseChanged]) are generated by deterministic
 * cascades and timed events — consumers should NOT submit them directly.
 * All other events are submitted by consumers via [PeerNetConnection.submitEvent].
 */
@Serializable
sealed class PeerEvent {
    // --- System events (emitted internally by the net layer) ---
    @Serializable data class Joined(val peer: PeerInfo) : PeerEvent()
    @Serializable data class Left(val peerId: String) : PeerEvent()

    // --- Lobby membership events ---
    @Serializable data class JoinedLobby(val lobbyId: String, val playerId: String) : PeerEvent()
    @Serializable data class LeftLobby(val lobbyId: String, val playerId: String) : PeerEvent()

    // --- Player actions ---
    @Serializable data class ReadyChanged(val lobbyId: String, val playerId: String, val isReady: Boolean) : PeerEvent()
    @Serializable data class ButtonPress(val lobbyId: String, val sourceId: String, val targetId: String, val delta: Int) : PeerEvent()
    @Serializable data class VoteCast(val lobbyId: String, val playerId: String, val choice: VoteChoice) : PeerEvent()

    // --- Metadata events ---
    @Serializable data class WebPortChanged(
        val peerId: String,
        val webPort: Int,
        val webSecure: Boolean,
        val platform: String,
    ) : PeerEvent()

    // --- Auto-generated by cascades/timed events (do not submit manually) ---
    @Serializable data class GameStarted(val lobbyId: String, val playerValues: Map<String, Int>) : PeerEvent()
    @Serializable data class PhaseChanged(val lobbyId: String, val newPhase: GamePhase) : PeerEvent()
    @Serializable data class CountdownTick(val lobbyId: String, val value: Int) : PeerEvent()
}

// ---------------------------------------------------------------------------
// Connection entry point
// ---------------------------------------------------------------------------

suspend fun <T> withPeerNetConnection(
    config: PeerNetConfig = PeerNetConfig(),
    clock: Clock = Clock.System,
    block: suspend CoroutineScope.(PeerNetConnection) -> T,
): T = coroutineScope {
    withRawPeerNetConnection(config) { rawConn ->
        val eventChannel = Channel<EventWithTime>(Channel.BUFFERED)
        val localEvents = Channel<EventWithTime>(Channel.BUFFERED)
        withEventLinearizer(eventChannel, clock) { state ->
            val gossipJob = launch { gossipRouter(rawConn, eventChannel, localEvents, config.displayName, clock) }
            val connection = object : PeerNetConnection {
                override val localId: String = rawConn.localPeerId
                override val state: StateFlow<PeerNetState> = state
                override suspend fun submitEvent(event: PeerEvent): Boolean {
                    val ewt = EventWithTime(clock.now(), rawConn.localPeerId, event)
                    eventChannel.send(ewt)
                    localEvents.send(ewt)
                    return true
                }
            }
            try {
                block(connection)
            } finally {
                // Cancel the gossip router so withEventLinearizer's coroutineScope
                // doesn't block waiting for it.
                gossipJob.cancel()
                // Best-effort: inform all peers we're leaving before the connection closes.
                // Uses broadcastDirect to bypass the channel (the channel-processing
                // coroutine is already cancelled at this point).
                try {
                    val leftEwt = EventWithTime(
                        clock.now(), rawConn.localPeerId,
                        PeerEvent.Left(rawConn.localPeerId),
                    )
                    val payload = "$LIN_EVENT${linJson.encodeToString(leftEwt)}"
                    rawConn.broadcastDirect(payload.encodeToByteArray())
                } catch (_: Exception) {}
            }
        }
    }
}
