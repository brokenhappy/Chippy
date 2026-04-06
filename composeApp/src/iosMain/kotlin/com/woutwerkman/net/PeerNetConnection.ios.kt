package com.woutwerkman.net

import kotlin.time.Duration.Companion.milliseconds
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import platform.Foundation.*
import platform.darwin.*
import platform.posix.*
import kotlin.concurrent.AtomicReference

private fun htons(value: UShort): UShort =
    ((value.toInt() and 0xFF) shl 8 or (value.toInt() shr 8 and 0xFF)).toUShort()

// ==================== UdpSocket actuals ====================

internal actual class UdpSocket(val fd: Int)

@OptIn(ExperimentalForeignApi::class)
internal actual suspend fun <T> withUdpSocket(
    peerId: String,
    block: suspend CoroutineScope.(UdpSocket) -> T,
): T {
    val fd = createPosixUdpSocket(peerId)
    return try {
        coroutineScope { block(UdpSocket(fd)) }
    } finally {
        if (fd >= 0) close(fd)
    }
}

@OptIn(ExperimentalForeignApi::class)
internal actual val UdpSocket.localPort: Int
    get() = memScoped {
        val boundAddr = alloc<sockaddr_in>()
        val addrLen = alloc<UIntVar>()
        addrLen.value = sizeOf<sockaddr_in>().toUInt()
        getsockname(fd, boundAddr.ptr.reinterpret(), addrLen.ptr)
        htons(boundAddr.sin_port).toInt()
    }

@OptIn(ExperimentalForeignApi::class)
internal actual fun UdpSocket.send(address: String, port: Int, message: String) {
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

@OptIn(ExperimentalForeignApi::class)
internal actual fun UdpSocket.receivedPackets(): Flow<ReceivedPacket> = flow {
    val buffer = ByteArray(4096)
    while (true) {
        memScoped {
            val senderAddr = alloc<sockaddr_in>()
            val addrLen = alloc<UIntVar>()
            addrLen.value = sizeOf<sockaddr_in>().toUInt()

            buffer.usePinned { pinned ->
                val bytesRead = recvfrom(
                    fd,
                    pinned.addressOf(0),
                    buffer.size.toULong(),
                    0,
                    senderAddr.ptr.reinterpret(),
                    addrLen.ptr
                )

                if (bytesRead > 0) {
                    val senderIp = inet_ntoa(senderAddr.sin_addr.readValue())?.toKString() ?: "unknown"
                    val senderPort = htons(senderAddr.sin_port).toInt()
                    val message = buffer.decodeToString(0, bytesRead.toInt())
                    emit(ReceivedPacket(message, senderIp, senderPort))
                }
            }
        }
        // 10ms polling: the socket is non-blocking (O_NONBLOCK), so recvfrom returns
        // immediately when no data. This delay yields to the coroutine scheduler and
        // provides a cancellation point.
        delay(10.milliseconds)
    }
}

// ==================== POSIX socket helpers ====================

@OptIn(ExperimentalForeignApi::class)
private fun createPosixUdpSocket(peerId: String): Int {
    val fd = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
    if (fd < 0) {
        NSLog("[PeerNet-$peerId] Failed to create UDP socket")
        return -1
    }

    memScoped {
        val reuseAddr = alloc<IntVar>()
        reuseAddr.value = 1
        setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, reuseAddr.ptr, sizeOf<IntVar>().toUInt())

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

    // Non-blocking mode for polling receive loop
    val flags = fcntl(fd, F_GETFL, 0)
    fcntl(fd, F_SETFL, flags or O_NONBLOCK)

    return fd
}

// ==================== withTransport ====================

/**
 * Thread-safe peer state map using compare-and-set on an immutable map snapshot.
 * All mutations atomically replace the entire map, so readers always see a consistent state.
 */
internal class AtomicPeerStates : PeerStates {
    private val ref = AtomicReference(emptyMap<String, PeerState>())

    override fun snapshot(): Map<String, PeerState> = ref.value

    override operator fun get(peerId: String): PeerState? = ref.value[peerId]

    override fun remove(peerId: String): PeerState? {
        while (true) {
            val current = ref.value
            val existing = current[peerId] ?: return null
            if (ref.compareAndSet(current, current - peerId)) return existing
        }
    }

    override inline fun compute(peerId: String, transform: (PeerState?) -> PeerState?): PeerState? {
        while (true) {
            val current = ref.value
            val result = transform(current[peerId])
            val newMap = if (result != null) current + (peerId to result) else current - peerId
            if (ref.compareAndSet(current, newMap)) return result
        }
    }
}

internal actual suspend fun <T> withTransport(
    config: PeerNetConfig,
    peerId: String,
    block: suspend CoroutineScope.(TransportHandle) -> T,
): T {
    val serviceType = "_${config.serviceName}._udp."
    val discoveryEvents = Channel<ServiceDiscoveryEvent>(Channel.BUFFERED)

    NSLog("[PeerNet-$peerId] Starting peer discovery")
    val localAddress = getLocalIpAddress()
    NSLog("[PeerNet-$peerId] Local IP: $localAddress")

    return withUdpSocket(peerId) { socket ->
        NSLog("[PeerNet-$peerId] Listening on port ${socket.localPort}")

        // Start Bonjour on Main thread (required by NSNetService)
        val resolverDelegates = mutableListOf<NetServiceResolveDelegate>()
        val bonjourState = withContext(Dispatchers.Main) {
            startBonjour(config, peerId, localAddress, socket.localPort, serviceType, discoveryEvents, resolverDelegates)
        }

        val handle = TransportHandle(
            localAddress = localAddress,
            localPort = socket.localPort,
            discoveryEvents = discoveryEvents,
            receivedPackets = socket.receivedPackets(),
            sendUdp = { address, port, message -> socket.send(address, port, message) },
            platformTasks = {
                // Drive the RunLoop so Bonjour delegate callbacks fire
                launch {
                    while (true) {
                        withContext(Dispatchers.Main) {
                            NSRunLoop.currentRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.05))
                        }
                        delay(50.milliseconds)
                    }
                }
                // BLE transport — discovery + data fallback
                launch {
                    withBleTransport(peerId, localAddress, socket.localPort) { ble ->
                        launch {
                            for (peerInfo in ble.discoveredPeers) {
                                val serviceName = formatServiceName(peerInfo.name, peerInfo.id, peerInfo.address, peerInfo.port)
                                discoveryEvents.trySend(ServiceDiscoveryEvent.Discovered(serviceName))
                            }
                        }
                        // BLE incoming data — not currently bridged into the receivedPackets flow.
                        // BLE peers also get UDP handshakes, so the common layer discovers them
                        // via UDP. If a BLE-only peer is needed, this would need a merged flow.
                        awaitCancellation()
                    }
                }
            },
        )

        try {
            block(handle)
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                bonjourState.browser.stop()
                bonjourState.service.stop()
            }
            NSLog("[PeerNet-$peerId] Stopped")
        }
    }
}

// ==================== Bonjour ====================

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
    discoveryEvents: SendChannel<ServiceDiscoveryEvent>,
    resolverDelegates: MutableList<NetServiceResolveDelegate>,
): BonjourState {
    val serviceName = formatServiceName(config.displayName, peerId, localAddress, boundPort)
    NSLog("[PeerNet-$peerId] Registering service: $serviceName")

    val publishDelegate = NetServicePublishDelegate(peerId)
    val netService = NSNetService(
        domain = "local.",
        type = serviceType,
        name = serviceName,
        port = boundPort
    )
    netService.delegate = publishDelegate
    netService.publish()
    netService.scheduleInRunLoop(NSRunLoop.currentRunLoop, forMode = NSDefaultRunLoopMode)

    val browserDelegate = NetServiceBrowserDelegate(
        peerId = peerId,
        onServiceFound = { service ->
            NSLog("[PeerNet-$peerId] Found service: ${service.name}")
            val resolveDelegate = NetServiceResolveDelegate(peerId) { resolvedService ->
                discoveryEvents.trySend(ServiceDiscoveryEvent.Discovered(resolvedService.name))
            }
            resolverDelegates.add(resolveDelegate)
            service.delegate = resolveDelegate
            service.resolveWithTimeout(5.0)
        }
    )

    val netServiceBrowser = NSNetServiceBrowser()
    netServiceBrowser.delegate = browserDelegate
    netServiceBrowser.scheduleInRunLoop(NSRunLoop.currentRunLoop, forMode = NSDefaultRunLoopMode)
    netServiceBrowser.searchForServicesOfType(serviceType, inDomain = "local.")

    return BonjourState(service = netService, browser = netServiceBrowser)
}

// ==================== Bonjour Delegates ====================

private class NetServiceBrowserDelegate(
    private val peerId: String,
    private val onServiceFound: (NSNetService) -> Unit
) : NSObject(), NSNetServiceBrowserDelegateProtocol {

    override fun netServiceBrowser(browser: NSNetServiceBrowser, didFindService: NSNetService, moreComing: Boolean) {
        NSLog("[PeerNet-$peerId] Browser found service: ${didFindService.name}, moreComing=$moreComing")
        if (!didFindService.name.contains(peerId)) {
            onServiceFound(didFindService)
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

internal class NetServiceResolveDelegate(
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
