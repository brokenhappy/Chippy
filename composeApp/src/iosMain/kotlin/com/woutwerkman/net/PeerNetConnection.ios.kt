package com.woutwerkman.net

import kotlin.time.Duration.Companion.milliseconds
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import platform.Foundation.*
import platform.darwin.*
import platform.posix.*
import kotlin.random.Random

private const val MESSAGE_PORT = 47391

// Internal protocol prefixes - hidden from consumers
private const val HANDSHAKE_PREFIX = "_PN_HS_"
private const val HANDSHAKE_HELLO = "${HANDSHAKE_PREFIX}HELLO|"
private const val HANDSHAKE_ACK = "${HANDSHAKE_PREFIX}ACK|"

@OptIn(ExperimentalForeignApi::class)
internal actual suspend fun <T> withRawPeerNetConnectionImpl(
    config: PeerNetConfig,
    block: suspend CoroutineScope.(RawPeerNetConnection) -> T
): T = coroutineScope {
    val incoming = Channel<RawPeerMessage>(Channel.BUFFERED)
    val outgoing = Channel<PeerCommand>(Channel.BUFFERED)

    val peerId = "ios-${currentTimeMillis().toString(36)}-${Random.nextLong().toString(36)}"
    val impl = IosPeerNetConnectionImpl(
        config = config,
        peerId = peerId,
        incoming = incoming,
        scope = this
    )

    try {
        impl.start()
        val connection = RawPeerNetConnection(peerId, incoming, outgoing)

        // Handle outgoing commands
        val commandJob = launch {
            for (command in outgoing) {
                impl.handleCommand(command)
            }
        }

        try {
            block(connection)
        } finally {
            commandJob.cancel()
        }
    } finally {
        impl.stop()
        incoming.close()
        outgoing.close()
    }
}

private fun currentTimeMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

private fun htons(value: UShort): UShort =
    ((value.toInt() and 0xFF) shl 8 or (value.toInt() shr 8 and 0xFF)).toUShort()

/**
 * Tracks the state of a discovered peer through the handshake process.
 */
private data class PeerState(
    val info: PeerInfo,
    var weSeeThemViaDiscovery: Boolean = false,
    var weSentHello: Boolean = false,
    var theyAckedUs: Boolean = false,
    var weAckedThem: Boolean = false,
    var isJoined: Boolean = false
)

@OptIn(ExperimentalForeignApi::class)
private class IosPeerNetConnectionImpl(
    private val config: PeerNetConfig,
    private val peerId: String,
    private val incoming: Channel<RawPeerMessage>,
    private val scope: CoroutineScope
) {
    private val serviceType = "_${config.serviceName}._udp."
    private val peerStates = mutableMapOf<String, PeerState>()

    private var netService: NSNetService? = null
    private var netServiceBrowser: NSNetServiceBrowser? = null
    private var browserDelegate: NetServiceBrowserDelegate? = null
    private var publishDelegate: NetServicePublishDelegate? = null
    private val resolverDelegates = mutableListOf<NetServiceResolveDelegate>()

    private var udpSocket: Int = -1
    private var receiveJob: Job? = null
    private var handshakeJob: Job? = null
    private var localAddress: String = "0.0.0.0"
    private var boundPort: Int = MESSAGE_PORT

    suspend fun start() {
        NSLog("[PeerNet-$peerId] Starting peer discovery")
        localAddress = getLocalIpAddress()
        NSLog("[PeerNet-$peerId] Local IP: $localAddress")

        createUdpSocket()

        withContext(Dispatchers.Main) {
            startBonjour()
        }

        // Periodic handshake maintenance and run loop processing
        handshakeJob = scope.launch {
            while (isActive) {
                // Process run loop to let Bonjour callbacks fire (50ms window for better responsiveness)
                withContext(Dispatchers.Main) {
                    NSRunLoop.currentRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.05))
                }

                delay(50.milliseconds)

                peerStates.values.toList().forEach { state ->
                    if (state.weSeeThemViaDiscovery && !state.isJoined) {
                        sendHandshakeHello(state.info)
                        state.weSentHello = true
                    }
                }
            }
        }
    }

    private fun createUdpSocket() {
        udpSocket = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        if (udpSocket < 0) {
            NSLog("[PeerNet-$peerId] Failed to create UDP socket")
            return
        }

        memScoped {
            val reuseAddr = alloc<IntVar>()
            reuseAddr.value = 1
            setsockopt(udpSocket, SOL_SOCKET, SO_REUSEADDR, reuseAddr.ptr, sizeOf<IntVar>().toUInt())
            setsockopt(udpSocket, SOL_SOCKET, SO_REUSEPORT, reuseAddr.ptr, sizeOf<IntVar>().toUInt())

            val addr = alloc<sockaddr_in>()
            addr.sin_family = AF_INET.toUByte()
            addr.sin_port = htons(MESSAGE_PORT.toUShort())
            addr.sin_addr.s_addr = INADDR_ANY

            var bindResult = bind(udpSocket, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().toUInt())
            if (bindResult < 0) {
                NSLog("[PeerNet-$peerId] Failed to bind to port $MESSAGE_PORT, falling back to random port")
                addr.sin_port = htons(0.toUShort())
                bindResult = bind(udpSocket, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().toUInt())
                if (bindResult < 0) {
                    NSLog("[PeerNet-$peerId] Failed to bind UDP socket on fallback port")
                    close(udpSocket)
                    udpSocket = -1
                    return
                }
            }

            // Read the actual bound port
            val boundAddr = alloc<sockaddr_in>()
            val addrLen = alloc<UIntVar>()
            addrLen.value = sizeOf<sockaddr_in>().toUInt()
            getsockname(udpSocket, boundAddr.ptr.reinterpret(), addrLen.ptr)
            boundPort = htons(boundAddr.sin_port).toInt()
        }

        val flags = fcntl(udpSocket, F_GETFL, 0)
        fcntl(udpSocket, F_SETFL, flags or O_NONBLOCK)

        NSLog("[PeerNet-$peerId] Listening on port $boundPort")

        receiveJob = scope.launch(Dispatchers.Default) {
            receiveLoop()
        }
    }

    private suspend fun receiveLoop() {
        val buffer = ByteArray(4096)
        var loopCount = 0

        while (scope.isActive && udpSocket >= 0) {
            loopCount++
            if (loopCount % 100 == 0) {
                NSLog("[PeerNet-$peerId] Receive loop iteration $loopCount, socket=$udpSocket")
            }

            memScoped {
                val senderAddr = alloc<sockaddr_in>()
                val addrLen = alloc<UIntVar>()
                addrLen.value = sizeOf<sockaddr_in>().toUInt()

                buffer.usePinned { pinned ->
                    val bytesRead = recvfrom(
                        udpSocket,
                        pinned.addressOf(0),
                        buffer.size.toULong(),
                        0,
                        senderAddr.ptr.reinterpret(),
                        addrLen.ptr
                    )

                    if (bytesRead > 0) {
                        val senderIp = inet_ntoa(senderAddr.sin_addr.readValue())?.toKString() ?: "unknown"
                        val data = buffer.copyOf(bytesRead.toInt())
                        val message = data.decodeToString()
                        NSLog("[PeerNet-$peerId] Received $bytesRead bytes from $senderIp: ${message.take(50)}...")

                        // Parse: "senderId:payload"
                        val separatorIndex = message.indexOf(':')
                        if (separatorIndex > 0) {
                            val fromPeerId = message.substring(0, separatorIndex)
                            NSLog("[PeerNet-$peerId] Parsed fromPeerId=$fromPeerId, myPeerId=$peerId")
                            if (fromPeerId != peerId) {
                                val payload = message.substring(separatorIndex + 1)
                                NSLog("[PeerNet-$peerId] Payload starts with: ${payload.take(30)}")

                                when {
                                    payload.startsWith(HANDSHAKE_HELLO) -> {
                                        handleHelloReceived(fromPeerId, payload, senderIp)
                                    }
                                    payload.startsWith(HANDSHAKE_ACK) -> {
                                        handleAckReceived(fromPeerId)
                                    }
                                    else -> {
                                        // Forward if we've seen this peer at all (don't require full
                                        // handshake, as linearization state may arrive before handshake completes)
                                        val state = peerStates[fromPeerId]
                                        if (state != null) {
                                            scope.launch {
                                                incoming.send(RawPeerMessage.Received(fromPeerId, payload.encodeToByteArray()))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            delay(10.milliseconds)
        }
    }

    private fun handleHelloReceived(fromPeerId: String, payload: String, fromAddress: String) {
        val helloData = payload.removePrefix(HANDSHAKE_HELLO)
        val parts = helloData.split("|")
        val pName = parts.getOrNull(0) ?: "Unknown"
        var pAddr = parts.getOrNull(2) ?: fromAddress
        val pPort = parts.getOrNull(3)?.toIntOrNull() ?: MESSAGE_PORT

        // Map emulator internal IP to sender IP
        if (pAddr == "10.0.2.15") {
            pAddr = fromAddress
        }

        val peerInfo = PeerInfo(id = fromPeerId, name = pName, address = pAddr, port = pPort)

        val state = peerStates.getOrPut(fromPeerId) {
            NSLog("[PeerNet-$peerId] Received HELLO from new peer: $pName ($fromPeerId) at $pAddr:$pPort")
            PeerState(info = peerInfo)
        }
        state.weSeeThemViaDiscovery = true

        // Always send ACK in reply to HELLO — the peer may have missed our earlier ACK
        sendHandshakeAck(peerInfo)
        state.weAckedThem = true

        checkAndEmitJoined(state)
    }

    private fun handleAckReceived(fromPeerId: String) {
        val state = peerStates[fromPeerId] ?: return
        NSLog("[PeerNet-$peerId] Received ACK from: $fromPeerId")
        state.theyAckedUs = true
        checkAndEmitJoined(state)
    }

    private fun checkAndEmitJoined(state: PeerState) {
        if (state.weSeeThemViaDiscovery && state.theyAckedUs && !state.isJoined) {
            state.isJoined = true
            NSLog("[PeerNet-$peerId] Peer JOINED: ${state.info.name} (${state.info.id})")
            scope.launch {
                incoming.send(RawPeerMessage.Event.Connected(state.info))
            }
        }
    }

    private fun startBonjour() {
        val serviceName = "${config.displayName}|$peerId|$localAddress|$boundPort"
        NSLog("[PeerNet-$peerId] Registering service: $serviceName")
        NSLog("[PeerNet-$peerId] Service type: $serviceType")

        publishDelegate = NetServicePublishDelegate(peerId)

        netService = NSNetService(
            domain = "local.",
            type = serviceType,
            name = serviceName,
            port = boundPort
        )
        netService?.delegate = publishDelegate
        NSLog("[PeerNet-$peerId] Calling publish()...")
        netService?.publish()
        netService?.scheduleInRunLoop(NSRunLoop.currentRunLoop, forMode = NSDefaultRunLoopMode)
        NSLog("[PeerNet-$peerId] Service scheduled in run loop")

        browserDelegate = NetServiceBrowserDelegate(
            peerId = peerId,
            onServiceFound = { service ->
                NSLog("[PeerNet-$peerId] Found service: ${service.name}")
                val resolveDelegate = NetServiceResolveDelegate(peerId) { resolvedService ->
                    handleServiceResolved(resolvedService)
                }
                resolverDelegates.add(resolveDelegate)
                service.delegate = resolveDelegate
                service.resolveWithTimeout(5.0)
            }
        )

        netServiceBrowser = NSNetServiceBrowser()
        netServiceBrowser?.delegate = browserDelegate
        netServiceBrowser?.scheduleInRunLoop(NSRunLoop.currentRunLoop, forMode = NSDefaultRunLoopMode)
        NSLog("[PeerNet-$peerId] Starting browser search for type: $serviceType")
        netServiceBrowser?.searchForServicesOfType(serviceType, inDomain = "local.")
        NSLog("[PeerNet-$peerId] Bonjour setup complete")
    }

    private fun handleServiceResolved(service: NSNetService) {
        val serviceName = service.name
        val parts = serviceName.split("|")
        if (parts.size >= 3) {
            val peerName = parts[0]
            val pId = parts[1]
            val peerAddr = parts[2]
            val peerPort = parts.getOrNull(3)?.toIntOrNull() ?: MESSAGE_PORT

            if (pId == peerId) return

            val peerInfo = PeerInfo(id = pId, name = peerName, address = peerAddr, port = peerPort)

            val state = peerStates.getOrPut(pId) {
                NSLog("[PeerNet-$peerId] Discovered via mDNS: $peerName ($pId) at $peerAddr:$peerPort")
                PeerState(info = peerInfo)
            }
            state.weSeeThemViaDiscovery = true

            // Send HELLO
            sendHandshakeHello(peerInfo)
            state.weSentHello = true
        }
    }

    private fun sendHandshakeHello(peer: PeerInfo) {
        val payload = "$HANDSHAKE_HELLO${config.displayName}|$peerId|$localAddress|$boundPort"
        NSLog("[PeerNet-$peerId] Sending HELLO to ${peer.name} at ${peer.address}:${peer.port}")
        sendUdp(peer.address, peer.port, "$peerId:$payload")
    }

    private fun sendHandshakeAck(peer: PeerInfo) {
        val payload = "$HANDSHAKE_ACK$peerId"
        sendUdp(peer.address, peer.port, "$peerId:$payload")
    }

    fun handleCommand(command: PeerCommand) {
        when (command) {
            is PeerCommand.SendTo -> {
                val state = peerStates[command.peerId]
                if (state != null) {
                    val payload = "$peerId:${command.payload.decodeToString()}"
                    sendUdp(state.info.address, state.info.port, payload)
                }
            }
            is PeerCommand.Broadcast -> {
                peerStates.values.forEach { state ->
                    val payload = "$peerId:${command.payload.decodeToString()}"
                    sendUdp(state.info.address, state.info.port, payload)
                }
            }
        }
    }

    private fun sendUdp(address: String, port: Int, message: String) {
        memScoped {
            val sendSocket = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
            if (sendSocket < 0) return

            val destAddr = alloc<sockaddr_in>()
            destAddr.sin_family = AF_INET.toUByte()
            destAddr.sin_port = htons(port.toUShort())
            inet_pton(AF_INET, address, destAddr.sin_addr.ptr)

            val data = message.encodeToByteArray()
            data.usePinned { pinned ->
                sendto(
                    sendSocket,
                    pinned.addressOf(0),
                    data.size.toULong(),
                    0,
                    destAddr.ptr.reinterpret(),
                    sizeOf<sockaddr_in>().toUInt()
                )
            }

            close(sendSocket)
        }
    }

    suspend fun stop() {
        NSLog("[PeerNet-$peerId] Stopping")
        receiveJob?.cancel()
        handshakeJob?.cancel()
        if (udpSocket >= 0) {
            close(udpSocket)
            udpSocket = -1
        }
        withContext(Dispatchers.Main) {
            netServiceBrowser?.stop()
            netService?.stop()
        }
    }

    private fun getLocalIpAddress(): String = com.woutwerkman.net.getLocalIpAddress()
}

// Delegate classes
private class NetServiceBrowserDelegate(
    private val peerId: String,
    private val onServiceFound: (NSNetService) -> Unit
) : NSObject(), NSNetServiceBrowserDelegateProtocol {

    override fun netServiceBrowser(browser: NSNetServiceBrowser, didFindService: NSNetService, moreComing: Boolean) {
        NSLog("[PeerNet-$peerId] Browser found service: ${didFindService.name}, moreComing=$moreComing")
        if (!didFindService.name.contains(peerId)) {
            onServiceFound(didFindService)
        } else {
            NSLog("[PeerNet-$peerId] Ignoring own service")
        }
    }

    override fun netServiceBrowser(browser: NSNetServiceBrowser, didNotSearch: Map<Any?, *>) {
        NSLog("[PeerNet-$peerId] Browser did not search: $didNotSearch")
    }

    override fun netServiceBrowserWillSearch(browser: NSNetServiceBrowser) {
        NSLog("[PeerNet-$peerId] Browser will search")
    }

    override fun netServiceBrowserDidStopSearch(browser: NSNetServiceBrowser) {
        NSLog("[PeerNet-$peerId] Browser stopped search")
    }
}

private class NetServicePublishDelegate(private val peerId: String) : NSObject(), NSNetServiceDelegateProtocol {
    override fun netServiceDidPublish(sender: NSNetService) {
        NSLog("[PeerNet-$peerId] Service published: ${sender.name}")
    }

    override fun netService(sender: NSNetService, didNotPublish: Map<Any?, *>) {
        NSLog("[PeerNet-$peerId] Service did NOT publish: $didNotPublish")
    }
}

private class NetServiceResolveDelegate(
    private val peerId: String,
    private val onResolved: (NSNetService) -> Unit
) : NSObject(), NSNetServiceDelegateProtocol {

    override fun netServiceDidResolveAddress(sender: NSNetService) {
        onResolved(sender)
    }

    override fun netService(sender: NSNetService, didNotResolve: Map<Any?, *>) {
        NSLog("[PeerNet-$peerId] Service did not resolve: $didNotResolve")
    }
}
