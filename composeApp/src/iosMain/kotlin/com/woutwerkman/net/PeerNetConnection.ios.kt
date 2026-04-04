package com.woutwerkman.net

import kotlin.time.Duration.Companion.milliseconds
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.awaitCancellation
import platform.Foundation.*
import platform.darwin.*
import platform.posix.*
import kotlin.concurrent.AtomicReference
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
    val weSeeThemViaDiscovery: Boolean = false,
    val weSentHello: Boolean = false,
    val theyAckedUs: Boolean = false,
    val weAckedThem: Boolean = false,
    val isJoined: Boolean = false,
    val bleConnected: Boolean = false,
    val udpHandshakeComplete: Boolean = false,
)

/**
 * Thread-safe peer state map using compare-and-set on an immutable map snapshot.
 * All mutations atomically replace the entire map, so readers always see a consistent state.
 */
private class AtomicPeerStates {
    private val ref = AtomicReference(emptyMap<String, PeerState>())

    fun snapshot(): Map<String, PeerState> = ref.value

    /** Atomically update a single peer's state. Returns the new state, or null if peer not found. */
    inline fun update(peerId: String, transform: (PeerState) -> PeerState): PeerState? {
        while (true) {
            val current = ref.value
            val existing = current[peerId] ?: return null
            val updated = transform(existing)
            if (ref.compareAndSet(current, current + (peerId to updated))) return updated
        }
    }

    /** Atomically compute a new value for [peerId]. The [transform] receives the current state (or null). */
    inline fun compute(peerId: String, transform: (PeerState?) -> PeerState?): PeerState? {
        while (true) {
            val current = ref.value
            val result = transform(current[peerId])
            val newMap = if (result != null) current + (peerId to result) else current - peerId
            if (ref.compareAndSet(current, newMap)) return result
        }
    }

    /** Insert [peerId] if absent, using [default] to create the initial value. Returns the existing or new state. */
    inline fun getOrPut(peerId: String, default: () -> PeerState): PeerState {
        while (true) {
            val current = ref.value
            current[peerId]?.let { return it }
            val newState = default()
            if (ref.compareAndSet(current, current + (peerId to newState))) return newState
        }
    }
}

/**
 * Events from discovery callbacks (Bonjour), bridged into the coroutine world.
 */
private sealed class DiscoveryEvent {
    data class ServiceResolved(val service: NSNetService) : DiscoveryEvent()
    data class BleServiceResolved(val peerInfo: PeerInfo) : DiscoveryEvent()
    data class PeerJoined(val peerId: String) : DiscoveryEvent()
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
    val peerStates = AtomicPeerStates()
    val discoveryEvents = Channel<DiscoveryEvent>(Channel.BUFFERED)

    NSLog("[PeerNet-$peerId] Starting peer discovery")
    val localAddress = getLocalIpAddress()
    NSLog("[PeerNet-$peerId] Local IP: $localAddress")

    val udpSocket = createUdpSocket(peerId)
    val boundPort = getSocketBoundPort(udpSocket)
    NSLog("[PeerNet-$peerId] Listening on port $boundPort")

    // BLE send function — set when BLE transport is active
    var bleSendToPeer: ((String, ByteArray) -> Boolean)? = null

    val broadcastFn: (ByteArray) -> Unit = { payload ->
        val message = "$peerId:${payload.decodeToString()}"
        peerStates.snapshot().forEach { (pId, state) ->
            if (state.udpHandshakeComplete) {
                sendUdp(state.info.address, state.info.port, message)
            } else if (state.bleConnected) {
                bleSendToPeer?.invoke(pId, payload)
            } else {
                // Best effort: try UDP anyway
                sendUdp(state.info.address, state.info.port, message)
            }
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
                for (command in outgoingChannel) {
                    handleCommand(command, peerId, peerStates, bleSendToPeer)
                }
            }
            launch {
                processDiscoveryEvents(discoveryEvents, peerStates, incomingChannel, peerId, config.displayName, localAddress, boundPort)
            }
            launch {
                driveRunLoopAndRetryHandshakes(peerStates, peerId, config.displayName, localAddress, boundPort)
            }
            // BLE transport — discovery + data fallback
            launch {
                withBleTransport(peerId, localAddress, boundPort) { ble ->
                    bleSendToPeer = ble.sendToPeer
                    // Forward BLE discoveries into the shared discovery channel
                    launch {
                        for (peerInfo in ble.discoveredPeers) {
                            discoveryEvents.trySend(DiscoveryEvent.BleServiceResolved(peerInfo))
                        }
                    }
                    // Forward BLE incoming data into the main incoming channel
                    launch {
                        for ((fromPeerId, payload) in ble.incoming) {
                            if (peerStates.snapshot().containsKey(fromPeerId)) {
                                incomingChannel.send(RawPeerMessage.Received(fromPeerId, payload))
                            }
                        }
                    }
                    // Keep alive until parent scope cancels
                    awaitCancellation()
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
            // 5s is Apple's recommended max timeout. Typical resolution on LAN is <500ms.
            // This is a safety net, not the expected duration.
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

/**
 * Combined RunLoop driver and handshake retry loop.
 *
 * iOS Bonjour (NSNetService) requires the main RunLoop to be pumped for delegate callbacks to
 * fire. We drive it in 50ms increments — fast enough for responsive discovery, slow enough to
 * not burn CPU. We also retry handshakes in the same loop since the 50ms cadence is more
 * aggressive than the 1s retry on other platforms, compensating for iOS's more complex
 * discovery path (Bonjour resolve + BLE).
 */
private suspend fun driveRunLoopAndRetryHandshakes(
    peerStates: AtomicPeerStates,
    peerId: String,
    displayName: String,
    localAddress: String,
    boundPort: Int,
) {
    while (true) {
        withContext(Dispatchers.Main) {
            NSRunLoop.currentRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.05))
        }

        delay(50.milliseconds)

        peerStates.snapshot().forEach { (pId, state) ->
            if (state.weSeeThemViaDiscovery && !state.isJoined && state.info.port > 0) {
                sendHandshakeHello(peerId, displayName, localAddress, boundPort, state.info)
                peerStates.update(pId) { it.copy(weSentHello = true) }
            }
        }
    }
}

private suspend fun processDiscoveryEvents(
    events: ReceiveChannel<DiscoveryEvent>,
    peerStates: AtomicPeerStates,
    incomingChannel: SendChannel<RawPeerMessage>,
    peerId: String,
    peerName: String,
    localAddress: String,
    boundPort: Int,
) {
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
                    peerStates.getOrPut(pId) {
                        NSLog("[PeerNet-$peerId] Discovered via mDNS: $discoveredName ($pId) at $peerAddr:$peerPort")
                        PeerState(info = peerInfo)
                    }
                    peerStates.update(pId) { it.copy(weSeeThemViaDiscovery = true, weSentHello = true) }

                    sendHandshakeHello(peerId, peerName, localAddress, boundPort, peerInfo)
                }
            }
            is DiscoveryEvent.BleServiceResolved -> {
                val peerInfo = event.peerInfo
                val pId = peerInfo.id

                if (pId == peerId) continue

                peerStates.getOrPut(pId) {
                    NSLog("[PeerNet-$peerId] Discovered via BLE: ${peerInfo.name} ($pId) at ${peerInfo.address}:${peerInfo.port}")
                    PeerState(info = peerInfo)
                }
                peerStates.update(pId) { it.copy(bleConnected = true, weSeeThemViaDiscovery = true) }

                // Try UDP handshake — if it succeeds, we'll prefer UDP (skip if port is 0)
                if (peerInfo.port > 0) {
                    peerStates.update(pId) { it.copy(weSentHello = true) }
                    sendHandshakeHello(peerId, peerName, localAddress, boundPort, peerInfo)
                }
            }
            is DiscoveryEvent.PeerJoined -> {
                var connected: PeerInfo? = null
                peerStates.compute(event.peerId) { existing ->
                    if (existing != null && !existing.isJoined) {
                        connected = existing.info
                        existing.copy(isJoined = true)
                    } else {
                        existing
                    }
                }
                connected?.let { info ->
                    NSLog("[PeerNet-$peerId] Peer JOINED: ${info.name} (${info.id})")
                    incomingChannel.send(RawPeerMessage.Event.Connected(info))
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
    peerStates: AtomicPeerStates,
    incomingChannel: SendChannel<RawPeerMessage>,
    discoveryEvents: SendChannel<DiscoveryEvent>,
) {
    val buffer = ByteArray(4096)
    var loopCount = 0

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
                                    if (peerStates.snapshot().containsKey(fromPeerId)) {
                                        incomingChannel.send(RawPeerMessage.Received(fromPeerId, payload.encodeToByteArray()))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        // 10ms polling interval: the socket is non-blocking (O_NONBLOCK), so recvfrom returns
        // immediately when no data is available. Without this delay we'd spin at 100% CPU.
        // 10ms keeps latency imperceptible while being cheap.
        delay(10.milliseconds)
    }
}

private fun handleHelloReceived(
    fromPeerId: String,
    payload: String,
    fromAddress: String,
    peerId: String,
    peerStates: AtomicPeerStates,
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

    peerStates.getOrPut(fromPeerId) {
        NSLog("[PeerNet-$peerId] Received HELLO from new peer: $pName ($fromPeerId) at $pAddr:$pPort")
        PeerState(info = peerInfo)
    }
    val state = peerStates.update(fromPeerId) {
        it.copy(info = peerInfo, weSeeThemViaDiscovery = true, weAckedThem = true)
    }

    sendHandshakeAck(peerId, peerInfo)

    if (state != null) checkAndEmitJoined(fromPeerId, state, discoveryEvents)
}

private fun handleAckReceived(
    fromPeerId: String,
    peerId: String,
    peerStates: AtomicPeerStates,
    discoveryEvents: SendChannel<DiscoveryEvent>,
) {
    NSLog("[PeerNet-$peerId] Received ACK from: $fromPeerId")
    val state = peerStates.update(fromPeerId) { it.copy(theyAckedUs = true, udpHandshakeComplete = true) } ?: return
    checkAndEmitJoined(fromPeerId, state, discoveryEvents)
}

private fun checkAndEmitJoined(peerId: String, state: PeerState, discoveryEvents: SendChannel<DiscoveryEvent>) {
    if (state.weSeeThemViaDiscovery && state.theyAckedUs && !state.isJoined) {
        discoveryEvents.trySend(DiscoveryEvent.PeerJoined(peerId))
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

private fun handleCommand(
    command: PeerCommand,
    peerId: String,
    peerStates: AtomicPeerStates,
    bleSendToPeer: ((String, ByteArray) -> Boolean)?,
) {
    val snapshot = peerStates.snapshot()
    when (command) {
        is PeerCommand.SendTo -> {
            val state = snapshot[command.peerId]
            if (state != null) {
                if (state.udpHandshakeComplete) {
                    sendUdp(state.info.address, state.info.port, "$peerId:${command.payload.decodeToString()}")
                } else if (state.bleConnected) {
                    bleSendToPeer?.invoke(command.peerId, command.payload)
                } else {
                    sendUdp(state.info.address, state.info.port, "$peerId:${command.payload.decodeToString()}")
                }
            }
        }
        is PeerCommand.Broadcast -> {
            snapshot.forEach { (pId, state) ->
                if (state.udpHandshakeComplete) {
                    sendUdp(state.info.address, state.info.port, "$peerId:${command.payload.decodeToString()}")
                } else if (state.bleConnected) {
                    bleSendToPeer?.invoke(pId, command.payload)
                } else {
                    sendUdp(state.info.address, state.info.port, "$peerId:${command.payload.decodeToString()}")
                }
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
