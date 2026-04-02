package com.woutwerkman.net

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
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
                assertTrue(url.startsWith("https://"), "URL should use HTTPS: $url")

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
                val wsUrl = url.replace("https://", "wss://") + "/ws"
                val ws = connectWebSocket(wsUrl, messages)

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
                val wsUrl = url.replace("https://", "wss://") + "/ws"
                val ws = connectWebSocket(wsUrl, messages)

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
    fun websocketClientCanSubmitEvents() {
        runBlocking {
            val conn = createTestConnection("Alice")
            hostingWebClient(conn) { url ->
                val messages = LinkedBlockingQueue<String>()
                val wsUrl = url.replace("https://", "wss://") + "/ws"
                val ws = connectWebSocket(wsUrl, messages)

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
                val wsUrl = url.replace("https://", "wss://") + "/ws"

                val messages1 = LinkedBlockingQueue<String>()
                val messages2 = LinkedBlockingQueue<String>()
                val ws1 = connectWebSocket(wsUrl, messages1)
                val ws2 = connectWebSocket(wsUrl, messages2)

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
                val wsUrl = url.replace("https://", "wss://") + "/ws"
                val ws = connectWebSocket(wsUrl, messages)

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
            applyEvent(event)
            return true
        }
    }

    private fun trustAllHttpClient(): HttpClient {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAll, SecureRandom())
        return HttpClient.newBuilder()
            .sslContext(sslContext)
            .build()
    }

    private fun httpGet(url: String): HttpResponse<String> {
        val client = trustAllHttpClient()
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .GET()
            .build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun connectWebSocket(url: String, messages: LinkedBlockingQueue<String>): WebSocket {
        val client = trustAllHttpClient()
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
