package com.woutwerkman.net

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Host a web client server using Ktor Netty over plain HTTP.
 * Shared between JVM desktop and Android targets.
 *
 * Plain HTTP is used instead of HTTPS because:
 * - Self-signed certs cause cert-warning UX issues in browsers (especially Safari)
 * - LAN-only traffic doesn't need encryption
 * - Enables seamless WebSocket reconnection to other hosts without cert pinning
 */
internal suspend fun <T> nettyHostingWebClient(
    connection: PeerNetConnection,
    block: suspend CoroutineScope.(url: String) -> T,
): T = coroutineScope {
    val json = Json { ignoreUnknownKeys = true }
    val localIp = getLocalIpAddress()
    val resources = loadWebClientResources()
    val activeWebClients = ConcurrentHashMap.newKeySet<String>()

    val appModule: Application.() -> Unit = {
        install(io.ktor.server.websocket.WebSockets)
        routing {
            webSocket("/ws") {
                handleWebSocketSession(connection, json, activeWebClients)
            }
            get("/") {
                serveResource(call, resources, "index.html")
            }
            get("/{path...}") {
                val path = call.parameters.getAll("path")?.joinToString("/") ?: ""
                serveResource(call, resources, path)
            }
        }
    }

    val environment = applicationEnvironment {}
    val server = embeddedServer(Netty, environment, configure = {
        connector {
            port = 0
        }
    }, appModule).also { it.start(wait = false) }

    val actualPort = server.engine.resolvedConnectors().first().port
    val url = "http://$localIp:$actualPort"
    println("[WebHost] Serving at $url")

    try {
        block(url)
    } finally {
        server.stop(500, 1000)
        // Clean up any web clients that weren't removed during server shutdown.
        // The handler's finally block may have failed to submit Left events
        // because the connection's event processing was already cancelled.
        for (virtualId in activeWebClients) {
            try {
                connection.submitEvent(PeerEvent.Left(virtualId))
            } catch (_: Exception) { }
        }
        // Fallback: directly apply cleanup to state. When the event-processing
        // coroutine (linearizer) is already cancelled, submitEvent sends to a
        // buffered channel that no one reads. Apply Left events directly to
        // guarantee web client entries are removed.
        @Suppress("UNCHECKED_CAST")
        (connection.state as? MutableStateFlow<PeerNetState>)?.update { state ->
            activeWebClients.fold(state) { s, id ->
                if (s.discoveredPeers.containsKey(id)) s.after(PeerEvent.Left(id)).first else s
            }
        }
    }
}

private suspend fun DefaultWebSocketServerSession.handleWebSocketSession(
    connection: PeerNetConnection,
    json: Json,
    activeWebClients: MutableSet<String>,
) {
    // Client-first handshake: wait for Hello or Reconnect to determine session identity.
    val firstFrame = incoming.receive()
    val handshake = if (firstFrame is Frame.Text) {
        try { json.decodeFromString<WsMessage>(firstFrame.readText()) }
        catch (_: Exception) { null }
    } else null

    val virtualId: String
    val playerName: String
    when (handshake) {
        is WsMessage.Reconnect -> {
            virtualId = handshake.localId
            playerName = handshake.playerName
        }
        is WsMessage.Hello -> {
            val sessionId = UUID.randomUUID().toString().take(8)
            virtualId = "web-${connection.localId}-$sessionId"
            playerName = handshake.playerName
        }
        else -> {
            val sessionId = UUID.randomUUID().toString().take(8)
            virtualId = "web-${connection.localId}-$sessionId"
            playerName = "Web Player"
        }
    }

    activeWebClients.add(virtualId)

    connection.submitEvent(
        PeerEvent.Joined(PeerInfo(id = virtualId, name = playerName, address = "", port = 0))
    )

    val identityMsg = json.encodeToString<WsMessage>(
        WsMessage.Identity(localId = virtualId, hostId = connection.localId)
    )
    send(Frame.Text(identityMsg))

    coroutineScope {
        launch {
            connection.state.collectLatest { state ->
                val msg = json.encodeToString<WsMessage>(WsMessage.StateUpdate(state))
                try {
                    send(Frame.Text(msg))
                } catch (_: Exception) {
                    // Session closed
                }
            }
        }

        try {
            for (frame in incoming) {
                if (frame is Frame.Text) {
                    val text = frame.readText()
                    val msg = try {
                        json.decodeFromString<WsMessage>(text)
                    } catch (_: Exception) {
                        continue
                    }
                    when (msg) {
                        is WsMessage.EventSubmission -> connection.submitEvent(msg.event)
                        else -> {}
                    }
                }
            }
        } finally {
            try {
                connection.submitEvent(PeerEvent.Left(virtualId))
            } catch (_: Exception) {
                // Scope may be cancelled; cleanup handled by nettyHostingWebClient's finally
            }
            println("[WebHost] Web client $virtualId disconnected")
        }
    }
}

private suspend fun serveResource(call: ApplicationCall, resources: WebClientResources, path: String) {
    val bytes = resources.get(path)
    if (bytes == null) {
        call.respondText("Not Found", ContentType.Text.Plain, HttpStatusCode.NotFound)
        return
    }
    call.respondBytes(bytes, ContentType.parse(resources.contentType(path)))
}

private fun getLocalIpAddress(): String {
    try {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        var preferredIp: String? = null
        var fallbackIp: String? = null
        while (interfaces.hasMoreElements()) {
            val ni = interfaces.nextElement()
            if (ni.isLoopback || !ni.isUp) continue
            val name = ni.name.lowercase()
            if (name.startsWith("utun") || name.startsWith("tun") || name.startsWith("tap")) continue
            val addresses = ni.inetAddresses
            while (addresses.hasMoreElements()) {
                val addr = addresses.nextElement()
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    val ip = addr.hostAddress ?: continue
                    if (ip.startsWith("169.254.") || ip.startsWith("100.64.") || ip.startsWith("100.96.")) continue
                    if (name == "en0" || name == "eth0") {
                        preferredIp = ip
                    } else if (fallbackIp == null) {
                        fallbackIp = ip
                    }
                }
            }
        }
        return preferredIp ?: fallbackIp ?: "127.0.0.1"
    } catch (_: Exception) {
        return "127.0.0.1"
    }
}
