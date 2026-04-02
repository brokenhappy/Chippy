package com.woutwerkman.net

import kotlin.time.Duration.Companion.milliseconds
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
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
): T {
    val incoming = Channel<RawPeerMessage>(Channel.BUFFERED)
    val outgoing = Channel<PeerCommand>(Channel.BUFFERED)
    val peerId = "ios-${currentTimeMillis().toString(36)}-${Random.nextLong().toString(36)}"

    return withIosPeerTransport(
        config = config,
        peerId = peerId,
        incomingChannel = incoming,
        outgoingChannel = outgoing,
    ) { broadcastFn ->
        val connection = RawPeerNetConnection(peerId, incoming, outgoing, broadcastFn)
        try {
            block(connection)
        } finally {
            incoming.close()
            outgoing.close()
        }
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

/**
 * Events from discovery callbacks (Bonjour), bridged into the coroutine world.
 */
private sealed class DiscoveryEvent {
    data class ServiceResolved(val service: NSNetService) : DiscoveryEvent()
    data class PeerJoined(val state: PeerState) : DiscoveryEvent()
}

/**
 * Resource function that sets up the iOS peer transport, runs [block] with a
 * broadcastDirect function, and tears down all resources when [block] completes.
 */
@OptIn(ExperimentalForeignApi::class)
private suspend fun <T> withIosPeerTransport(
    config: PeerNetConfig,
    peerId: String,
    incomingChannel: SendChannel<RawPeerMessage>,
    outgoingChannel: ReceiveChannel<PeerCommand>,
    block: suspend CoroutineScope.((ByteArray) -> Unit) -> T,
): T {
    val serviceType = "_${config.serviceName}._udp."
    val peerStates = mutableMapOf<String, PeerState>()
    val discoveryEvents = Channel<DiscoveryEvent>(Channel.BUFFERED)

    NSLog("[PeerNet-$peerId] Starting peer discovery")
    val localAddress = getLocalIpAddress()
    NSLog("[PeerNet-$peerId] Local IP: $localAddress")

    val udpSocket = createUdpSocket(peerId)
    val boundPort = getSocketBoundPort(udpSocket)
    NSLog("[PeerNet-$peerId] Listening on port $boundPort")

    val broadcastFn: (ByteArray) -> Unit = { payload ->
        val message = "$peerId:${payload.decodeToString()}"
        peerStates.values.forEach { state ->
            sendUdp(state.info.address, state.info.port, message)
        }
    }

    // Set non-blocking mode for receive loop
    val flags = fcntl(udpSocket, F_GETFL, 0)
    fcntl(udpSocket, F_SETFL, flags or O_NONBLOCK)

    // Start Bonjour on Main thread (required by NSNetService)
    val resolverDelegates = mutableListOf<NetServiceResolveDelegate>()
    val bonjourState = withContext(Dispatchers.Main) {
        startBonjour(config, peerId, localAddress, boundPort, serviceType, discoveryEvents, resolverDelegates)
    }

    try {
        return coroutineScope {
            // Infrastructure coroutines
            launch(Dispatchers.Default) {
                receiveLoop(udpSocket, peerId, peerStates, incomingChannel, discoveryEvents)
            }
            launch {
                // Cancellable: channel receive is a suspension point
                for (command in outgoingChannel) {
                    handleCommand(command, peerId, peerStates)
                }
            }
            launch {
                processDiscoveryEvents(discoveryEvents, peerStates, incomingChannel, peerId, config.displayName, localAddress, boundPort)
            }
            // Handshake maintenance + RunLoop processing
            launch {
                // Cancellable: delay is a suspension point
                while (true) {
                    withContext(Dispatchers.Main) {
                        NSRunLoop.currentRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.05))
                    }

                    delay(50.milliseconds)

                    peerStates.values.toList().forEach { state ->
                        if (state.weSeeThemViaDiscovery && !state.isJoined) {
                            sendHandshakeHello(peerId, config.displayName, localAddress, boundPort, state.info)
                            state.weSentHello = true
                        }
                    }
                }
            }

            try {
                coroutineScope { block(broadcastFn) }
            } finally {
                coroutineContext.cancelChildren()
            }
        }
    } finally {
        withContext(NonCancellable) {
            NSLog("[PeerNet-$peerId] Stopping")
            if (udpSocket >= 0) {
                close(udpSocket)
            }
            withContext(Dispatchers.Main) {
                bonjourState.browser.stop()
                bonjourState.service.stop()
            }
        }
    }
}

private data class BonjourState(
    val service: NSNetService,
    val browser: NSNetServiceBrowser,
)

private fun startBonjour(
    config: PeerNetConfig,
    peerId: String,
    localAddress: String,
    boundPort: Int,
    serviceType: String,
    discoveryEvents: SendChannel<DiscoveryEvent>,
    resolverDelegates: MutableList<NetServiceResolveDelegate>,
): BonjourState {
    val serviceName = "${config.displayName}|$peerId|$localAddress|$boundPort"
    NSLog("[PeerNet-$peerId] Registering service: $serviceName")
    NSLog("[PeerNet-$peerId] Service type: $serviceType")

    val publishDelegate = NetServicePublishDelegate(peerId)
    val netService = NSNetService(
        domain = "local.",
        type = serviceType,
        name = serviceName,
        port = boundPort
    )
    netService.delegate = publishDelegate
    NSLog("[PeerNet-$peerId] Calling publish()...")
    netService.publish()
    netService.scheduleInRunLoop(NSRunLoop.currentRunLoop, forMode = NSDefaultRunLoopMode)
    NSLog("[PeerNet-$peerId] Service scheduled in run loop")

    val browserDelegate = NetServiceBrowserDelegate(
        peerId = peerId,
        onServiceFound = { service ->
            NSLog("[PeerNet-$peerId] Found service: ${service.name}")
            val resolveDelegate = NetServiceResolveDelegate(peerId) { resolvedService ->
                discoveryEvents.trySend(DiscoveryEvent.ServiceResolved(resolvedService))
            }
            resolverDelegates.add(resolveDelegate)
            service.delegate = resolveDelegate
            service.resolveWithTimeout(5.0)
        }
    )

    val netServiceBrowser = NSNetServiceBrowser()
    netServiceBrowser.delegate = browserDelegate
    netServiceBrowser.scheduleInRunLoop(NSRunLoop.currentRunLoop, forMode = NSDefaultRunLoopMode)
    NSLog("[PeerNet-$peerId] Starting browser search for type: $serviceType")
    netServiceBrowser.searchForServicesOfType(serviceType, inDomain = "local.")
    NSLog("[PeerNet-$peerId] Bonjour setup complete")

    return BonjourState(service = netService, browser = netServiceBrowser)
}

private suspend fun processDiscoveryEvents(
    events: ReceiveChannel<DiscoveryEvent>,
    peerStates: MutableMap<String, PeerState>,
    incomingChannel: SendChannel<RawPeerMessage>,
    peerId: String,
    peerName: String,
    localAddress: String,
    boundPort: Int,
) {
    // Cancellable: channel receive is a suspension point
    for (event in events) {
        when (event) {
            is DiscoveryEvent.ServiceResolved -> {
                val serviceName = event.service.name
                val parts = serviceName.split("|")
                if (parts.size >= 3) {
                    val discoveredName = parts[0]
                    val pId = parts[1]
                    val peerAddr = parts[2]
                    val peerPort = parts.getOrNull(3)?.toIntOrNull() ?: MESSAGE_PORT

                    if (pId == peerId) continue

                    val peerInfo = PeerInfo(id = pId, name = discoveredName, address = peerAddr, port = peerPort)
                    val state = peerStates.getOrPut(pId) {
                        NSLog("[PeerNet-$peerId] Discovered via mDNS: $discoveredName ($pId) at $peerAddr:$peerPort")
                        PeerState(info = peerInfo)
                    }
                    state.weSeeThemViaDiscovery = true

                    sendHandshakeHello(peerId, peerName, localAddress, boundPort, peerInfo)
                    state.weSentHello = true
                }
            }
            is DiscoveryEvent.PeerJoined -> {
                val state = event.state
                if (!state.isJoined) {
                    state.isJoined = true
                    NSLog("[PeerNet-$peerId] Peer JOINED: ${state.info.name} (${state.info.id})")
                    incomingChannel.send(RawPeerMessage.Event.Connected(state.info))
                }
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun createUdpSocket(peerId: String): Int {
    val fd = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
    if (fd < 0) {
        NSLog("[PeerNet-$peerId] Failed to create UDP socket")
        return -1
    }

    memScoped {
        val reuseAddr = alloc<IntVar>()
        reuseAddr.value = 1
        setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, reuseAddr.ptr, sizeOf<IntVar>().toUInt())
        setsockopt(fd, SOL_SOCKET, SO_REUSEPORT, reuseAddr.ptr, sizeOf<IntVar>().toUInt())

        val addr = alloc<sockaddr_in>()
        addr.sin_family = AF_INET.toUByte()
        addr.sin_port = htons(MESSAGE_PORT.toUShort())
        addr.sin_addr.s_addr = INADDR_ANY

        var bindResult = bind(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().toUInt())
        if (bindResult < 0) {
            NSLog("[PeerNet-$peerId] Failed to bind to port $MESSAGE_PORT, falling back to random port")
            addr.sin_port = htons(0.toUShort())
            bindResult = bind(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().toUInt())
            if (bindResult < 0) {
                NSLog("[PeerNet-$peerId] Failed to bind UDP socket on fallback port")
                close(fd)
                return -1
            }
        }
    }

    return fd
}

@OptIn(ExperimentalForeignApi::class)
private fun getSocketBoundPort(fd: Int): Int = memScoped {
    val boundAddr = alloc<sockaddr_in>()
    val addrLen = alloc<UIntVar>()
    addrLen.value = sizeOf<sockaddr_in>().toUInt()
    getsockname(fd, boundAddr.ptr.reinterpret(), addrLen.ptr)
    htons(boundAddr.sin_port).toInt()
}

@OptIn(ExperimentalForeignApi::class)
private suspend fun receiveLoop(
    udpSocket: Int,
    peerId: String,
    peerStates: MutableMap<String, PeerState>,
    incomingChannel: SendChannel<RawPeerMessage>,
    discoveryEvents: SendChannel<DiscoveryEvent>,
) {
    val buffer = ByteArray(4096)
    var loopCount = 0

    // Cancellable: delay is a suspension point
    while (true) {
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

                    val separatorIndex = message.indexOf(':')
                    if (separatorIndex > 0) {
                        val fromPeerId = message.substring(0, separatorIndex)
                        NSLog("[PeerNet-$peerId] Parsed fromPeerId=$fromPeerId, myPeerId=$peerId")
                        if (fromPeerId != peerId) {
                            val payload = message.substring(separatorIndex + 1)
                            NSLog("[PeerNet-$peerId] Payload starts with: ${payload.take(30)}")

                            when {
                                payload.startsWith(HANDSHAKE_HELLO) -> {
                                    handleHelloReceived(fromPeerId, payload, senderIp, peerId, peerStates, discoveryEvents)
                                }
                                payload.startsWith(HANDSHAKE_ACK) -> {
                                    handleAckReceived(fromPeerId, peerId, peerStates, discoveryEvents)
                                }
                                else -> {
                                    val state = peerStates[fromPeerId]
                                    if (state != null) {
                                        incomingChannel.send(RawPeerMessage.Received(fromPeerId, payload.encodeToByteArray()))
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

private fun handleHelloReceived(
    fromPeerId: String,
    payload: String,
    fromAddress: String,
    peerId: String,
    peerStates: MutableMap<String, PeerState>,
    discoveryEvents: SendChannel<DiscoveryEvent>,
) {
    val helloData = payload.removePrefix(HANDSHAKE_HELLO)
    val parts = helloData.split("|")
    val pName = parts.getOrNull(0) ?: "Unknown"
    var pAddr = parts.getOrNull(2) ?: fromAddress
    val pPort = parts.getOrNull(3)?.toIntOrNull() ?: MESSAGE_PORT

    if (pAddr == "10.0.2.15") {
        pAddr = fromAddress
    }

    val peerInfo = PeerInfo(id = fromPeerId, name = pName, address = pAddr, port = pPort)

    val state = peerStates.getOrPut(fromPeerId) {
        NSLog("[PeerNet-$peerId] Received HELLO from new peer: $pName ($fromPeerId) at $pAddr:$pPort")
        PeerState(info = peerInfo)
    }
    state.weSeeThemViaDiscovery = true

    sendHandshakeAck(peerId, peerInfo)
    state.weAckedThem = true

    checkAndEmitJoined(state, discoveryEvents)
}

private fun handleAckReceived(
    fromPeerId: String,
    peerId: String,
    peerStates: MutableMap<String, PeerState>,
    discoveryEvents: SendChannel<DiscoveryEvent>,
) {
    val state = peerStates[fromPeerId] ?: return
    NSLog("[PeerNet-$peerId] Received ACK from: $fromPeerId")
    state.theyAckedUs = true
    checkAndEmitJoined(state, discoveryEvents)
}

private fun checkAndEmitJoined(state: PeerState, discoveryEvents: SendChannel<DiscoveryEvent>) {
    if (state.weSeeThemViaDiscovery && state.theyAckedUs && !state.isJoined) {
        discoveryEvents.trySend(DiscoveryEvent.PeerJoined(state))
    }
}

private fun sendHandshakeHello(peerId: String, peerName: String, localAddress: String, boundPort: Int, peer: PeerInfo) {
    val payload = "$HANDSHAKE_HELLO$peerName|$peerId|$localAddress|$boundPort"
    NSLog("[PeerNet-$peerId] Sending HELLO to ${peer.name} at ${peer.address}:${peer.port}")
    sendUdp(peer.address, peer.port, "$peerId:$payload")
}

private fun sendHandshakeAck(peerId: String, peer: PeerInfo) {
    val payload = "$HANDSHAKE_ACK$peerId"
    sendUdp(peer.address, peer.port, "$peerId:$payload")
}

private fun handleCommand(command: PeerCommand, peerId: String, peerStates: Map<String, PeerState>) {
    when (command) {
        is PeerCommand.SendTo -> {
            val state = peerStates[command.peerId]
            if (state != null) {
                sendUdp(state.info.address, state.info.port, "$peerId:${command.payload.decodeToString()}")
            }
        }
        is PeerCommand.Broadcast -> {
            peerStates.values.forEach { state ->
                sendUdp(state.info.address, state.info.port, "$peerId:${command.payload.decodeToString()}")
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
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
