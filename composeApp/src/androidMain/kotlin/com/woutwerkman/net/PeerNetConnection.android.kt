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
private const val HANDSHAKE_HELLO = "${HANDSHAKE_PREFIX}HELLO|"
private const val HANDSHAKE_ACK = "${HANDSHAKE_PREFIX}ACK|"

internal actual suspend fun <T> withRawPeerNetConnectionImpl(
    config: PeerNetConfig,
    block: suspend CoroutineScope.(RawPeerNetConnection) -> T
): T = coroutineScope {
    val peerId = generatePeerId()
    val incoming = Channel<RawPeerMessage>(Channel.BUFFERED)
    val outgoing = Channel<PeerCommand>(Channel.BUFFERED)

    val transport = AndroidPeerTransport(
        peerId = peerId,
        peerName = config.displayName,
        serviceName = config.serviceName,
        incomingChannel = incoming,
        outgoingChannel = outgoing
    )

    try {
        transport.start(this)
        val connection = RawPeerNetConnection(peerId, incoming, outgoing)
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
    return "android-${timestamp.toString(36)}-$random"
}

/**
 * Tracks the state of a discovered peer through the handshake process.
 */
private data class PeerState(
    var info: PeerInfo,
    var weSeeThemViaDiscovery: Boolean = false,
    var weSentHello: Boolean = false,
    var theyAckedUs: Boolean = false,
    var weAckedThem: Boolean = false,
    var isJoined: Boolean = false
)

private class AndroidPeerTransport(
    private val peerId: String,
    private val peerName: String,
    private val serviceName: String,
    private val incomingChannel: SendChannel<RawPeerMessage>,
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
    private var scope: CoroutineScope? = null

    fun start(scope: CoroutineScope) {
        this.scope = scope
        isRunning = true

        scope.launch(Dispatchers.IO) {
            try {
                localAddress = getLocalIpAddress()
                println("[PeerNet-$peerId] Local IP: $localAddress")

                // Start UDP socket for messaging - try MESSAGE_PORT first for emulator adb reverse compatibility
                udpSocket = try {
                    DatagramSocket(MESSAGE_PORT).apply {
                        reuseAddress = true
                        broadcast = true
                        soTimeout = 100
                    }
                } catch (e: Exception) {
                    println("[PeerNet-$peerId] Port $MESSAGE_PORT busy, using random port")
                    DatagramSocket(0).apply {
                        reuseAddress = true
                        broadcast = true
                        soTimeout = 100
                    }
                }
                localPort = udpSocket!!.localPort
                println("[PeerNet-$peerId] Bound to port $localPort")

                // Start mDNS
                startMdns(this)

                // Listen for UDP messages
                launch { listenForMessages() }

                // Process outgoing commands
                launch { processOutgoingCommands() }

                // Handshake maintenance
                launch { handshakeMaintenance() }

            } catch (e: Exception) {
                println("[PeerNet-$peerId] Error starting: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    private suspend fun handshakeMaintenance() {
        while (isRunning) {
            delay(1000)
            
            // Emulator-to-Host proactive discovery
            if (localAddress == "10.0.2.15") {
                // Try common default port as a fallback
                sendUdp("10.0.2.2", MESSAGE_PORT, "$peerId:$HANDSHAKE_HELLO$peerName|$peerId|$localAddress|$localPort")
            }

            peerStates.values.forEach { state ->
                if (state.weSeeThemViaDiscovery && !state.isJoined) {
                    sendHandshakeHello(state.info)
                    state.weSentHello = true
                }
            }
        }
    }

    private fun startMdns(scope: CoroutineScope) {
        try {
            val addr = InetAddress.getByName(localAddress)
            jmdns = JmDNS.create(addr, "peer-android-$peerId")
            
            jmdns?.addServiceListener(serviceType, object : ServiceListener {
                override fun serviceAdded(event: ServiceEvent) {
                    jmdns?.requestServiceInfo(event.type, event.name, true)
                }

                override fun serviceRemoved(event: ServiceEvent) {
                    handlePeerRemoved(event.name)
                }

                override fun serviceResolved(event: ServiceEvent) {
                    handlePeerDiscovered(event.name)
                }
            })

            val fullServiceName = "$peerName|$peerId|$localAddress|$localPort"
            serviceInfo = ServiceInfo.create(serviceType, fullServiceName, localPort, "PeerNet")
            jmdns?.registerService(serviceInfo)
            println("[PeerNet-$peerId] mDNS service registered: $fullServiceName")

            // Fallback query
            scope.launch {
                while (isRunning) {
                    delay(5000)
                    try {
                        val services = jmdns?.list(serviceType)
                        services?.forEach { handlePeerDiscovered(it.name) }
                    } catch (e: Exception) {}
                }
            }
        } catch (e: Exception) {
            println("[PeerNet-$peerId] Error starting mDNS: ${e.message}")
        }
    }

    private fun handlePeerDiscovered(serviceName: String) {
        val parts = serviceName.split("|")
        if (parts.size >= 3) {
            val pName = parts[0]
            val pId = parts[1]
            val pAddr = parts[2]
            val pPort = parts.getOrNull(3)?.toIntOrNull() ?: MESSAGE_PORT

            if (pId == peerId) return

            val peerInfo = PeerInfo(id = pId, name = pName, address = pAddr, port = pPort)
            peerStates.compute(pId) { _, existing ->
                if (existing == null) {
                    println("[PeerNet-$peerId] Peer discovered: $pName ($pId)")
                    PeerState(info = peerInfo, weSeeThemViaDiscovery = true)
                } else {
                    existing.weSeeThemViaDiscovery = true
                    existing
                }
            }
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
                    incomingChannel.send(RawPeerMessage.Event.Disconnected(pId))
                }
            }
        }
    }

    private suspend fun listenForMessages() {
        val buffer = ByteArray(65535)
        while (isRunning && udpSocket != null) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                withContext(Dispatchers.IO) { udpSocket?.receive(packet) }
                val data = packet.data.copyOf(packet.length)
                val message = String(data, Charsets.UTF_8)
                val separatorIndex = message.indexOf(':')
                if (separatorIndex > 0) {
                    val fromPeerId = message.substring(0, separatorIndex)
                    if (fromPeerId == peerId) continue
                    val payload = message.substring(separatorIndex + 1)
                    when {
                        payload.startsWith(HANDSHAKE_HELLO) -> handleHelloReceived(fromPeerId, payload, packet.address.hostAddress ?: "", packet.port)
                        payload.startsWith(HANDSHAKE_ACK) -> handleAckReceived(fromPeerId)
                        else -> {
                            // Forward if we've seen this peer at all (don't require full
                            // handshake, as linearization state may arrive before handshake completes)
                            val state = peerStates[fromPeerId]
                            if (state != null) {
                                incomingChannel.send(RawPeerMessage.Received(fromPeerId, payload.toByteArray(Charsets.UTF_8)))
                            }
                        }
                    }
                }
            } catch (e: SocketTimeoutException) {
            } catch (e: Exception) {
                if (isRunning) println("[PeerNet-$peerId] Receive error: ${e.message}")
            }
        }
    }

    private fun handleHelloReceived(fromPeerId: String, payload: String, fromAddress: String, fromPort: Int) {
        val helloData = payload.removePrefix(HANDSHAKE_HELLO)
        val parts = helloData.split("|")
        val pName = parts.getOrNull(0) ?: "Unknown"
        var pAddr = parts.getOrNull(2) ?: fromAddress
        var pPort = parts.getOrNull(3)?.toIntOrNull() ?: MESSAGE_PORT

        // Emulator logic:
        // 1. If we receive a HELLO with 10.0.2.15, it's from another emulator or ourselves (translated).
        //    Use fromAddress and fromPort which are the correct return path through the NAT.
        if (pAddr == "10.0.2.15") {
            pAddr = fromAddress
            pPort = fromPort
        }
        
        // 2. If we are an emulator (our IP is 10.0.2.15) and the peer is NOT an emulator,
        //    we should try to reach the host at 10.0.2.2.
        if (localAddress == "10.0.2.15" && !pAddr.startsWith("10.0.2.") && pAddr != "127.0.0.1") {
            println("[PeerNet-$peerId] We are emulator, peer is $pAddr. Adding 10.0.2.2 as alternative.")
            // We can't easily add an alternative, but we can try to send HELLO to 10.0.2.2 too
            scope?.launch {
                delay(500)
                sendUdp("10.0.2.2", pPort, "$peerId:$HANDSHAKE_HELLO$peerName|$peerId|$localAddress|$localPort")
            }
        }

        val peerInfo = PeerInfo(id = fromPeerId, name = pName, address = pAddr, port = pPort)
        val state = peerStates.compute(fromPeerId) { _, existing ->
            if (existing == null) PeerState(info = peerInfo, weSeeThemViaDiscovery = true)
            else { existing.info = peerInfo; existing.weSeeThemViaDiscovery = true; existing }
        }!!
        // Always send ACK in reply to HELLO — the peer may have missed our earlier ACK
        sendHandshakeAck(peerInfo)
        state.weAckedThem = true
        checkAndEmitJoined(state)
    }

    private fun handleAckReceived(fromPeerId: String) {
        val state = peerStates[fromPeerId] ?: return
        state.theyAckedUs = true
        checkAndEmitJoined(state)
    }

    private fun checkAndEmitJoined(state: PeerState) {
        if (state.weSeeThemViaDiscovery && state.theyAckedUs && !state.isJoined) {
            state.isJoined = true
            println("[PeerNet-$peerId] Peer JOINED: ${state.info.name} (${state.info.id})")
            scope?.launch { incomingChannel.send(RawPeerMessage.Event.Connected(state.info)) }
        }
    }

    private fun sendHandshakeHello(peer: PeerInfo) {
        val payload = "$HANDSHAKE_HELLO$peerName|$peerId|$localAddress|$localPort"
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
                    peerStates[command.peerId]?.let {
                        sendUdp(it.info.address, it.info.port, "$peerId:${String(command.payload)}")
                    }
                }
                is PeerCommand.Broadcast -> {
                    peerStates.values.forEach {
                        sendUdp(it.info.address, it.info.port, "$peerId:${String(command.payload)}")
                    }
                }
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            serviceInfo?.let { jmdns?.unregisterService(it) }
            jmdns?.close()
        } catch (e: Exception) {}
        udpSocket?.close()
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
        } catch (e: Exception) {}
        return "127.0.0.1"
    }
}
