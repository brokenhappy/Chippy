package com.woutwerkman.game.network

import kotlinx.cinterop.*
import kotlinx.coroutines.*
import platform.Foundation.*
import platform.Network.*
import platform.darwin.*
import platform.posix.*
import kotlin.coroutines.coroutineContext

// Network byte order conversion
private fun htons(value: UShort): UShort {
    return ((value.toInt() and 0xFF) shl 8 or (value.toInt() shr 8 and 0xFF)).toUShort()
}

private fun ntohs(value: UShort): UShort {
    return ((value.toInt() and 0xFF) shl 8 or (value.toInt() shr 8 and 0xFF)).toUShort()
}

private const val SERVICE_TYPE = "_chippy._udp."

// Old iOS-specific transport, now using common PeerNetTransport via createUdpNetworkTransport()
// Keeping this class for reference but it's no longer used.
@Deprecated("Use PeerNetTransport via createUdpNetworkTransport() instead")

@OptIn(ExperimentalForeignApi::class)
class IosUdpNetworkTransport(
    private val peerId: String,
    private val peerName: String
) : NetworkTransport {

    private var messageHandler: ((String) -> Unit)? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var udpSocket: Int = -1
    private var isDiscovering = false
    private var isServerRunning = false
    private var receiveJob: Job? = null

    // Bonjour
    private var netService: NSNetService? = null
    private var netServiceBrowser: NSNetServiceBrowser? = null
    private var browserDelegate: NetServiceBrowserDelegate? = null
    private var publishDelegate: NetServicePublishDelegate? = null
    private val pendingServices = mutableListOf<NSNetService>()
    private val resolverDelegates = mutableListOf<NetServiceResolveDelegate>()

    private val connectedPeers = mutableMapOf<String, PeerAddress>()
    private var localAddress: String = "0.0.0.0"
    private var localPort: Int = GAME_PORT_START

    data class PeerAddress(val address: String, val port: Int)

    override fun setMessageHandler(handler: (String) -> Unit) {
        messageHandler = handler
    }

    override suspend fun startDiscovery() {
        if (isDiscovering) return
        isDiscovering = true

        try {
            // Get local IP address
            localAddress = getLocalIpAddress()
            println("iOS: Local IP address: $localAddress")

            // Create BSD UDP socket for receiving
            udpSocket = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
            if (udpSocket < 0) {
                println("iOS: Failed to create UDP socket: ${posix_errno()}")
                return
            }
            println("iOS: Created UDP socket: $udpSocket")

            // Allow address reuse
            memScoped {
                val reuseAddr = alloc<IntVar>()
                reuseAddr.value = 1
                setsockopt(udpSocket, SOL_SOCKET, SO_REUSEADDR, reuseAddr.ptr, sizeOf<IntVar>().toUInt())
                setsockopt(udpSocket, SOL_SOCKET, SO_REUSEPORT, reuseAddr.ptr, sizeOf<IntVar>().toUInt())
            }

            // Bind to discovery port
            memScoped {
                val addr = alloc<sockaddr_in>()
                addr.sin_family = AF_INET.toUByte()
                addr.sin_port = htons(DISCOVERY_PORT.toUShort())
                addr.sin_addr.s_addr = INADDR_ANY

                val bindResult = bind(udpSocket, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().toUInt())
                if (bindResult < 0) {
                    println("iOS: Failed to bind UDP socket: ${posix_errno()}")
                    close(udpSocket)
                    udpSocket = -1
                    return
                }
                println("iOS: UDP socket bound to port $DISCOVERY_PORT")
            }

            // Set non-blocking
            val flags = fcntl(udpSocket, F_GETFL, 0)
            fcntl(udpSocket, F_SETFL, flags or O_NONBLOCK)

            // Start receive loop in background
            receiveJob = scope.launch(Dispatchers.Default) {
                receiveLoop()
            }

            // Start Bonjour service registration and browsing (on main thread)
            withContext(Dispatchers.Main) {
                startBonjour()
            }

        } catch (e: Exception) {
            println("iOS: Error starting discovery: ${e.message}")
        }
    }

    private suspend fun receiveLoop() {
        val buffer = ByteArray(4096)
        println("iOS: UDP receive loop started")

        while (coroutineContext.isActive && udpSocket >= 0) {
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
                        val message = buffer.decodeToString(0, bytesRead.toInt())
                        val senderIp = inet_ntoa(senderAddr.sin_addr.readValue())?.toKString() ?: "unknown"
                        val senderPort = ntohs(senderAddr.sin_port).toInt()
                        println("iOS: UDP received ${bytesRead} bytes from $senderIp:$senderPort - ${message.take(60)}...")

                        if (!message.contains(peerId)) {
                            println("iOS: Passing message to handler (my peerId=$peerId)")
                            withContext(Dispatchers.Main) {
                                messageHandler?.invoke(message)
                            }
                        } else {
                            println("iOS: Filtered out own message (contains my peerId=$peerId)")
                        }
                    }
                }
            }
            // Small delay to prevent busy loop
            delay(10)
        }
        println("iOS: UDP receive loop ended")
    }

    private fun startBonjour() {
        // Register our service
        // Service name format: peerName|peerId|address
        val serviceName = "$peerName|$peerId|$localAddress"
        println("iOS: Creating Bonjour service: $serviceName, type: $SERVICE_TYPE, port: $DISCOVERY_PORT")

        publishDelegate = NetServicePublishDelegate()

        netService = NSNetService(
            domain = "local.",
            type = SERVICE_TYPE,
            name = serviceName,
            port = DISCOVERY_PORT
        )
        netService?.delegate = publishDelegate
        netService?.publish()
        println("iOS: Bonjour service publish() called")

        // Browse for other services
        browserDelegate = NetServiceBrowserDelegate(
            peerId = peerId,
            onServiceFound = { service ->
                println("iOS: Bonjour found service: ${service.name}")
                pendingServices.add(service)
                // Create a delegate for resolution and keep strong reference
                val resolveDelegate = NetServiceResolveDelegate { resolvedService ->
                    handleResolvedService(resolvedService)
                }
                resolverDelegates.add(resolveDelegate)
                service.delegate = resolveDelegate
                service.resolveWithTimeout(5.0)
            },
            onServiceRemoved = { service ->
                println("iOS: Bonjour service removed: ${service.name}")
            }
        )

        netServiceBrowser = NSNetServiceBrowser()
        netServiceBrowser?.delegate = browserDelegate
        netServiceBrowser?.scheduleInRunLoop(NSRunLoop.currentRunLoop, forMode = NSDefaultRunLoopMode)
        // Use empty string for domain to search all domains (including local)
        netServiceBrowser?.searchForServicesOfType(SERVICE_TYPE, inDomain = "local.")
        println("iOS: Bonjour browser started for type: $SERVICE_TYPE in domain: local.")

        // Also schedule the service on the run loop
        netService?.scheduleInRunLoop(NSRunLoop.currentRunLoop, forMode = NSDefaultRunLoopMode)
    }

    private fun handleResolvedService(service: NSNetService) {
        val serviceName = service.name
        println("iOS: Bonjour service resolved: $serviceName")

        val parts = serviceName.split("|")
        if (parts.size >= 3) {
            val pName = parts[0]
            val pId = parts[1]
            val pAddr = parts[2]

            if (pId != peerId) {
                println("iOS: Found peer via Bonjour: $pName ($pId) at $pAddr")
                // Create a discovery message to feed into the normal flow
                val discoveryJson = """{"type":"com.woutwerkman.game.model.NetworkMessage.Discovery","peer":{"id":"$pId","name":"$pName","address":"$pAddr","port":$DISCOVERY_PORT}}"""
                messageHandler?.invoke(discoveryJson)
            }
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

                        println("iOS: Found interface $name with IP $ip")

                        if (ip != null && !ip.startsWith("127.") && !ip.startsWith("169.254.") && !ip.startsWith("192.0.0.")) {
                            when {
                                // en0 is WiFi on iOS
                                name == "en0" -> wifiIp = ip
                                // bridge100 is hotspot interface
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
                    println("iOS: Selected IP $selectedIp (wifi=$wifiIp, cellular=$cellularIp, other=$otherIp)")
                    return selectedIp
                }
            }
        }
        return "0.0.0.0"
    }

    override suspend fun stopDiscovery() {
        isDiscovering = false
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

    override suspend fun broadcast(message: String) {
        // With Bonjour, we don't need to broadcast for discovery
        println("iOS: broadcast called (using Bonjour for discovery)")
    }

    override suspend fun sendTo(address: String, port: Int, message: String) {
        withContext(Dispatchers.Default) {
            println("iOS: sendTo $address:$port - ${message.take(50)}...")
            sendViaBsdSocket(address, port, message)
        }
    }

    private fun sendViaBsdSocket(address: String, port: Int, message: String) {
        memScoped {
            val sendSocket = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
            if (sendSocket < 0) {
                println("iOS: Failed to create send socket")
                return
            }

            val destAddr = alloc<sockaddr_in>()
            destAddr.sin_family = AF_INET.toUByte()
            destAddr.sin_port = htons(port.toUShort())
            inet_pton(AF_INET, address, destAddr.sin_addr.ptr)

            val data = message.encodeToByteArray()
            data.usePinned { pinned ->
                val bytesSent = sendto(
                    sendSocket,
                    pinned.addressOf(0),
                    data.size.toULong(),
                    0,
                    destAddr.ptr.reinterpret(),
                    sizeOf<sockaddr_in>().toUInt()
                )
                if (bytesSent < 0) {
                    println("iOS: sendto failed: ${posix_errno()}")
                } else {
                    println("iOS: Sent $bytesSent bytes to $address:$port")
                }
            }

            close(sendSocket)
        }
    }

    override suspend fun broadcastToConnected(message: String) {
        connectedPeers.values.forEach { peer ->
            sendTo(peer.address, peer.port, message)
        }
    }

    override suspend fun connectTo(address: String, port: Int) {
        val key = "$address:$port"
        connectedPeers[key] = PeerAddress(address, port)
    }

    override suspend fun startServer() {
        isServerRunning = true
    }

    override suspend fun stopServer() {
        isServerRunning = false
    }

    override suspend fun disconnectAll() {
        connectedPeers.clear()
    }

    override fun getLocalAddress(): String = localAddress

    override fun getLocalPort(): Int = localPort

    override fun cleanup() {
        isDiscovering = false
        isServerRunning = false
        receiveJob?.cancel()
        if (udpSocket >= 0) {
            close(udpSocket)
            udpSocket = -1
        }
        scope.cancel()
        netServiceBrowser?.stop()
        netService?.stop()
    }
}

// Separate delegate class for NSNetServiceBrowser to avoid conflicting overloads
private class NetServiceBrowserDelegate(
    private val peerId: String,
    private val onServiceFound: (NSNetService) -> Unit,
    private val onServiceRemoved: (NSNetService) -> Unit
) : NSObject(), NSNetServiceBrowserDelegateProtocol {

    override fun netServiceBrowser(browser: NSNetServiceBrowser, didFindService: NSNetService, moreComing: Boolean) {
        println("iOS: Bonjour browser didFindService: ${didFindService.name}, moreComing: $moreComing")
        // Filter out our own service
        if (!didFindService.name.contains(peerId)) {
            onServiceFound(didFindService)
        } else {
            println("iOS: Filtered out own service")
        }
    }

    override fun netServiceBrowser(browser: NSNetServiceBrowser, didNotSearch: Map<Any?, *>) {
        println("iOS: Bonjour browser did not search: $didNotSearch")
    }

    override fun netServiceBrowserWillSearch(browser: NSNetServiceBrowser) {
        println("iOS: Bonjour browser will search")
    }

    override fun netServiceBrowserDidStopSearch(browser: NSNetServiceBrowser) {
        println("iOS: Bonjour browser did stop search")
    }
}

// Delegate for publishing our own service
private class NetServicePublishDelegate : NSObject(), NSNetServiceDelegateProtocol {

    override fun netServiceDidPublish(sender: NSNetService) {
        println("iOS: Bonjour service published successfully: ${sender.name}")
    }

    override fun netService(sender: NSNetService, didNotPublish: Map<Any?, *>) {
        println("iOS: Bonjour service did NOT publish: ${sender.name} - $didNotPublish")
    }
}

// Separate delegate class for NSNetService resolution
private class NetServiceResolveDelegate(
    private val onResolved: (NSNetService) -> Unit
) : NSObject(), NSNetServiceDelegateProtocol {

    override fun netServiceDidResolveAddress(sender: NSNetService) {
        onResolved(sender)
    }

    override fun netService(sender: NSNetService, didNotResolve: Map<Any?, *>) {
        println("iOS: Bonjour service did not resolve: ${sender.name} - $didNotResolve")
    }
}
