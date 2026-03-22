package com.woutwerkman.net

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

internal actual suspend fun <T> withPeerNetConnectionImpl(
    config: PeerNetConfig,
    block: suspend CoroutineScope.(PeerNetConnection) -> T
): T = coroutineScope {
    val peerId = generatePeerId()
    val incoming = Channel<PeerMessage>(Channel.BUFFERED)
    val outgoing = Channel<PeerCommand>(Channel.BUFFERED)

    val transport = JvmPeerTransport(
        peerId = peerId,
        peerName = config.displayName,
        serviceName = config.serviceName,
        incomingChannel = incoming,
        outgoingChannel = outgoing
    )

    try {
        transport.start(this)
        val connection = PeerNetConnection(incoming, outgoing)
        block(connection)
    } finally {
        transport.stop()
        incoming.close()
        outgoing.close()
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
    var weSeeThemViaDiscovery: Boolean = false,  // We found them via mDNS/dns-sd
    var weSentHello: Boolean = false,             // We sent HELLO to them
    var theyAckedUs: Boolean = false,             // They sent ACK (confirming they see us)
    var weAckedThem: Boolean = false,             // We sent ACK to them
    var isJoined: Boolean = false                 // Fully connected, Joined event sent
)

private class JvmPeerTransport(
    private val peerId: String,
    private val peerName: String,
    private val serviceName: String,
    private val incomingChannel: SendChannel<PeerMessage>,
    private val outgoingChannel: ReceiveChannel<PeerCommand>
) {
    private var jmdns: JmDNS? = null
    private var serviceInfo: ServiceInfo? = null
    private var udpSocket: DatagramSocket? = null
    private var isRunning = false
    private val peerStates = ConcurrentHashMap<String, PeerState>()
    private val serviceType = "_${serviceName}._udp.local."
    private var localAddress: String = "0.0.0.0"
    private var localPort: Int = 0
    private var dnsSdProcess: Process? = null
    private var scope: CoroutineScope? = null

    fun start(scope: CoroutineScope) {
        this.scope = scope
        isRunning = true

        scope.launch(Dispatchers.IO) {
            try {
                localAddress = getLocalIpAddress()
                println("[PeerNet-$peerId] Local IP: $localAddress")

                // Start UDP socket for messaging - use port 0 to let OS assign
                udpSocket = DatagramSocket(0).apply {
                    reuseAddress = true
                    broadcast = true
                    soTimeout = 100 // 100ms timeout for receive
                }
                localPort = udpSocket!!.localPort
                println("[PeerNet-$peerId] Bound to port $localPort")

                // Start mDNS with our actual port
                startMdns(this)

                // Listen for UDP messages
                launch { listenForMessages() }

                // Process outgoing commands from consumers
                launch { processOutgoingCommands() }

                // Periodic handshake maintenance
                launch { handshakeMaintenance() }

            } catch (e: Exception) {
                println("[PeerNet-$peerId] Error starting: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    /**
     * Periodically sends HELLOs to peers we've discovered but haven't completed handshake with.
     */
    private suspend fun handshakeMaintenance() {
        while (isRunning) {
            delay(1000)

            peerStates.values.forEach { state ->
                // If we see them via discovery but handshake isn't complete, send HELLO
                if (state.weSeeThemViaDiscovery && !state.isJoined) {
                    sendHandshakeHello(state.info)
                    state.weSentHello = true
                }
            }
        }
    }

    private fun startMdns(scope: CoroutineScope) {
        try {
            // Create JmDNS bound to the WiFi interface IP so services are visible to other devices
            val inetAddress = InetAddress.getByName(localAddress)
            jmdns = JmDNS.create(inetAddress, localAddress)
            println("[PeerNet-$peerId] JmDNS created on $localAddress")

            jmdns?.addServiceListener(serviceType, object : ServiceListener {
                override fun serviceAdded(event: ServiceEvent) {
                    println("[PeerNet-$peerId] mDNS service added: ${event.name}")
                    jmdns?.requestServiceInfo(event.type, event.name, true)
                }

                override fun serviceRemoved(event: ServiceEvent) {
                    println("[PeerNet-$peerId] mDNS service removed: ${event.name}")
                    handlePeerRemoved(event.name)
                }

                override fun serviceResolved(event: ServiceEvent) {
                    println("[PeerNet-$peerId] mDNS service resolved: ${event.name}")
                    handlePeerDiscovered(event.name)
                }
            })

            // Register our service - format: displayName|peerId|address|port
            val fullServiceName = "$peerName|$peerId|$localAddress|$localPort"
            serviceInfo = ServiceInfo.create(serviceType, fullServiceName, localPort, "PeerNet")
            jmdns?.registerService(serviceInfo)
            println("[PeerNet-$peerId] mDNS service registered: $fullServiceName")

            // Fallback: dns-sd on macOS for iPhone hotspot discovery
            if (System.getProperty("os.name").lowercase().contains("mac")) {
                scope.launch(Dispatchers.IO) {
                    discoverViaDnsSd()
                }
            }
        } catch (e: Exception) {
            println("[PeerNet-$peerId] Error starting mDNS: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun handlePeerDiscovered(serviceName: String) {
        val parts = serviceName.split("|")
        if (parts.size >= 3) {
            val pName = parts[0]
            val pId = parts[1]
            val pAddr = parts[2]
            val pPort = parts.getOrNull(3)?.toIntOrNull() ?: MESSAGE_PORT

            if (pId == peerId) return // Ignore self

            val peerInfo = PeerInfo(id = pId, name = pName, address = pAddr, port = pPort)

            peerStates.compute(pId) { _, existing ->
                if (existing == null) {
                    println("[PeerNet-$peerId] New peer discovered: $pName ($pId) at $pAddr")
                    PeerState(info = peerInfo, weSeeThemViaDiscovery = true)
                } else {
                    existing.weSeeThemViaDiscovery = true
                    existing
                }
            }

            // Immediately send HELLO
            sendHandshakeHello(peerInfo)
        }
    }

    private fun handlePeerRemoved(serviceName: String) {
        val parts = serviceName.split("|")
        if (parts.size >= 2) {
            val pId = parts[1]
            val state = peerStates.remove(pId)
            if (state?.isJoined == true) {
                scope?.launch {
                    incomingChannel.send(PeerMessage.Event.Left(pId))
                }
            }
        }
    }

    private suspend fun discoverViaDnsSd() {
        try {
            val browseServiceType = "_${serviceName}._udp."
            val process = ProcessBuilder("dns-sd", "-B", browseServiceType, "local.")
                .redirectErrorStream(true)
                .start()
            dnsSdProcess = process

            val reader = process.inputStream.bufferedReader()

            while (isRunning) {
                val line = reader.readLine() ?: break
                if (line.contains("Add") && line.contains(browseServiceType)) {
                    val instanceNameStart = line.indexOf(browseServiceType)
                    if (instanceNameStart >= 0) {
                        val instanceName = line.substring(instanceNameStart + browseServiceType.length).trim()
                        handlePeerDiscovered(instanceName)
                    }
                }
            }

            process.destroy()
        } catch (e: Exception) {
            println("[PeerNet-$peerId] dns-sd error: ${e.message}")
        }
    }

    private suspend fun listenForMessages() {
        val buffer = ByteArray(65535)
        println("[PeerNet-$peerId] Listening for messages on port $localPort")

        while (isRunning && udpSocket != null) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                withContext(Dispatchers.IO) {
                    udpSocket?.receive(packet)
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
                            handleHelloReceived(fromPeerId, payload, packet.address.hostAddress ?: "")
                        }
                        payload.startsWith(HANDSHAKE_ACK) -> {
                            handleAckReceived(fromPeerId)
                        }
                        else -> {
                            // Application data - only forward if peer is joined
                            val state = peerStates[fromPeerId]
                            if (state?.isJoined == true) {
                                incomingChannel.send(PeerMessage.Received(fromPeerId, payload.toByteArray(Charsets.UTF_8)))
                            }
                        }
                    }
                }
            } catch (e: java.net.SocketTimeoutException) {
                // Normal timeout, continue polling
            } catch (e: Exception) {
                if (isRunning) {
                    println("[PeerNet-$peerId] Socket receive error: ${e::class.simpleName}: ${e.message}")
                }
            }
        }
    }

    /**
     * When we receive HELLO from a peer, we know they can see us.
     * We send ACK back and also mark them as discovered if we haven't seen them via mDNS.
     */
    private fun handleHelloReceived(fromPeerId: String, payload: String, fromAddress: String) {
        // Parse HELLO payload: "peerName|peerId|address|port"
        val helloData = payload.removePrefix(HANDSHAKE_HELLO)
        val parts = helloData.split("|")
        val pName = parts.getOrNull(0) ?: "Unknown"
        val pAddr = parts.getOrNull(2) ?: fromAddress
        val pPort = parts.getOrNull(3)?.toIntOrNull() ?: MESSAGE_PORT

        val peerInfo = PeerInfo(id = fromPeerId, name = pName, address = pAddr, port = pPort)

        val state = peerStates.compute(fromPeerId) { _, existing ->
            if (existing == null) {
                println("[PeerNet-$peerId] Received HELLO from new peer: $pName ($fromPeerId)")
                PeerState(info = peerInfo, weSeeThemViaDiscovery = true)
            } else {
                existing.weSeeThemViaDiscovery = true
                existing
            }
        }!!

        // Send ACK back
        if (!state.weAckedThem) {
            sendHandshakeAck(peerInfo)
            state.weAckedThem = true
        }

        // Check if fully connected
        checkAndEmitJoined(state)
    }

    /**
     * When we receive ACK, the peer confirmed they see us.
     */
    private fun handleAckReceived(fromPeerId: String) {
        val state = peerStates[fromPeerId] ?: return
        println("[PeerNet-$peerId] Received ACK from: $fromPeerId")
        state.theyAckedUs = true
        checkAndEmitJoined(state)
    }

    /**
     * Emit Joined event if handshake is complete (both sides confirmed).
     */
    private fun checkAndEmitJoined(state: PeerState) {
        // Joined when: we see them AND they see us (via ACK or HELLO response)
        if (state.weSeeThemViaDiscovery && state.theyAckedUs && !state.isJoined) {
            state.isJoined = true
            println("[PeerNet-$peerId] Peer JOINED: ${state.info.name} (${state.info.id})")
            scope?.launch {
                incomingChannel.send(PeerMessage.Event.Joined(state.info))
            }
        }
    }

    private fun sendHandshakeHello(peer: PeerInfo) {
        val payload = "$HANDSHAKE_HELLO$peerName|$peerId|$localAddress|$localPort"
        println("[PeerNet-$peerId] Sending HELLO to ${peer.name} at ${peer.address}:${peer.port}")
        sendUdp(peer.address, peer.port, "$peerId:$payload")
    }

    private fun sendHandshakeAck(peer: PeerInfo) {
        val payload = "$HANDSHAKE_ACK$peerId"
        sendUdp(peer.address, peer.port, "$peerId:$payload")
    }

    private fun sendUdp(address: String, port: Int, message: String) {
        try {
            val socket = udpSocket ?: return
            val data = message.toByteArray(Charsets.UTF_8)
            val packet = DatagramPacket(data, data.size, InetAddress.getByName(address), port)
            socket.send(packet)
        } catch (e: Exception) {
            println("[PeerNet-$peerId] Send error to $address:$port: ${e.message}")
        }
    }

    private suspend fun processOutgoingCommands() {
        for (command in outgoingChannel) {
            when (command) {
                is PeerCommand.SendTo -> {
                    val state = peerStates[command.peerId]
                    if (state?.isJoined == true) {
                        val payload = "$peerId:${String(command.payload, Charsets.UTF_8)}"
                        sendUdp(state.info.address, state.info.port, payload)
                    }
                }
                is PeerCommand.Broadcast -> {
                    peerStates.values.filter { it.isJoined }.forEach { state ->
                        val payload = "$peerId:${String(command.payload, Charsets.UTF_8)}"
                        sendUdp(state.info.address, state.info.port, payload)
                    }
                }
            }
        }
    }

    fun stop() {
        println("[PeerNet-$peerId] Stopping")
        isRunning = false
        try {
            dnsSdProcess?.destroyForcibly()
        } catch (e: Exception) {
            println("[PeerNet-$peerId] Error destroying dns-sd process: ${e.message}")
        }
        try {
            serviceInfo?.let {
                println("[PeerNet-$peerId] Unregistering mDNS service")
                jmdns?.unregisterService(it)
            }
            println("[PeerNet-$peerId] Closing JmDNS")
            jmdns?.close()
        } catch (e: Exception) {
            println("[PeerNet-$peerId] Error closing JmDNS: ${e.message}")
        }
        try {
            udpSocket?.close()
        } catch (e: Exception) {
            println("[PeerNet-$peerId] Error closing UDP socket: ${e.message}")
        }
        println("[PeerNet-$peerId] Stopped")
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
                // Skip tunnel/VPN interfaces (utun, tun, tap, etc.)
                if (name.startsWith("utun") || name.startsWith("tun") || name.startsWith("tap")) continue

                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        val ip = address.hostAddress ?: continue
                        // Skip link-local and Tailscale/CGNAT addresses
                        if (ip.startsWith("169.254.") || ip.startsWith("100.64.") || ip.startsWith("100.96.")) continue

                        // Prefer en0 (WiFi on Mac) or eth0 (Linux)
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
}
