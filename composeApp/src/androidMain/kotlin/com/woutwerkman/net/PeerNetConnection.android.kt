package com.woutwerkman.net

import kotlin.time.Duration.Companion.milliseconds
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
private const val HANDSHAKE_HELLO = "${HANDSHAKE_PREFIX}HELLO|"
private const val HANDSHAKE_ACK = "${HANDSHAKE_PREFIX}ACK|"

internal actual suspend fun <T> withRawPeerNetConnectionImpl(
    config: PeerNetConfig,
    block: suspend CoroutineScope.(RawPeerNetConnection) -> T
): T {
    val peerId = generatePeerId()
    val incoming = Channel<RawPeerMessage>(Channel.BUFFERED)
    val outgoing = Channel<PeerCommand>(Channel.BUFFERED)

    return withAndroidPeerTransport(
        peerId = peerId,
        peerName = config.displayName,
        serviceName = config.serviceName,
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

private fun generatePeerId(): String {
    val timestamp = System.currentTimeMillis()
    val random = Random.nextLong(0, Long.MAX_VALUE).toString(36)
    return "android-${timestamp.toString(36)}-$random"
}

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
)

/**
 * Events from discovery callbacks (JmDNS), bridged into the coroutine world.
 */
private sealed class DiscoveryEvent {
    data class PeerDiscovered(val serviceName: String) : DiscoveryEvent()
    data class PeerRemoved(val serviceName: String) : DiscoveryEvent()
    data class PeerJoined(val peerId: String) : DiscoveryEvent()
    data class EmulatorProbe(val address: String, val port: Int, val helloPayload: String) : DiscoveryEvent()
}

/**
 * Resource function that sets up the Android peer transport, runs [block] with a
 * broadcastDirect function, and tears down all resources when [block] completes.
 */
private suspend fun <T> withAndroidPeerTransport(
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
        val message = "$peerId:${String(payload)}"
        peerStates.values.forEach { state ->
            sendUdp(udpSocket, state.info.address, state.info.port, message)
        }
    }

    val jmdns = startJmdns(peerId, localAddress, localPort, serviceType, peerName, discoveryEvents)

    try {
        return coroutineScope {
            val childTasks = launch {
                launch(Dispatchers.IO) {
                    listenForMessages(udpSocket, peerId, peerStates, incomingChannel, discoveryEvents, localAddress, localPort, peerName)
                }
                launch {
                    processOutgoingCommands(outgoingChannel, peerId, peerStates, udpSocket)
                }
                launch {
                    retryUnacknowledgedHandshakes(peerStates, peerId, peerName, localAddress, localPort, udpSocket)
                }
                launch {
                    processDiscoveryEvents(discoveryEvents, peerStates, incomingChannel, peerId, peerName, localAddress, localPort, udpSocket)
                }
                launch {
                    pollMdnsAsJmdnsFallback(jmdns, serviceType, discoveryEvents)
                }
            }

            try {
                coroutineScope { block(broadcastFn) }
            } finally {
                childTasks.cancel()
            }
        }
    } finally {
        withContext(NonCancellable) {
            println("[PeerNet-$peerId] Stopping")
            try { jmdns.unregisterAllServices(); jmdns.close() } catch (_: Exception) {}
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
        println("[PeerNet-$peerId] Port $MESSAGE_PORT busy, using random port")
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
    val addr = InetAddress.getByName(localAddress)
    val jmdns = JmDNS.create(addr, "peer-android-$peerId")

    jmdns.addServiceListener(serviceType, object : ServiceListener {
        override fun serviceAdded(event: ServiceEvent) {
            jmdns.requestServiceInfo(event.type, event.name, true)
        }

        override fun serviceRemoved(event: ServiceEvent) {
            discoveryEvents.trySend(DiscoveryEvent.PeerRemoved(event.name))
        }

        override fun serviceResolved(event: ServiceEvent) {
            discoveryEvents.trySend(DiscoveryEvent.PeerDiscovered(event.name))
        }
    })

    val fullServiceName = "$peerName|$peerId|$localAddress|$localPort"
    val serviceInfo = ServiceInfo.create(serviceType, fullServiceName, localPort, "PeerNet")
    jmdns.registerService(serviceInfo)
    println("[PeerNet-$peerId] mDNS service registered: $fullServiceName")

    return jmdns
}

/**
 * JmDNS's passive [ServiceListener] sometimes misses service events on Android, especially
 * on emulators. This fallback actively queries mDNS to catch anything the listener dropped.
 *
 * - 500ms initial delay: gives the passive listener a chance to fire first, avoiding duplicate work
 *   in the happy path where the listener works fine.
 * - 5s repeat interval: services don't appear/disappear frequently on a LAN, so aggressive polling
 *   isn't needed after the initial catch-up. 5s keeps network/CPU overhead negligible.
 */
private suspend fun pollMdnsAsJmdnsFallback(
    jmdns: JmDNS,
    serviceType: String,
    discoveryEvents: SendChannel<DiscoveryEvent>,
) {
    delay(500.milliseconds)
    while (true) {
        try {
            val services = jmdns.list(serviceType)
            services?.forEach {
                discoveryEvents.trySend(DiscoveryEvent.PeerDiscovered(it.name))
            }
        } catch (_: Exception) {}
        delay(5.seconds)
    }
}

private suspend fun processDiscoveryEvents(
    events: ReceiveChannel<DiscoveryEvent>,
    peerStates: ConcurrentHashMap<String, PeerState>,
    incomingChannel: SendChannel<RawPeerMessage>,
    peerId: String,
    peerName: String,
    localAddress: String,
    localPort: Int,
    udpSocket: DatagramSocket,
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
                            println("[PeerNet-$peerId] Peer discovered: $pName ($pId)")
                            PeerState(info = peerInfo, weSeeThemViaDiscovery = true)
                        } else {
                            existing.copy(weSeeThemViaDiscovery = true)
                        }
                    }
                    sendHandshakeHello(udpSocket, peerId, peerName, localAddress, localPort, peerInfo)
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
            is DiscoveryEvent.EmulatorProbe -> {
                // 500ms delay: the emulator NAT needs time to set up the reverse mapping after
                // receiving inbound traffic. Probing immediately often fails because the NAT
                // hasn't opened the return path yet.
                delay(500.milliseconds)
                sendUdp(udpSocket, event.address, event.port, event.helloPayload)
            }
        }
    }
}

/**
 * Retries HELLO to discovered-but-not-yet-joined peers every 1 second. UDP is unreliable, so
 * the first HELLO (sent immediately on mDNS discovery) may be lost. 1s strikes a balance: fast
 * enough that a single dropped packet only adds 1s of latency, slow enough to avoid flooding
 * the network when multiple peers are discovering simultaneously.
 *
 * On Android emulators (10.0.2.15), also probes the host gateway (10.0.2.2) since mDNS doesn't
 * cross the emulator NAT — this is the only way to discover the host JVM peer.
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

        if (localAddress == "10.0.2.15") {
            sendUdp(udpSocket, "10.0.2.2", MESSAGE_PORT, "$peerId:$HANDSHAKE_HELLO$peerName|$peerId|$localAddress|$localPort")
        }

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
    localAddress: String,
    localPort: Int,
    peerName: String,
) {
    val buffer = ByteArray(65535)
    while (true) {
        try {
            val packet = DatagramPacket(buffer, buffer.size)
            withContext(Dispatchers.IO) { udpSocket.receive(packet) }
            val data = packet.data.copyOf(packet.length)
            val message = String(data, Charsets.UTF_8)
            val separatorIndex = message.indexOf(':')
            if (separatorIndex > 0) {
                val fromPeerId = message.substring(0, separatorIndex)
                if (fromPeerId == peerId) continue
                val payload = message.substring(separatorIndex + 1)
                when {
                    payload.startsWith(HANDSHAKE_HELLO) -> {
                        handleHelloReceived(
                            fromPeerId, payload, packet.address.hostAddress ?: "", packet.port,
                            peerId, peerStates, discoveryEvents, udpSocket, localAddress, localPort, peerName,
                        )
                    }
                    payload.startsWith(HANDSHAKE_ACK) -> {
                        handleAckReceived(fromPeerId, peerStates, discoveryEvents)
                    }
                    else -> {
                        val state = peerStates[fromPeerId]
                        if (state != null) {
                            incomingChannel.send(RawPeerMessage.Received(fromPeerId, payload.toByteArray(Charsets.UTF_8)))
                        }
                    }
                }
            }
        } catch (_: SocketTimeoutException) {
            // Expected: soTimeout (100ms) fires to allow cancellation checks
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("[PeerNet-$peerId] Receive error: ${e.message}")
        }
    }
}

private fun handleHelloReceived(
    fromPeerId: String,
    payload: String,
    fromAddress: String,
    fromPort: Int,
    peerId: String,
    peerStates: ConcurrentHashMap<String, PeerState>,
    discoveryEvents: SendChannel<DiscoveryEvent>,
    udpSocket: DatagramSocket,
    localAddress: String,
    localPort: Int,
    peerName: String,
) {
    val helloData = payload.removePrefix(HANDSHAKE_HELLO)
    val parts = helloData.split("|")
    val pName = parts.getOrNull(0) ?: "Unknown"
    var pAddr = parts.getOrNull(2) ?: fromAddress
    var pPort = parts.getOrNull(3)?.toIntOrNull() ?: MESSAGE_PORT

    if (pAddr == "10.0.2.15") {
        pAddr = fromAddress
        pPort = fromPort
    }

    // If we are an emulator and the peer is not, route via the host gateway (10.0.2.2).
    // The peer's self-reported address (e.g. 192.168.178.x) is unreachable from inside the
    // emulator NAT. We must use 10.0.2.2 for ALL communication with this peer — including
    // the stored address (for ACKs) and the discovery probe.
    if (localAddress == "10.0.2.15" && !pAddr.startsWith("10.0.2.") && pAddr != "127.0.0.1") {
        println("[PeerNet-$peerId] We are emulator, peer is $pAddr. Routing via 10.0.2.2:$pPort")
        pAddr = "10.0.2.2"
        discoveryEvents.trySend(
            DiscoveryEvent.EmulatorProbe(
                address = "10.0.2.2",
                port = pPort,
                helloPayload = "$peerId:$HANDSHAKE_HELLO$peerName|$peerId|$localAddress|$localPort",
            )
        )
    }

    val peerInfo = PeerInfo(id = fromPeerId, name = pName, address = pAddr, port = pPort)
    val state = peerStates.compute(fromPeerId) { _, existing ->
        if (existing == null) PeerState(info = peerInfo, weSeeThemViaDiscovery = true, weAckedThem = true)
        else existing.copy(info = peerInfo, weSeeThemViaDiscovery = true, weAckedThem = true)
    }!!

    sendHandshakeAck(udpSocket, peerId, peerInfo)
    checkAndEmitJoined(fromPeerId, state, discoveryEvents)
}

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
    } catch (_: Exception) {}
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
                peerStates[command.peerId]?.let {
                    sendUdp(udpSocket, it.info.address, it.info.port, "$peerId:${String(command.payload)}")
                }
            }
            is PeerCommand.Broadcast -> {
                peerStates.values.forEach {
                    sendUdp(udpSocket, it.info.address, it.info.port, "$peerId:${String(command.payload)}")
                }
            }
        }
    }
}

private fun getLocalIpAddress(): String {
    try {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        var fallback: String? = null
        while (interfaces.hasMoreElements()) {
            val ni = interfaces.nextElement()
            if (ni.isLoopback || !ni.isUp) continue
            val addrs = ni.inetAddresses
            while (addrs.hasMoreElements()) {
                val addr = addrs.nextElement()
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    val ip = addr.hostAddress ?: continue
                    if (ip == "10.0.2.15") return ip
                    fallback = ip
                }
            }
        }
        return fallback ?: "127.0.0.1"
    } catch (_: Exception) {}
    return "127.0.0.1"
}
