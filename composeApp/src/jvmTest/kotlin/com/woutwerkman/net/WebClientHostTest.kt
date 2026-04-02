package com.woutwerkman.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlin.time.Duration

class WebClientHostTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun createTestConnection(name: String = "TestHost"): TestPeerNetConnection {
        return TestPeerNetConnection("host-1", name)
    }

    @Test
    fun webServerStartsAndServesHtml() {
        runBlocking {
            val conn = createTestConnection()
            hostingWebClient(conn) { url ->
                assertTrue(url.startsWith("http://"), "URL should use HTTP: $url")

                val response = httpGet(url)
                assertEquals(200, response.statusCode())
                val body = response.body()
                assertTrue(body.contains("<!DOCTYPE html>"), "Should serve HTML page")
                assertTrue(body.contains("Chippy"), "HTML should contain app name")
                assertTrue(body.contains("composeApp.js"), "HTML should load the Compose WASM app")
            }
        }
    }

    @Test
    fun qrCodeCanBeGeneratedFromUrl() {
        runBlocking {
            hostingWebClient(createTestConnection()) { url ->
                val qr = generateQrCodePng(url)
                assertTrue(qr.size > 100, "QR code should have substantial content (got ${qr.size} bytes)")
                assertEquals(0x89.toByte(), qr[0], "QR should be PNG format")
                assertEquals(0x50.toByte(), qr[1]) // 'P'
                assertEquals(0x4E.toByte(), qr[2]) // 'N'
                assertEquals(0x47.toByte(), qr[3]) // 'G'
            }
        }
    }

    @Test
    fun websocketClientReceivesIdentityAndState() {
        runBlocking {
            val conn = createTestConnection("Alice")
            hostingWebClient(conn) { url ->
                val messages = LinkedBlockingQueue<String>()
                val wsUrl = url.replace("http://", "ws://") + "/ws"
                val ws = connectWebSocket(wsUrl, messages)
                sendHello(ws)

                val identityRaw = messages.poll(5, TimeUnit.SECONDS)
                assertNotNull(identityRaw, "Should receive Identity message")
                val identity = json.decodeFromString<WsMessage>(identityRaw)
                assertIs<WsMessage.Identity>(identity)
                assertTrue(identity.localId.startsWith("web-"), "Virtual ID should start with web-")
                assertEquals("host-1", identity.hostId)

                val stateRaw = messages.poll(5, TimeUnit.SECONDS)
                assertNotNull(stateRaw, "Should receive StateUpdate message")
                val stateMsg = json.decodeFromString<WsMessage>(stateRaw)
                assertIs<WsMessage.StateUpdate>(stateMsg)
                assertTrue(
                    stateMsg.state.discoveredPeers.containsKey("host-1"),
                    "State should contain the host peer"
                )

                ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join()
            }
        }
    }

    @Test
    fun websocketClientAppearsAsJoinedPeer() {
        runBlocking {
            val conn = createTestConnection("Alice")
            hostingWebClient(conn) { url ->
                val messages = LinkedBlockingQueue<String>()
                val wsUrl = url.replace("http://", "ws://") + "/ws"
                val ws = connectWebSocket(wsUrl, messages)
                sendHello(ws)

                val identityRaw = messages.poll(5, TimeUnit.SECONDS)!!
                val identity = json.decodeFromString<WsMessage>(identityRaw) as WsMessage.Identity
                val virtualId = identity.localId

                var found = false
                repeat(10) {
                    val msg = messages.poll(2, TimeUnit.SECONDS) ?: return@repeat
                    val parsed = json.decodeFromString<WsMessage>(msg)
                    if (parsed is WsMessage.StateUpdate && parsed.state.discoveredPeers.containsKey(virtualId)) {
                        found = true
                        assertEquals("Web Player", parsed.state.discoveredPeers[virtualId]!!.name)
                        return@repeat
                    }
                }
                assertTrue(found, "Web client should appear as a discovered peer with ID $virtualId")

                ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join()
            }
        }
    }

    @Test
    fun helloPlayerNameIsUsed() {
        runBlocking {
            val conn = createTestConnection("Alice")
            hostingWebClient(conn) { url ->
                val messages = LinkedBlockingQueue<String>()
                val wsUrl = url.replace("http://", "ws://") + "/ws"
                val ws = connectWebSocket(wsUrl, messages)
                sendHello(ws, "CustomName")

                val identity = readIdentity(messages)

                awaitCondition("Peer uses custom name") {
                    conn.state.value.discoveredPeers[identity.localId]?.name == "CustomName"
                }

                ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join()
            }
        }
    }

    @Test
    fun websocketClientCanSubmitEvents() {
        runBlocking {
            val conn = createTestConnection("Alice")
            hostingWebClient(conn) { url ->
                val messages = LinkedBlockingQueue<String>()
                val wsUrl = url.replace("http://", "ws://") + "/ws"
                val ws = connectWebSocket(wsUrl, messages)
                sendHello(ws)

                val identityRaw = messages.poll(5, TimeUnit.SECONDS)!!
                val identity = json.decodeFromString<WsMessage>(identityRaw) as WsMessage.Identity
                val virtualId = identity.localId

                val event = PeerEvent.JoinedLobby(lobbyId = "host-1", playerId = virtualId)
                val submission = json.encodeToString<WsMessage>(WsMessage.EventSubmission(event))
                ws.sendText(submission, true).join()

                var found = false
                repeat(20) {
                    val msg = messages.poll(2, TimeUnit.SECONDS) ?: return@repeat
                    val parsed = json.decodeFromString<WsMessage>(msg)
                    if (parsed is WsMessage.StateUpdate) {
                        val lobby = parsed.state.lobbies["host-1"]
                        if (lobby != null && lobby.players.containsKey(virtualId)) {
                            found = true
                            return@repeat
                        }
                    }
                }
                assertTrue(found, "Web client should be in the lobby after submitting JoinedLobby")

                ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join()
            }
        }
    }

    @Test
    fun multipleWebClientsCanSeeEachOther() {
        runBlocking {
            val conn = createTestConnection("Alice")
            hostingWebClient(conn) { url ->
                val wsUrl = url.replace("http://", "ws://") + "/ws"

                val messages1 = LinkedBlockingQueue<String>()
                val messages2 = LinkedBlockingQueue<String>()
                val ws1 = connectWebSocket(wsUrl, messages1)
                val ws2 = connectWebSocket(wsUrl, messages2)
                sendHello(ws1)
                sendHello(ws2)

                val id1 = readIdentity(messages1)
                val id2 = readIdentity(messages2)
                assertNotEquals(id1.localId, id2.localId, "Each client should get a unique ID")

                awaitCondition("Both clients visible as peers") {
                    val peers = conn.state.value.discoveredPeers
                    peers.containsKey(id1.localId) && peers.containsKey(id2.localId)
                }

                val joinEvent1 = PeerEvent.JoinedLobby(lobbyId = "host-1", playerId = id1.localId)
                ws1.sendText(json.encodeToString<WsMessage>(WsMessage.EventSubmission(joinEvent1)), true).join()

                awaitCondition("Client 1 in lobby") {
                    conn.state.value.lobbies["host-1"]?.players?.containsKey(id1.localId) == true
                }

                val joinEvent2 = PeerEvent.JoinedLobby(lobbyId = "host-1", playerId = id2.localId)
                ws2.sendText(json.encodeToString<WsMessage>(WsMessage.EventSubmission(joinEvent2)), true).join()

                awaitCondition("Client 2 in lobby") {
                    conn.state.value.lobbies["host-1"]?.players?.containsKey(id2.localId) == true
                }

                val finalState = conn.state.value
                val lobby = finalState.lobbies["host-1"]!!
                assertTrue(lobby.players.containsKey(id1.localId), "Lobby should contain client 1")
                assertTrue(lobby.players.containsKey(id2.localId), "Lobby should contain client 2")
                assertTrue(lobby.players.containsKey("host-1"), "Lobby should contain host")

                var client1SeesLobby = false
                repeat(20) {
                    val msg = messages1.poll(2, TimeUnit.SECONDS) ?: return@repeat
                    val parsed = json.decodeFromString<WsMessage>(msg)
                    if (parsed is WsMessage.StateUpdate) {
                        val l = parsed.state.lobbies["host-1"]
                        if (l != null && l.players.containsKey(id1.localId) && l.players.containsKey(id2.localId)) {
                            client1SeesLobby = true
                            return@repeat
                        }
                    }
                }
                assertTrue(client1SeesLobby, "Client 1 should receive state update with both players in lobby")

                ws1.sendClose(WebSocket.NORMAL_CLOSURE, "done").join()
                ws2.sendClose(WebSocket.NORMAL_CLOSURE, "done").join()
            }
        }
    }

    @Test
    fun websocketDisconnectEmitsLeftEvent() {
        runBlocking {
            val conn = createTestConnection("Alice")
            hostingWebClient(conn) { url ->
                val messages = LinkedBlockingQueue<String>()
                val wsUrl = url.replace("http://", "ws://") + "/ws"
                val ws = connectWebSocket(wsUrl, messages)
                sendHello(ws)

                val identityRaw = messages.poll(5, TimeUnit.SECONDS)!!
                val identity = json.decodeFromString<WsMessage>(identityRaw) as WsMessage.Identity
                val virtualId = identity.localId

                while (messages.poll(500, TimeUnit.MILLISECONDS) != null) { /* drain */ }

                ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join()
                Thread.sleep(1000)

                val state = conn.state.value
                assertFalse(
                    state.discoveredPeers.containsKey(virtualId),
                    "Web client should be removed from peers after disconnect"
                )
            }
        }
    }

    @Test
    fun reconnectingClientKeepsSameId() {
        runBlocking {
            val conn = createTestConnection("Alice")
            hostingWebClient(conn) { url ->
                val wsUrl = url.replace("http://", "ws://") + "/ws"

                // First connection — get an identity
                val messages1 = LinkedBlockingQueue<String>()
                val ws1 = connectWebSocket(wsUrl, messages1)
                sendHello(ws1, "Bob")
                val identity1 = readIdentity(messages1)
                val originalId = identity1.localId

                awaitCondition("First client visible") {
                    conn.state.value.discoveredPeers.containsKey(originalId)
                }

                // Disconnect first client
                ws1.sendClose(WebSocket.NORMAL_CLOSURE, "done").join()
                Thread.sleep(500)

                // Reconnect with the same ID
                val messages2 = LinkedBlockingQueue<String>()
                val ws2 = connectWebSocket(wsUrl, messages2)
                val reconnectMsg = json.encodeToString<WsMessage>(
                    WsMessage.Reconnect(localId = originalId, playerName = "Bob")
                )
                ws2.sendText(reconnectMsg, true).join()

                val identity2 = readIdentity(messages2)
                assertEquals(originalId, identity2.localId, "Reconnected client should keep its original ID")

                awaitCondition("Reconnected client visible with same ID") {
                    conn.state.value.discoveredPeers.containsKey(originalId)
                }
                assertEquals(
                    "Bob",
                    conn.state.value.discoveredPeers[originalId]?.name,
                    "Reconnected client should keep its name"
                )

                ws2.sendClose(WebSocket.NORMAL_CLOSURE, "done").join()
            }
        }
    }

    /**
     * When the hostingWebClient block exits (server shuts down), the web client's
     * virtual ID should be removed from state by the handler's finally block.
     */
    @Test
    fun hostShutdownCleansUpWebClient() {
        runBlocking {
            val conn = createTestConnection("Alice")
            var virtualId: String? = null

            hostingWebClient(conn) { url ->
                val messages = LinkedBlockingQueue<String>()
                val wsUrl = url.replace("http://", "ws://") + "/ws"
                val ws = connectWebSocket(wsUrl, messages)
                sendHello(ws, "Bob")
                val identity = readIdentity(messages)
                virtualId = identity.localId

                awaitCondition("Web client in state") {
                    conn.state.value.discoveredPeers.containsKey(virtualId)
                }
                // Block exits → server.stop() → handler's finally submits Left(virtualId)
            }

            assertFalse(
                conn.state.value.discoveredPeers.containsKey(virtualId),
                "Web client $virtualId should be removed from state after host shutdown"
            )
        }
    }

    /**
     * Simulates the production scenario: the event-processing coroutine (linearizer)
     * is cancelled before the web server's WebSocket handler finally block runs.
     *
     * In production, the event linearizer runs in the same scope as hostingWebClient.
     * When that scope is cancelled, the linearizer stops processing events. But the
     * Ktor handler's finally still tries to submit Left(virtualId) through the channel.
     * The event sits in the channel buffer, never processed.
     *
     * This test proves the bug by using a channel-backed PeerNetConnection whose
     * processing coroutine is cancelled before the server shuts down.
     */
    @Test
    fun webClientLeftSurvivesCancelledProcessingScope() {
        runBlocking {
            val processingJob = Job()
            val processingScope = CoroutineScope(Dispatchers.Default + processingJob)
            val conn = ChannelBackedPeerNetConnection("host-1", "Alice", processingScope)

            var virtualId: String? = null

            hostingWebClient(conn) { url ->
                val messages = LinkedBlockingQueue<String>()
                val wsUrl = url.replace("http://", "ws://") + "/ws"
                val ws = connectWebSocket(wsUrl, messages)
                sendHello(ws, "Bob")
                val identity = readIdentity(messages)
                virtualId = identity.localId

                awaitCondition("Web client in state") {
                    conn.state.value.discoveredPeers.containsKey(virtualId)
                }

                // Cancel the processing scope — simulates the production scenario
                // where withPeerNetConnection's linearizer is cancelled before
                // the web server's shutdown cleanup runs.
                processingScope.cancel()
            }

            // Give time for any buffered events
            Thread.sleep(1000)

            assertFalse(
                conn.state.value.discoveredPeers.containsKey(virtualId),
                "Web client $virtualId should be removed from state after host shutdown, " +
                        "even when the event-processing scope is cancelled"
            )
        }
    }

    /**
     * When a host shuts down, it must also clean up web client virtual IDs
     * so that other peers in the network don't retain ghost entries.
     *
     * In production, the host broadcasts Left(hostId) via broadcastDirect,
     * but never broadcasts Left(virtualId) for its web clients. Other peers
     * received the original Joined(virtualId) via gossip and will retain
     * the web client as a ghost peer forever.
     */
    @Test
    fun hostShutdownCleansUpWebClientOnOtherPeers() {
        runBlocking {
            val conn = createTestConnection("Alice")
            var virtualId: String? = null

            hostingWebClient(conn) { url ->
                val messages = LinkedBlockingQueue<String>()
                val wsUrl = url.replace("http://", "ws://") + "/ws"
                val ws = connectWebSocket(wsUrl, messages)
                sendHello(ws, "Bob")
                val identity = readIdentity(messages)
                virtualId = identity.localId

                awaitCondition("Web client in state") {
                    conn.state.value.discoveredPeers.containsKey(virtualId)
                }
            }

            // Simulate what another peer sees:
            // They received the Joined(virtualId) via gossip from the host.
            // When the host leaves, they should also remove the web client.
            val otherPeer = createTestConnection("Charlie")
            // Apply the events that would have been gossiped
            otherPeer.submitEvent(PeerEvent.Joined(PeerInfo(virtualId!!, "Bob", "", 0)))
            assertTrue(
                otherPeer.state.value.discoveredPeers.containsKey(virtualId),
                "Other peer should know about the web client (via gossip)"
            )

            // Now the host leaves — this is what broadcastDirect sends
            otherPeer.submitEvent(PeerEvent.Left("host-1"))

            // The web client's Left should ALSO be propagated
            assertFalse(
                otherPeer.state.value.discoveredPeers.containsKey(virtualId),
                "Web client $virtualId should be cleaned up on other peers when host leaves"
            )
        }
    }

    /**
     * Web clients are thin clients — they should NOT get their own solo lobby
     * when they join via Joined event. Only native peers host lobbies.
     *
     * Bug: the Joined handler creates a solo lobby for every peer, including
     * web clients. This causes the web client to appear in two lobbies (its
     * own solo lobby AND the host's lobby), showing as an "extra player."
     */
    @Test
    fun webClientJoinedDoesNotCreateSoloLobby() {
        runBlocking {
            val conn = createTestConnection("Alice")
            val virtualId = "web-host-1-abc12345"

            conn.submitEvent(
                PeerEvent.Joined(PeerInfo(id = virtualId, name = "Bob", address = "", port = 0))
            )

            // The web client should be in discoveredPeers
            assertTrue(
                conn.state.value.discoveredPeers.containsKey(virtualId),
                "Web client should be a discovered peer"
            )

            // But should NOT have its own solo lobby
            assertFalse(
                conn.state.value.lobbies.containsKey(virtualId),
                "Web client should NOT have a solo lobby (it's a thin client, not a host)"
            )
        }
    }

    @Test
    fun webPortChangedEventUpdatesState() {
        runBlocking {
            val conn = createTestConnection("Alice")
            conn.submitEvent(
                PeerEvent.WebPortChanged(
                    peerId = "host-1",
                    webPort = 8443,
                    webSecure = true,
                    platform = "Java 17",
                )
            )
            val peer = conn.state.value.discoveredPeers["host-1"]
            assertNotNull(peer)
            assertEquals(8443, peer.webPort)
            assertTrue(peer.webSecure)
            assertEquals("Java 17", peer.platform)
        }
    }

    // ---- Helpers ----

    private class TestPeerNetConnection(
        override val localId: String,
        localName: String,
    ) : PeerNetConnection {
        override val state = MutableStateFlow(PeerNetState())

        init {
            applyEvent(PeerEvent.Joined(PeerInfo(localId, localName, "test", 0)))
        }

        fun applyEvent(event: PeerEvent) {
            val (newState, timedEvent) = state.value.after(event)
            state.value = newState
            if (timedEvent != null && timedEvent.delay == Duration.ZERO) {
                applyEvent(timedEvent.event)
            }
        }

        override suspend fun submitEvent(event: PeerEvent): Boolean {
            var timedEvent: TimedEvent? = null
            state.update { current ->
                val (newState, te) = current.after(event)
                timedEvent = te
                newState
            }
            val te = timedEvent
            if (te != null && te.delay == Duration.ZERO) {
                submitEvent(te.event)
            }
            return true
        }
    }

    /**
     * A PeerNetConnection that processes events through a channel, mimicking
     * production behavior where the event linearizer reads from a channel.
     * When the [processingScope] is cancelled, events are no longer processed
     * (they sit in the channel buffer), just like in production.
     */
    private class ChannelBackedPeerNetConnection(
        override val localId: String,
        localName: String,
        processingScope: CoroutineScope,
    ) : PeerNetConnection {
        private val _state = MutableStateFlow(PeerNetState())
        override val state: StateFlow<PeerNetState> = _state
        private val eventChannel = Channel<PeerEvent>(Channel.BUFFERED)

        init {
            _state.update { it.after(PeerEvent.Joined(PeerInfo(localId, localName, "test", 0))).first }
            processingScope.launch {
                for (event in eventChannel) {
                    _state.update { it.after(event).first }
                }
            }
        }

        override suspend fun submitEvent(event: PeerEvent): Boolean {
            eventChannel.send(event)
            return true
        }
    }

    private fun httpGet(url: String): HttpResponse<String> {
        val client = HttpClient.newHttpClient()
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .GET()
            .build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun connectWebSocket(url: String, messages: LinkedBlockingQueue<String>): WebSocket {
        val client = HttpClient.newHttpClient()
        return client.newWebSocketBuilder()
            .buildAsync(URI.create(url), object : WebSocket.Listener {
                private val textBuffer = StringBuilder()

                override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*> {
                    textBuffer.append(data)
                    if (last) {
                        messages.add(textBuffer.toString())
                        textBuffer.clear()
                    }
                    webSocket.request(1)
                    return CompletableFuture.completedFuture(null)
                }

                override fun onOpen(webSocket: WebSocket) {
                    webSocket.request(1)
                }
            })
            .join()
    }

    private fun sendHello(ws: WebSocket, name: String = "Web Player") {
        val msg = json.encodeToString<WsMessage>(WsMessage.Hello(name))
        ws.sendText(msg, true).join()
    }

    private fun awaitCondition(description: String, timeoutMs: Long = 10_000, block: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (block()) return
            Thread.sleep(100)
        }
        fail("Timed out waiting for: $description")
    }

    private fun readIdentity(messages: LinkedBlockingQueue<String>): WsMessage.Identity {
        val raw = messages.poll(5, TimeUnit.SECONDS)
            ?: error("Timed out waiting for Identity message")
        val msg = json.decodeFromString<WsMessage>(raw)
        assertIs<WsMessage.Identity>(msg)
        return msg
    }
}
