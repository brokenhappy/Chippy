package com.woutwerkman.net

import io.ktor.http.*
import io.ktor.network.tls.certificates.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.UUID

/**
 * Host a web client server using Ktor Netty with self-signed TLS.
 * Shared between JVM desktop and Android targets.
 */
internal suspend fun <T> nettyHostingWebClient(
    connection: PeerNetConnection,
    block: suspend CoroutineScope.(url: String) -> T,
): T = coroutineScope {
    val json = Json { ignoreUnknownKeys = true }
    val localIp = getLocalIpAddress()

    val keyStorePassword = "chippy-web"
    val keyStore = buildKeyStore {
        certificate("chippy") {
            password = keyStorePassword
            domains = listOf("localhost", localIp)
            ipAddresses = listOf(InetAddress.getByName(localIp), InetAddress.getByName("127.0.0.1"))
        }
    }

    val appModule: Application.() -> Unit = {
        install(io.ktor.server.websocket.WebSockets)
        routing {
            get("/") {
                call.respondText(WEB_CLIENT_HTML, ContentType.Text.Html)
            }
            webSocket("/ws") {
                handleWebSocketSession(connection, json)
            }
        }
    }

    val environment = applicationEnvironment {}
    val server = embeddedServer(Netty, environment, configure = {
        sslConnector(
            keyStore = keyStore,
            keyAlias = "chippy",
            keyStorePassword = { keyStorePassword.toCharArray() },
            privateKeyPassword = { keyStorePassword.toCharArray() },
        ) {
            port = 0
        }
    }, appModule).also { it.start(wait = false) }

    val actualPort = server.engine.resolvedConnectors().first().port
    val url = "https://$localIp:$actualPort"
    println("[WebHost] Serving at $url")

    try {
        block(url)
    } finally {
        server.stop(500, 1000)
    }
}

private suspend fun DefaultWebSocketServerSession.handleWebSocketSession(
    connection: PeerNetConnection,
    json: Json,
) {
    val sessionId = UUID.randomUUID().toString().take(8)
    val virtualId = "web-${connection.localId}-$sessionId"

    connection.submitEvent(
        PeerEvent.Joined(PeerInfo(id = virtualId, name = "Web Player", address = "", port = 0))
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
                        is WsMessage.Reconnect -> { /* TODO Phase 2 */ }
                        else -> {}
                    }
                }
            }
        } finally {
            connection.submitEvent(PeerEvent.Left(virtualId))
            println("[WebHost] Web client $virtualId disconnected")
        }
    }
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
