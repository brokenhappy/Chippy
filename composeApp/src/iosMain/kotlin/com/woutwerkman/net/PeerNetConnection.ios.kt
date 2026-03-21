package com.woutwerkman.net

import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import platform.Foundation.*
import platform.darwin.*
import platform.posix.*
import kotlin.random.Random

private const val MESSAGE_PORT = 41234

@OptIn(ExperimentalForeignApi::class)
internal actual suspend fun <T> withPeerNetConnectionImpl(
    config: PeerNetConfig,
    block: suspend CoroutineScope.(PeerNetConnection) -> T
): T = coroutineScope {
    val incoming = Channel<PeerMessage>(Channel.BUFFERED)
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
        val connection = PeerNetConnection(incoming, outgoing)

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

// Network byte order conversion
private fun htons(value: UShort): UShort =
    ((value.toInt() and 0xFF) shl 8 or (value.toInt() shr 8 and 0xFF)).toUShort()

@OptIn(ExperimentalForeignApi::class)
private class IosPeerNetConnectionImpl(
    private val config: PeerNetConfig,
    private val peerId: String,
    private val incoming: Channel<PeerMessage>,
    private val scope: CoroutineScope
) {
    // Service type format matching UdpTransport.ios.kt
    private val serviceType = "_${config.serviceName}._udp."
    private val discoveredPeers = mutableMapOf<String, PeerInfo>()

    private var netService: NSNetService? = null
    private var netServiceBrowser: NSNetServiceBrowser? = null
    private var browserDelegate: NetServiceBrowserDelegate? = null
    private var publishDelegate: NetServicePublishDelegate? = null
    private val pendingServices = mutableListOf<NSNetService>()
    private val resolverDelegates = mutableListOf<NetServiceResolveDelegate>()

    private var udpSocket: Int = -1
    private var receiveJob: Job? = null
    private var localAddress: String = "0.0.0.0"

    suspend fun start() {
        println("[iOS-$peerId] Starting peer discovery")
        localAddress = getLocalIpAddress()
        println("[iOS-$peerId] Local IP: $localAddress")

        // Create UDP socket for messaging
        createUdpSocket()

        // Start Bonjour on main thread (critical for delegates to work!)
        withContext(Dispatchers.Main) {
            startBonjour()
        }
    }

    private fun createUdpSocket() {
        udpSocket = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        if (udpSocket < 0) {
            println("[iOS-$peerId] Failed to create UDP socket: ${posix_errno()}")
            return
        }
        println("[iOS-$peerId] Created UDP socket: $udpSocket")

        memScoped {
            val reuseAddr = alloc<IntVar>()
            reuseAddr.value = 1
            setsockopt(udpSocket, SOL_SOCKET, SO_REUSEADDR, reuseAddr.ptr, sizeOf<IntVar>().toUInt())
            setsockopt(udpSocket, SOL_SOCKET, SO_REUSEPORT, reuseAddr.ptr, sizeOf<IntVar>().toUInt())

            val addr = alloc<sockaddr_in>()
            addr.sin_family = AF_INET.toUByte()
            addr.sin_port = htons(MESSAGE_PORT.toUShort())
            addr.sin_addr.s_addr = INADDR_ANY

            val bindResult = bind(udpSocket, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().toUInt())
            if (bindResult < 0) {
                println("[iOS-$peerId] Failed to bind UDP socket: ${posix_errno()}")
                close(udpSocket)
                udpSocket = -1
                return
            }
            println("[iOS-$peerId] UDP socket bound to port $MESSAGE_PORT")
        }

        // Set non-blocking
        val flags = fcntl(udpSocket, F_GETFL, 0)
        fcntl(udpSocket, F_SETFL, flags or O_NONBLOCK)

        println("[iOS-$peerId] Listening for messages on port $MESSAGE_PORT")

        // Start receive loop
        receiveJob = scope.launch(Dispatchers.Default) {
            receiveLoop()
        }
    }

    private suspend fun receiveLoop() {
        val buffer = ByteArray(4096)
        println("[iOS-$peerId] UDP receive loop started")

        while (scope.isActive && udpSocket >= 0) {
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
                        val data = buffer.copyOf(bytesRead.toInt())
                        val senderIp = inet_ntoa(senderAddr.sin_addr.readValue())?.toKString() ?: "unknown"

                        // Find peer by address
                        val fromPeer = discoveredPeers.values.find { it.address == senderIp }
                        if (fromPeer != null) {
                            scope.launch {
                                incoming.send(PeerMessage.Data(fromPeer.id, data))
                            }
                        }
                    }
                }
            }
            delay(10)
        }
        println("[iOS-$peerId] UDP receive loop ended")
    }

    private fun startBonjour() {
        // Service name format: displayName|peerId|address (matching UdpTransport)
        val serviceName = "${config.displayName}|$peerId|$localAddress"
        println("[iOS-$peerId] Creating Bonjour service: $serviceName, type: $serviceType, port: $MESSAGE_PORT")

        publishDelegate = NetServicePublishDelegate(peerId)

        netService = NSNetService(
            domain = "local.",
            type = serviceType,
            name = serviceName,
            port = MESSAGE_PORT
        )
        netService?.delegate = publishDelegate
        netService?.publish()
        println("[iOS-$peerId] Bonjour service publish() called")

        // Schedule on run loop AFTER publish (important!)
        netService?.scheduleInRunLoop(NSRunLoop.currentRunLoop, forMode = NSDefaultRunLoopMode)

        // Browse for services
        browserDelegate = NetServiceBrowserDelegate(
            peerId = peerId,
            onServiceFound = { service ->
                println("[iOS-$peerId] Bonjour found service: ${service.name}")
                pendingServices.add(service)
                // Create a delegate for resolution and keep strong reference
                val resolveDelegate = NetServiceResolveDelegate(peerId) { resolvedService ->
                    handleResolvedService(resolvedService)
                }
                resolverDelegates.add(resolveDelegate)
                service.delegate = resolveDelegate
                service.resolveWithTimeout(5.0)
            }
        )

        netServiceBrowser = NSNetServiceBrowser()
        netServiceBrowser?.delegate = browserDelegate
        netServiceBrowser?.scheduleInRunLoop(NSRunLoop.currentRunLoop, forMode = NSDefaultRunLoopMode)
        netServiceBrowser?.searchForServicesOfType(serviceType, inDomain = "local.")
        println("[iOS-$peerId] Bonjour browser started for type: $serviceType in domain: local.")
    }

    private fun handleResolvedService(service: NSNetService) {
        val serviceName = service.name
        println("[iOS-$peerId] Bonjour service resolved: $serviceName")

        val parts = serviceName.split("|")
        if (parts.size >= 3) {
            val peerName = parts[0]
            val pId = parts[1]
            val peerAddr = parts[2]

            if (pId != peerId && !discoveredPeers.containsKey(pId)) {
                println("[iOS-$peerId] Found peer via Bonjour: $peerName ($pId) at $peerAddr")
                val peerInfo = PeerInfo(
                    id = pId,
                    name = peerName,
                    address = peerAddr,
                    port = MESSAGE_PORT
                )
                discoveredPeers[pId] = peerInfo

                scope.launch {
                    incoming.send(PeerMessage.Event.Discovered(peerInfo))
                }
            }
        }
    }

    fun handleCommand(command: PeerCommand) {
        when (command) {
            is PeerCommand.SendTo -> {
                val peer = discoveredPeers[command.peerId]
                if (peer != null) {
                    sendUdp(peer.address, peer.port, command.payload)
                }
            }
            is PeerCommand.Broadcast -> {
                discoveredPeers.values.forEach { peer ->
                    sendUdp(peer.address, peer.port, command.payload)
                }
            }
        }
    }

    private fun sendUdp(address: String, port: Int, data: ByteArray) {
        memScoped {
            val sendSocket = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
            if (sendSocket < 0) return

            val destAddr = alloc<sockaddr_in>()
            destAddr.sin_family = AF_INET.toUByte()
            destAddr.sin_port = htons(port.toUShort())
            inet_pton(AF_INET, address, destAddr.sin_addr.ptr)

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
        println("[iOS-$peerId] Stopping peer discovery")
        receiveJob?.cancel()
        if (udpSocket >= 0) {
            close(udpSocket)
            udpSocket = -1
        }
        withContext(Dispatchers.Main) {
            netServiceBrowser?.stop()
            netService?.stop()
        }
    }

    private fun getLocalIpAddress(): String {
        memScoped {
            val ifaddrs = alloc<CPointerVar<ifaddrs>>()
            if (getifaddrs(ifaddrs.ptr) == 0) {
                var current = ifaddrs.value
                var wifiIp: String? = null
                var cellularIp: String? = null
                var otherIp: String? = null

                while (current != null) {
                    val addr = current.pointed
                    if (addr.ifa_addr != null && addr.ifa_addr!!.pointed.sa_family == AF_INET.toUByte()) {
                        val name = addr.ifa_name?.toKString() ?: ""
                        val sockaddr = addr.ifa_addr!!.reinterpret<sockaddr_in>()
                        val inAddr = sockaddr.pointed.sin_addr.readValue()
                        val ip = inet_ntoa(inAddr)?.toKString()

                        println("[iOS-$peerId] Found interface $name with IP $ip")

                        if (ip != null && !ip.startsWith("127.") && !ip.startsWith("169.254.") && !ip.startsWith("192.0.0.")) {
                            when {
                                // en0 is WiFi on iOS
                                name == "en0" -> wifiIp = ip
                                // bridge100 is hotspot interface (when connected TO a hotspot)
                                name == "bridge100" -> wifiIp = ip
                                // pdp_ip is cellular
                                name.startsWith("pdp_ip") -> cellularIp = ip
                                // Other interfaces (skip ipsec)
                                !name.startsWith("ipsec") -> if (otherIp == null) otherIp = ip
                            }
                        }
                    }
                    current = addr.ifa_next
                }
                freeifaddrs(ifaddrs.value)

                // Prefer WiFi, then other, then cellular
                val selectedIp = wifiIp ?: otherIp ?: cellularIp
                if (selectedIp != null) {
                    println("[iOS-$peerId] Selected IP $selectedIp (wifi=$wifiIp, cellular=$cellularIp, other=$otherIp)")
                    return selectedIp
                }
            }
        }
        return "0.0.0.0"
    }
}

// Delegate for browsing - matching UdpTransport.ios.kt
private class NetServiceBrowserDelegate(
    private val peerId: String,
    private val onServiceFound: (NSNetService) -> Unit
) : NSObject(), NSNetServiceBrowserDelegateProtocol {

    override fun netServiceBrowser(browser: NSNetServiceBrowser, didFindService: NSNetService, moreComing: Boolean) {
        println("[iOS-$peerId] Bonjour browser didFindService: ${didFindService.name}, moreComing: $moreComing")
        // Filter out our own service
        if (!didFindService.name.contains(peerId)) {
            onServiceFound(didFindService)
        } else {
            println("[iOS-$peerId] Filtered out own service")
        }
    }

    override fun netServiceBrowser(browser: NSNetServiceBrowser, didNotSearch: Map<Any?, *>) {
        println("[iOS-$peerId] Bonjour browser did not search: $didNotSearch")
    }

    override fun netServiceBrowserWillSearch(browser: NSNetServiceBrowser) {
        println("[iOS-$peerId] Bonjour browser will search")
    }

    override fun netServiceBrowserDidStopSearch(browser: NSNetServiceBrowser) {
        println("[iOS-$peerId] Bonjour browser did stop search")
    }
}

// Delegate for publishing
private class NetServicePublishDelegate(private val peerId: String) : NSObject(), NSNetServiceDelegateProtocol {

    override fun netServiceDidPublish(sender: NSNetService) {
        println("[iOS-$peerId] Bonjour service published successfully: ${sender.name}")
    }

    override fun netService(sender: NSNetService, didNotPublish: Map<Any?, *>) {
        println("[iOS-$peerId] Bonjour service did NOT publish: ${sender.name} - $didNotPublish")
    }
}

// Delegate for resolving
private class NetServiceResolveDelegate(
    private val peerId: String,
    private val onResolved: (NSNetService) -> Unit
) : NSObject(), NSNetServiceDelegateProtocol {

    override fun netServiceDidResolveAddress(sender: NSNetService) {
        onResolved(sender)
    }

    override fun netService(sender: NSNetService, didNotResolve: Map<Any?, *>) {
        println("[iOS-$peerId] Bonjour service did not resolve: ${sender.name} - $didNotResolve")
    }
}
