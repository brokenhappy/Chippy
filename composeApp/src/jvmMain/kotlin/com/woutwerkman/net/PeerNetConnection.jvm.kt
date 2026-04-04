package com.woutwerkman.net

import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import java.net.*
import java.util.concurrent.ConcurrentHashMap
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener
import kotlin.random.Random

private const val MESSAGE_PORT = 47391

// Internal protocol prefixes - hidden from consumers
private const val HANDSHAKE_PREFIX = "_PN_HS_"
private const val HANDSHAKE_HELLO = "${HANDSHAKE_PREFIX}HELLO|"      // I see you
private const val HANDSHAKE_ACK = "${HANDSHAKE_PREFIX}ACK|"          // I confirm I see you too

internal actual suspend fun <T> withRawPeerNetConnectionImpl(
    config: PeerNetConfig,
    block: suspend CoroutineScope.(RawPeerNetConnection) -> T
): T {
    val peerId = generatePeerId()
    val incoming = Channel<RawPeerMessage>(Channel.BUFFERED)
    val outgoing = Channel<PeerCommand>(Channel.BUFFERED)

    return withJvmPeerTransport(
        peerId = peerId,
        peerName = config.displayName,
        serviceName = config.serviceName,
        incomingChannel = incoming,
        outgoingChannel = outgoing,
    ) { broadcastFn ->
        try {
            block(RawPeerNetConnection(peerId, incoming, outgoing, broadcastFn))
        } finally {
            incoming.close()
            outgoing.close()
        }
    }
}

private fun generatePeerId(): String {
    val timestamp = System.currentTimeMillis()
    val random = Random.nextLong(0, Long.MAX_VALUE).toString(36)
    return "jvm-${timestamp.toString(36)}-$random"
}

/**
 * Tracks the state of a discovered peer through the handshake process.
 */
private data class PeerState(
    val info: PeerInfo,
    val weSeeThemViaDiscovery: Boolean = false,  // We found them via mDNS/dns-sd
    val weSentHello: Boolean = false,             // We sent HELLO to them
    val theyAckedUs: Boolean = false,             // They sent ACK (confirming they see us)
    val weAckedThem: Boolean = false,             // We sent ACK to them
    val isJoined: Boolean = false                 // Fully connected, Joined event sent
)

/**
 * Events from discovery callbacks (JmDNS/dns-sd), bridged into the coroutine world.
 */
private sealed class DiscoveryEvent {
    data class PeerDiscovered(val serviceName: String) : DiscoveryEvent()
    data class PeerRemoved(val serviceName: String) : DiscoveryEvent()
    data class PeerJoined(val peerId: String) : DiscoveryEvent()
}

/**
 * Resource function that sets up the JVM peer transport, runs [block] with a
 * broadcastDirect function, and tears down all resources when [block] completes.
 */
private suspend fun <T> withJvmPeerTransport(
    peerId: String,
    peerName: String,
    serviceName: String,
    incomingChannel: SendChannel<RawPeerMessage>,
    outgoingChannel: ReceiveChannel<PeerCommand>,
    block: suspend CoroutineScope.((ByteArray) -> Unit) -> T,
): T {
    val serviceType = "_${serviceName}._udp.local."
    val peerStates = ConcurrentHashMap<String, PeerState>()
    val discoveryEvents = Channel<DiscoveryEvent>(Channel.BUFFERED)

    val localAddress = getLocalIpAddress()
    println("[PeerNet-$peerId] Local IP: $localAddress")

    val udpSocket = createUdpSocket(peerId)
    val localPort = udpSocket.localPort
    println("[PeerNet-$peerId] Bound to port $localPort")

    val broadcastFn: (ByteArray) -> Unit = { payload ->
        val message = "$peerId:${String(payload, Charsets.UTF_8)}"
        peerStates.values.forEach { state ->
            sendUdp(udpSocket, state.info.address, state.info.port, message)
        }
    }

    val jmdns = startJmdns(peerId, localAddress, localPort, serviceType, peerName, discoveryEvents)

    val isMac = System.getProperty("os.name").lowercase().contains("mac")
    val dnsSdProcess: Process? = if (isMac) {
        val browseServiceType = "_${serviceName}._udp."
        ProcessBuilder("dns-sd", "-B", browseServiceType, "local.")
            .redirectErrorStream(true)
            .start()
    } else null

    try {
        return coroutineScope {
            launch(Dispatchers.IO) {
                listenForMessages(udpSocket, peerId, peerStates, incomingChannel, discoveryEvents)
            }
            launch {
                processOutgoingCommands(outgoingChannel, peerId, peerStates, udpSocket)
            }
            launch {
                retryUnacknowledgedHandshakes(peerStates, peerId, peerName, localAddress, localPort, udpSocket)
            }
            launch {
                processDiscoveryEvents(discoveryEvents, peerStates, incomingChannel, peerId)
            }
            if (dnsSdProcess != null) {
                launch(Dispatchers.IO) {
                    readDnsSdOutput(dnsSdProcess, serviceName, peerId, discoveryEvents)
                }
            }

            try {
                coroutineScope { block(broadcastFn) }
            } finally {
                // Destroy dns-sd process first to unblock the blocking readLine() call,
                // then cancel infrastructure coroutines so coroutineScope can exit.
                dnsSdProcess?.destroyForcibly()
                coroutineContext.cancelChildren()
            }
        }
    } finally {
        withContext(NonCancellable) {
            println("[PeerNet-$peerId] Stopping")
            try { dnsSdProcess?.destroyForcibly() } catch (_: Exception) {}
            try {
                jmdns.unregisterAllServices()
                jmdns.close()
            } catch (_: Exception) {}
            try { udpSocket.close() } catch (_: Exception) {}
            println("[PeerNet-$peerId] Stopped")
        }
    }
}

private fun createUdpSocket(peerId: String): DatagramSocket {
    fun DatagramSocket.configure() = apply {
        reuseAddress = true
        broadcast = true
        // 100ms receive timeout: makes the blocking receive() return periodically so the
        // coroutine can check for cancellation. Lower = more responsive shutdown, higher =
        // less CPU. 100ms is a good balance — cancellation feels instant to humans.
        soTimeout = 100
    }
    return try {
        DatagramSocket(MESSAGE_PORT).configure()
    } catch (e: Exception) {
        println("[PeerNet-$peerId] MESSAGE_PORT $MESSAGE_PORT busy, using random port")
        DatagramSocket(0).configure()
    }
}

private fun startJmdns(
    peerId: String,
    localAddress: String,
    localPort: Int,
    serviceType: String,
    peerName: String,
    discoveryEvents: SendChannel<DiscoveryEvent>,
): JmDNS {
    val inetAddress = InetAddress.getByName(localAddress)
    val jmdns = JmDNS.create(inetAddress, localAddress)
    println("[PeerNet-$peerId] JmDNS created on $localAddress")

    jmdns.addServiceListener(serviceType, object : ServiceListener {
        override fun serviceAdded(event: ServiceEvent) {
            println("[PeerNet-$peerId] mDNS service added: ${event.name}")
            jmdns.requestServiceInfo(event.type, event.name, true)
        }

        override fun serviceRemoved(event: ServiceEvent) {
            println("[PeerNet-$peerId] mDNS service removed: ${event.name}")
            discoveryEvents.trySend(DiscoveryEvent.PeerRemoved(event.name))
        }

        override fun serviceResolved(event: ServiceEvent) {
            println("[PeerNet-$peerId] mDNS service resolved: ${event.name}")
            discoveryEvents.trySend(DiscoveryEvent.PeerDiscovered(event.name))
        }
    })

    val fullServiceName = "$peerName|$peerId|$localAddress|$localPort"
    val serviceInfo = ServiceInfo.create(serviceType, fullServiceName, localPort, "PeerNet")
    jmdns.registerService(serviceInfo)
    println("[PeerNet-$peerId] mDNS service registered: $fullServiceName")

    return jmdns
}

private suspend fun processDiscoveryEvents(
    events: ReceiveChannel<DiscoveryEvent>,
    peerStates: ConcurrentHashMap<String, PeerState>,
    incomingChannel: SendChannel<RawPeerMessage>,
    peerId: String,
) {
    for (event in events) {
        when (event) {
            is DiscoveryEvent.PeerDiscovered -> {
                val parts = event.serviceName.split("|")
                if (parts.size >= 3) {
                    val pName = parts[0]
                    val pId = parts[1]
                    val pAddr = parts[2]
                    val pPort = parts.getOrNull(3)?.toIntOrNull() ?: MESSAGE_PORT

                    if (pId == peerId) continue

                    val peerInfo = PeerInfo(id = pId, name = pName, address = pAddr, port = pPort)

                    peerStates.compute(pId) { _, existing ->
                        if (existing == null) {
                            println("[PeerNet-$peerId] New peer discovered: $pName ($pId) at $pAddr")
                            PeerState(info = peerInfo, weSeeThemViaDiscovery = true)
                        } else {
                            existing.copy(weSeeThemViaDiscovery = true)
                        }
                    }
                }
            }
            is DiscoveryEvent.PeerRemoved -> {
                val parts = event.serviceName.split("|")
                if (parts.size >= 2) {
                    val pId = parts[1]
                    val state = peerStates.remove(pId)
                    if (state?.isJoined == true) {
                        incomingChannel.send(RawPeerMessage.Event.Disconnected(pId))
                    }
                }
            }
            is DiscoveryEvent.PeerJoined -> {
                var connected: PeerInfo? = null
                peerStates.compute(event.peerId) { _, existing ->
                    if (existing != null && !existing.isJoined) {
                        connected = existing.info
                        existing.copy(isJoined = true)
                    } else {
                        existing
                    }
                }
                connected?.let { info ->
                    println("[PeerNet-$peerId] Peer JOINED: ${info.name} (${info.id})")
                    incomingChannel.send(RawPeerMessage.Event.Connected(info))
                }
            }
        }
    }
}

/**
 * Retries HELLO to discovered-but-not-yet-joined peers every 1 second. UDP is unreliable, so
 * the first HELLO (sent immediately on mDNS discovery) may be lost. 1s strikes a balance: fast
 * enough that a single dropped packet only adds 1s of latency, slow enough to avoid flooding
 * the network when multiple peers are discovering simultaneously.
 */
private suspend fun retryUnacknowledgedHandshakes(
    peerStates: ConcurrentHashMap<String, PeerState>,
    peerId: String,
    peerName: String,
    localAddress: String,
    localPort: Int,
    udpSocket: DatagramSocket,
) {
    while (true) {
        delay(1.seconds)

        peerStates.forEach { (pId, state) ->
            if (state.weSeeThemViaDiscovery && !state.isJoined) {
                sendHandshakeHello(udpSocket, peerId, peerName, localAddress, localPort, state.info)
                peerStates.compute(pId) { _, current ->
                    current?.copy(weSentHello = true)
                }
            }
        }
    }
}

private suspend fun listenForMessages(
    udpSocket: DatagramSocket,
    peerId: String,
    peerStates: ConcurrentHashMap<String, PeerState>,
    incomingChannel: SendChannel<RawPeerMessage>,
    discoveryEvents: SendChannel<DiscoveryEvent>,
) {
    val buffer = ByteArray(65535)
    println("[PeerNet-$peerId] Listening for messages on port ${udpSocket.localPort}")

    while (true) {
        try {
            val packet = DatagramPacket(buffer, buffer.size)
            withContext(Dispatchers.IO) {
                udpSocket.receive(packet)
            }

            val data = packet.data.copyOf(packet.length)
            val message = String(data, Charsets.UTF_8)

            // Parse: "senderId:payload"
            val separatorIndex = message.indexOf(':')
            if (separatorIndex > 0) {
                val fromPeerId = message.substring(0, separatorIndex)
                if (fromPeerId == peerId) continue // Ignore our own messages

                val payload = message.substring(separatorIndex + 1)

                when {
                    payload.startsWith(HANDSHAKE_HELLO) -> {
                        handleHelloReceived(
                            fromPeerId, payload, packet.address.hostAddress ?: "", packet.port,
                            peerId, peerStates, discoveryEvents, udpSocket,
                        )
                    }
                    payload.startsWith(HANDSHAKE_ACK) -> {
                        handleAckReceived(fromPeerId, peerStates, discoveryEvents)
                    }
                    else -> {
                        // Application data - forward if we've seen this peer at all
                        val state = peerStates[fromPeerId]
                        if (state != null) {
                            incomingChannel.send(RawPeerMessage.Received(fromPeerId, payload.toByteArray(Charsets.UTF_8)))
                        }
                    }
                }
            }
        } catch (_: java.net.SocketTimeoutException) {
            // Expected: soTimeout (100ms) fires to allow cancellation checks
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("[PeerNet-$peerId] Socket receive error: ${e::class.simpleName}: ${e.message}")
        }
    }
}

/**
 * When we receive HELLO from a peer, we know they can see us.
 * We send ACK back and also mark them as discovered if we haven't seen them via mDNS.
 */
private fun handleHelloReceived(
    fromPeerId: String,
    payload: String,
    fromAddress: String,
    fromPort: Int,
    peerId: String,
    peerStates: ConcurrentHashMap<String, PeerState>,
    discoveryEvents: SendChannel<DiscoveryEvent>,
    udpSocket: DatagramSocket,
) {
    val helloData = payload.removePrefix(HANDSHAKE_HELLO)
    val parts = helloData.split("|")
    val pName = parts.getOrNull(0) ?: "Unknown"
    var pAddr = parts.getOrNull(2) ?: fromAddress
    var pPort = parts.getOrNull(3)?.toIntOrNull() ?: MESSAGE_PORT

    // If peer is an Android emulator (sending 10.0.2.15), the emulator's NAT translates
    // both address and port. We must use the packet's actual source address AND port
    // to route responses back through the NAT correctly.
    if (pAddr == "10.0.2.15") {
        println("[PeerNet-$peerId] Mapping emulator 10.0.2.15:$pPort to packet source $fromAddress:$fromPort")
        pAddr = fromAddress
        pPort = fromPort
    }

    val peerInfo = PeerInfo(id = fromPeerId, name = pName, address = pAddr, port = pPort)

    val state = peerStates.compute(fromPeerId) { _, existing ->
        if (existing == null) {
            println("[PeerNet-$peerId] Received HELLO from new peer: $pName ($fromPeerId)")
            PeerState(info = peerInfo, weSeeThemViaDiscovery = true, weAckedThem = true)
        } else {
            existing.copy(info = peerInfo, weSeeThemViaDiscovery = true, weAckedThem = true)
        }
    }!!

    // Always send ACK in reply to HELLO — the peer may have missed our earlier ACK
    sendHandshakeAck(udpSocket, peerId, peerInfo)

    checkAndEmitJoined(fromPeerId, state, discoveryEvents)
}

/**
 * When we receive ACK, the peer confirmed they see us.
 */
private fun handleAckReceived(
    fromPeerId: String,
    peerStates: ConcurrentHashMap<String, PeerState>,
    discoveryEvents: SendChannel<DiscoveryEvent>,
) {
    val state = peerStates.compute(fromPeerId) { _, existing ->
        existing?.copy(theyAckedUs = true)
    } ?: return
    checkAndEmitJoined(fromPeerId, state, discoveryEvents)
}

/**
 * Emit Joined event if handshake is complete (both sides confirmed).
 * Uses trySend to discoveryEvents channel since this is called from non-suspend contexts.
 */
private fun checkAndEmitJoined(peerId: String, state: PeerState, discoveryEvents: SendChannel<DiscoveryEvent>) {
    if (state.weSeeThemViaDiscovery && state.theyAckedUs && !state.isJoined) {
        discoveryEvents.trySend(DiscoveryEvent.PeerJoined(peerId))
    }
}

private fun sendHandshakeHello(
    udpSocket: DatagramSocket,
    peerId: String,
    peerName: String,
    localAddress: String,
    localPort: Int,
    peer: PeerInfo,
) {
    val payload = "$HANDSHAKE_HELLO$peerName|$peerId|$localAddress|$localPort"
    println("[PeerNet-$peerId] Sending HELLO to ${peer.name} at ${peer.address}:${peer.port}")
    sendUdp(udpSocket, peer.address, peer.port, "$peerId:$payload")
}

private fun sendHandshakeAck(udpSocket: DatagramSocket, peerId: String, peer: PeerInfo) {
    val payload = "$HANDSHAKE_ACK$peerId"
    sendUdp(udpSocket, peer.address, peer.port, "$peerId:$payload")
}

private fun sendUdp(udpSocket: DatagramSocket, address: String, port: Int, message: String) {
    try {
        val data = message.toByteArray(Charsets.UTF_8)
        val packet = DatagramPacket(data, data.size, InetAddress.getByName(address), port)
        udpSocket.send(packet)
    } catch (e: Exception) {
        // Silently ignore send errors — peer may be unreachable
    }
}

private suspend fun processOutgoingCommands(
    outgoingChannel: ReceiveChannel<PeerCommand>,
    peerId: String,
    peerStates: ConcurrentHashMap<String, PeerState>,
    udpSocket: DatagramSocket,
) {
    for (command in outgoingChannel) {
        when (command) {
            is PeerCommand.SendTo -> {
                val state = peerStates[command.peerId]
                if (state != null) {
                    val payload = "$peerId:${String(command.payload, Charsets.UTF_8)}"
                    sendUdp(udpSocket, state.info.address, state.info.port, payload)
                }
            }
            is PeerCommand.Broadcast -> {
                peerStates.values.forEach { state ->
                    val payload = "$peerId:${String(command.payload, Charsets.UTF_8)}"
                    sendUdp(udpSocket, state.info.address, state.info.port, payload)
                }
            }
        }
    }
}

/**
 * Reads dns-sd browse output and sends discovery events.
 * This function blocks on readLine() — cancellation is handled by destroying the process
 * from the caller's finally block, which causes readLine() to return null.
 */
private fun readDnsSdOutput(
    process: Process,
    serviceName: String,
    peerId: String,
    discoveryEvents: SendChannel<DiscoveryEvent>,
) {
    val browseServiceType = "_${serviceName}._udp."
    val reader = process.inputStream.bufferedReader()
    try {
        // Non-cancellable blocking loop — process destruction (in caller's finally) breaks it
        while (true) {
            val line = reader.readLine() ?: break
            if (line.contains("Add") && line.contains(browseServiceType)) {
                val instanceNameStart = line.indexOf(browseServiceType)
                if (instanceNameStart >= 0) {
                    val instanceName = line.substring(instanceNameStart + browseServiceType.length).trim()
                    discoveryEvents.trySend(DiscoveryEvent.PeerDiscovered(instanceName))
                }
            }
        }
    } catch (_: Exception) {
        // Process was likely destroyed, exit gracefully
    }
}

private fun getLocalIpAddress(): String {
    try {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        var preferredIp: String? = null
        var fallbackIp: String? = null

        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            if (networkInterface.isLoopback || !networkInterface.isUp) continue

            val name = networkInterface.name.lowercase()
            if (name.startsWith("utun") || name.startsWith("tun") || name.startsWith("tap")) continue

            val addresses = networkInterface.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (address is Inet4Address && !address.isLoopbackAddress) {
                    val ip = address.hostAddress ?: continue
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
    } catch (e: Exception) {
        println("[PeerNet] Error getting local IP: ${e.message}")
    }
    return "127.0.0.1"
}
