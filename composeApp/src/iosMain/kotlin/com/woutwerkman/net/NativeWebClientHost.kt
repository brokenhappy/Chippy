package com.woutwerkman.net

import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import platform.Foundation.NSLog
import platform.darwin.*
import platform.posix.*

/**
 * Host a web client server using POSIX TCP sockets with a coroutine-based accept loop.
 * Uses IPv6 dual-stack to accept both IPv4 and IPv6 connections.
 */
@OptIn(ExperimentalForeignApi::class)
internal suspend fun <T> nativeHostingWebClient(
    connection: PeerNetConnection,
    block: suspend CoroutineScope.(url: String) -> T,
): T = coroutineScope {
    val json = Json { ignoreUnknownKeys = true }
    val localIp = getLocalIpAddress()
    val resources = loadWebClientResources()
    val activeWebClients = mutableSetOf<String>()

    val serverFd = createListeningSocket()
    val port = getSocketPort(serverFd)
    val url = "http://$localIp:$port"
    NSLog("[WebHost-iOS] TCP server listening on port $port (fd=$serverFd)")
    NSLog("[WebHost-iOS] Serving at $url")

    launch(Dispatchers.Default) {
        acceptLoop(serverFd, connection, json, resources, activeWebClients)
    }

    try {
        block(url)
    } finally {
        close(serverFd)
        for (virtualId in activeWebClients) {
            try {
                connection.submitEvent(PeerEvent.Left(virtualId))
            } catch (_: Exception) { }
        }
        @Suppress("UNCHECKED_CAST")
        (connection.state as? MutableStateFlow<PeerNetState>)?.update { state ->
            activeWebClients.fold(state) { s, id ->
                if (s.discoveredPeers.containsKey(id)) s.after(PeerEvent.Left(id)).first else s
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private suspend fun acceptLoop(
    serverFd: Int,
    connection: PeerNetConnection,
    json: Json,
    resources: WebClientResources,
    activeWebClients: MutableSet<String>,
): Unit = coroutineScope {
    while (isActive) {
        val clientFd = memScoped {
            val clientAddr = alloc<sockaddr_in6>()
            val addrLen = alloc<socklen_tVar>()
            addrLen.value = sizeOf<sockaddr_in6>().toUInt()
            accept(serverFd, clientAddr.ptr.reinterpret(), addrLen.ptr)
        }
        if (clientFd < 0) break
        NSLog("[WebHost-iOS] Accepted connection (fd=$clientFd)")
        launch { handleConnection(clientFd, connection, json, resources, activeWebClients) }
    }
}

private suspend fun handleConnection(
    fd: Int,
    connection: PeerNetConnection,
    json: Json,
    resources: WebClientResources,
    activeWebClients: MutableSet<String>,
) {
    try {
        val requestBytes = posixReadAvailable(fd) ?: return
        val request = requestBytes.decodeToString()
        val firstLine = request.substringBefore("\r\n")
        val path = firstLine.split(" ").getOrNull(1) ?: "/"

        when {
            path == "/ws" && request.contains("Upgrade: websocket", ignoreCase = true) -> {
                handleWebSocketUpgrade(fd, request, connection, json, activeWebClients)
            }
            else -> {
                val filePath = if (path == "/") "index.html" else path.trimStart('/')
                val body = resources.get(filePath)
                if (body != null) {
                    sendHttpResponse(fd, 200, resources.contentType(filePath), body)
                } else {
                    sendHttpResponse(fd, 404, "text/plain", "Not Found".encodeToByteArray())
                }
                close(fd)
            }
        }
    } catch (e: Exception) {
        NSLog("[WebHost-iOS] Connection error: ${e.message}")
        close(fd)
    }
}

private fun sendHttpResponse(fd: Int, status: Int, contentType: String, body: ByteArray) {
    val statusText = when (status) {
        200 -> "OK"
        404 -> "Not Found"
        else -> "Error"
    }
    val header = "HTTP/1.1 $status $statusText\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n" +
            "\r\n"
    posixSendAll(fd, header.encodeToByteArray() + body)
}

// ---- WebSocket ----

private suspend fun handleWebSocketUpgrade(
    fd: Int,
    request: String,
    connection: PeerNetConnection,
    json: Json,
    activeWebClients: MutableSet<String>,
) {
    val key = extractHeader(request, "Sec-WebSocket-Key")
    if (key == null) {
        close(fd)
        return
    }

    val acceptKey = computeWebSocketAccept(key)
    val response = "HTTP/1.1 101 Switching Protocols\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: $acceptKey\r\n" +
            "\r\n"
    posixSendAll(fd, response.encodeToByteArray())

    handleWebSocketSession(fd, connection, json, activeWebClients)
}

private suspend fun handleWebSocketSession(
    fd: Int,
    connection: PeerNetConnection,
    json: Json,
    activeWebClients: MutableSet<String>,
) {
    // Client-first handshake: read Hello or Reconnect to determine session identity.
    val firstFrame = readWebSocketFrame(fd)
    val handshake = if (firstFrame != null && firstFrame.opcode == 0x01) {
        try { json.decodeFromString<WsMessage>(firstFrame.payload.decodeToString()) }
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
            virtualId = "web-${connection.localId}-${randomHex(8)}"
            playerName = handshake.playerName
        }
        else -> {
            virtualId = "web-${connection.localId}-${randomHex(8)}"
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
    sendWebSocketFrame(fd, identityMsg)

    coroutineScope {
        launch {
            connection.state.collectLatest { state ->
                val msg = json.encodeToString<WsMessage>(WsMessage.StateUpdate(state))
                try {
                    sendWebSocketFrame(fd, msg)
                } catch (_: Exception) { /* session closed */ }
            }
        }

        try {
            while (isActive) {
                val frame = readWebSocketFrame(fd) ?: break
                if (frame.opcode == 0x08) break // close
                if (frame.opcode == 0x09) { // ping → pong
                    sendWebSocketRawFrame(fd, 0x0A, frame.payload)
                    continue
                }
                if (frame.opcode == 0x01) { // text
                    val text = frame.payload.decodeToString()
                    val msg = try {
                        json.decodeFromString<WsMessage>(text)
                    } catch (_: Exception) { continue }
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
                // Scope may be cancelled; cleanup handled by nativeHostingWebClient's finally
            }
            close(fd)
            NSLog("[WebHost-iOS] Web client $virtualId disconnected")
        }
    }
}

// ---- WebSocket frame I/O (RFC 6455) ----

private data class WsFrame(val opcode: Int, val payload: ByteArray)

private fun readWebSocketFrame(fd: Int): WsFrame? {
    val header = posixReadExact(fd, 2) ?: return null

    val opcode = header[0].toInt() and 0x0F
    val masked = (header[1].toInt() and 0x80) != 0
    var payloadLen = (header[1].toInt() and 0x7F).toLong()

    if (payloadLen == 126L) {
        val ext = posixReadExact(fd, 2) ?: return null
        payloadLen = ((ext[0].toInt() and 0xFF) shl 8 or (ext[1].toInt() and 0xFF)).toLong()
    } else if (payloadLen == 127L) {
        val ext = posixReadExact(fd, 8) ?: return null
        payloadLen = 0L
        for (i in 0..7) payloadLen = (payloadLen shl 8) or (ext[i].toLong() and 0xFF)
    }

    val maskKey = if (masked) posixReadExact(fd, 4) else null
    if (masked && maskKey == null) return null

    if (payloadLen > 1_000_000) return null

    val payload = if (payloadLen > 0) {
        posixReadExact(fd, payloadLen.toInt()) ?: return null
    } else {
        ByteArray(0)
    }

    if (maskKey != null) {
        for (i in payload.indices) {
            payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
        }
    }

    return WsFrame(opcode, payload)
}

private fun sendWebSocketFrame(fd: Int, text: String) {
    sendWebSocketRawFrame(fd, 0x81, text.encodeToByteArray())
}

private fun sendWebSocketRawFrame(fd: Int, firstByte: Int, payload: ByteArray) {
    val len = payload.size
    val header: ByteArray = if (len < 126) {
        byteArrayOf(firstByte.toByte(), len.toByte())
    } else if (len < 65536) {
        byteArrayOf(
            firstByte.toByte(),
            126.toByte(),
            (len shr 8 and 0xFF).toByte(),
            (len and 0xFF).toByte()
        )
    } else {
        ByteArray(10).also { h ->
            h[0] = firstByte.toByte()
            h[1] = 127.toByte()
            for (i in 0..7) {
                h[2 + i] = (len.toLong() shr ((7 - i) * 8) and 0xFF).toByte()
            }
        }
    }
    posixSendAll(fd, header + payload)
}

// ---- POSIX I/O helpers ----

@OptIn(ExperimentalForeignApi::class)
private fun posixReadExact(fd: Int, length: Int): ByteArray? {
    val buffer = ByteArray(length)
    var offset = 0
    buffer.usePinned { pinned ->
        while (offset < length) {
            val n = read(fd, pinned.addressOf(offset), (length - offset).toULong())
            if (n <= 0) return null
            offset += n.toInt()
        }
    }
    return buffer
}

@OptIn(ExperimentalForeignApi::class)
private fun posixReadAvailable(fd: Int): ByteArray? {
    val buffer = ByteArray(65536)
    val n = buffer.usePinned { pinned ->
        read(fd, pinned.addressOf(0), buffer.size.toULong())
    }
    if (n <= 0) return null
    return buffer.copyOf(n.toInt())
}

@OptIn(ExperimentalForeignApi::class)
private fun posixSendAll(fd: Int, data: ByteArray) {
    var offset = 0
    data.usePinned { pinned ->
        while (offset < data.size) {
            val n = write(fd, pinned.addressOf(offset), (data.size - offset).toULong())
            if (n < 0) return
            offset += n.toInt()
        }
    }
}

// ---- Socket setup ----

@OptIn(ExperimentalForeignApi::class)
private fun createListeningSocket(): Int {
    val fd = socket(AF_INET6, SOCK_STREAM, IPPROTO_TCP)
    check(fd >= 0) { "Failed to create socket: errno=$errno" }

    memScoped {
        val yes = alloc<IntVar>().apply { value = 1 }
        setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, yes.ptr, sizeOf<IntVar>().toUInt())

        val no = alloc<IntVar>().apply { value = 0 }
        setsockopt(fd, IPPROTO_IPV6, IPV6_V6ONLY, no.ptr, sizeOf<IntVar>().toUInt())

        val addr = alloc<sockaddr_in6>()
        addr.sin6_len = sizeOf<sockaddr_in6>().toUByte()
        addr.sin6_family = AF_INET6.toUByte()
        addr.sin6_port = 0u

        check(bind(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in6>().toUInt()) == 0) {
            "Bind failed: errno=$errno"
        }
        check(listen(fd, SOMAXCONN) == 0) {
            "Listen failed: errno=$errno"
        }
    }
    return fd
}

@OptIn(ExperimentalForeignApi::class)
private fun getSocketPort(fd: Int): Int = memScoped {
    val addr = alloc<sockaddr_in6>()
    val len = alloc<socklen_tVar>().apply { value = sizeOf<sockaddr_in6>().toUInt() }
    getsockname(fd, addr.ptr.reinterpret(), len.ptr)
    val raw = addr.sin6_port.toInt()
    ((raw and 0xFF) shl 8) or ((raw shr 8) and 0xFF)
}

// ---- Helpers ----

private fun extractHeader(request: String, name: String): String? {
    for (line in request.split("\r\n")) {
        if (line.startsWith("$name:", ignoreCase = true)) {
            return line.substringAfter(":").trim()
        }
    }
    return null
}

@OptIn(ExperimentalForeignApi::class)
private fun computeWebSocketAccept(key: String): String {
    val magic = key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    val data = magic.encodeToByteArray()

    val digest = UByteArray(20)
    data.usePinned { pinned ->
        digest.usePinned { digestPinned ->
            platform.CoreCrypto.CC_SHA1(
                pinned.addressOf(0),
                data.size.toUInt(),
                digestPinned.addressOf(0)
            )
        }
    }

    return base64Encode(digest.toByteArray())
}

private fun base64Encode(data: ByteArray): String {
    val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    val sb = StringBuilder()
    var i = 0
    while (i < data.size) {
        val b0 = data[i].toInt() and 0xFF
        val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xFF else 0
        val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xFF else 0
        sb.append(chars[(b0 shr 2) and 0x3F])
        sb.append(chars[((b0 shl 4) or (b1 shr 4)) and 0x3F])
        sb.append(if (i + 1 < data.size) chars[((b1 shl 2) or (b2 shr 6)) and 0x3F] else '=')
        sb.append(if (i + 2 < data.size) chars[b2 and 0x3F] else '=')
        i += 3
    }
    return sb.toString()
}

private fun randomHex(length: Int): String {
    val chars = "0123456789abcdef"
    return (1..length).map { chars.random() }.joinToString("")
}

