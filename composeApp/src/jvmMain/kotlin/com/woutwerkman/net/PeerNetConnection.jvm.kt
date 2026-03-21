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

private const val DISCOVERY_PORT = 41234

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

private class JvmPeerTransport(
    private val peerId: String,
    private val peerName: String,
    private val serviceName: String,
    private val incomingChannel: SendChannel<PeerMessage>,
    private val outgoingChannel: ReceiveChannel<PeerCommand>
) {
    private var jmdns: JmDNS? = null
    private var serviceInfo: ServiceInfo? = null
    private var discoverySocket: DatagramSocket? = null
    private var isRunning = false
    private val discoveredPeers = ConcurrentHashMap<String, PeerInfo>()
    private val serviceType = "_${serviceName}._udp.local."
    private var localAddress: String = "0.0.0.0"
    private var dnsSdProcess: Process? = null
    
    fun start(scope: CoroutineScope) {
        isRunning = true
        
        scope.launch(Dispatchers.IO) {
            try {
                localAddress = getLocalIpAddress()
                println("[JVM-$peerId] Local IP: $localAddress")
                
                // Start UDP socket for direct messaging
                discoverySocket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(DISCOVERY_PORT))
                }
                
                // Start mDNS
                startMdns(this)
                
                // Listen for UDP messages
                launch { listenForMessages() }
                
                // Process outgoing commands
                launch { processOutgoingCommands() }
                
            } catch (e: Exception) {
                println("[JVM-$peerId] Error starting transport: ${e.message}")
                e.printStackTrace()
            }
        }
    }
    
    private fun startMdns(scope: CoroutineScope) {
        try {
            // Create JmDNS without binding to specific address to allow cross-interface discovery
            // This is important for hotspot setups where devices are on different interfaces
            jmdns = JmDNS.create()
            println("[JVM-$peerId] JmDNS created")

            // Add service listener BEFORE registering our service to catch early announcements
            jmdns?.addServiceListener(serviceType, object : ServiceListener {
                override fun serviceAdded(event: ServiceEvent) {
                    println("[JVM-$peerId] Service added: ${event.name}")
                    jmdns?.requestServiceInfo(event.type, event.name, true)
                }

                override fun serviceRemoved(event: ServiceEvent) {
                    println("[JVM-$peerId] Service removed: ${event.name}")
                    val parts = event.name.split("|")
                    if (parts.size >= 2) {
                        val removedPeerId = parts[1]
                        if (removedPeerId != peerId && discoveredPeers.remove(removedPeerId) != null) {
                            runBlocking {
                                incomingChannel.send(PeerMessage.Event.Lost(removedPeerId))
                            }
                        }
                    }
                }

                override fun serviceResolved(event: ServiceEvent) {
                    println("[JVM-$peerId] Service resolved: ${event.name}")
                    val parts = event.name.split("|")
                    if (parts.size >= 3) {
                        val pName = parts[0]
                        val pId = parts[1]
                        val pAddr = parts[2]

                        if (pId != peerId && !discoveredPeers.containsKey(pId)) {
                            val peerInfo = PeerInfo(
                                id = pId,
                                name = pName,
                                address = pAddr,
                                port = DISCOVERY_PORT
                            )
                            discoveredPeers[pId] = peerInfo
                            runBlocking {
                                incomingChannel.send(PeerMessage.Event.Discovered(peerInfo))
                            }
                        }
                    }
                }
            })

            // Service name format: displayName|peerId|address
            val fullServiceName = "$peerName|$peerId|$localAddress"
            serviceInfo = ServiceInfo.create(
                serviceType,
                fullServiceName,
                DISCOVERY_PORT,
                "PeerNet"
            )
            jmdns?.registerService(serviceInfo)
            println("[JVM-$peerId] mDNS service registered: $fullServiceName")

            // Periodically query for services to catch late arrivals
            scope.launch {
                while (isRunning) {
                    delay(2000) // Query every 2 seconds
                    try {
                        val services = jmdns?.list(serviceType)
                        services?.forEach { info ->
                            val parts = info.name.split("|")
                            if (parts.size >= 3) {
                                val pName = parts[0]
                                val pId = parts[1]
                                val pAddr = parts[2]

                                if (pId != peerId && !discoveredPeers.containsKey(pId)) {
                                    println("[JVM-$peerId] Found service via list: ${info.name}")
                                    val peerInfo = PeerInfo(
                                        id = pId,
                                        name = pName,
                                        address = pAddr,
                                        port = DISCOVERY_PORT
                                    )
                                    discoveredPeers[pId] = peerInfo
                                    incomingChannel.send(PeerMessage.Event.Discovered(peerInfo))
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Ignore query errors
                    }
                }
            }

            // Fallback: Use native dns-sd command on macOS to discover services
            // JmDNS sometimes fails to discover services on iPhone hotspots
            if (System.getProperty("os.name").lowercase().contains("mac")) {
                scope.launch(Dispatchers.IO) {
                    println("[JVM-$peerId] Starting native dns-sd fallback discovery")
                    discoverViaDnsSd()
                }
            }
        } catch (e: Exception) {
            println("[JVM-$peerId] Error starting mDNS: ${e.message}")
            e.printStackTrace()
        }
    }
    
    private suspend fun discoverViaDnsSd() {
        try {
            // Run dns-sd browse command
            val browseServiceType = "_${serviceName}._udp."
            val process = ProcessBuilder("dns-sd", "-B", browseServiceType, "local.")
                .redirectErrorStream(true)
                .start()
            dnsSdProcess = process

            val reader = process.inputStream.bufferedReader()

            while (isRunning) {
                val line = reader.readLine() ?: break
                // Parse dns-sd output: "Timestamp A/R Flags if Domain Service Type Instance Name"
                // Example: "20:28:07.303  Add  3  23 local.  _chippy._udp.  BraveViper108|mn0pze5s-1d90urtmykcjb|172.20.10.1"
                if (line.contains("Add") && line.contains(browseServiceType)) {
                    // Extract the instance name (last part after service type)
                    val parts = line.split("\\s+".toRegex())
                    if (parts.size >= 7) {
                        // Instance name is everything after the service type column
                        val instanceNameStart = line.indexOf(browseServiceType)
                        if (instanceNameStart >= 0) {
                            val instanceName = line.substring(instanceNameStart + browseServiceType.length).trim()
                            val nameParts = instanceName.split("|")
                            if (nameParts.size >= 3) {
                                val pName = nameParts[0]
                                val pId = nameParts[1]
                                val pAddr = nameParts[2]

                                if (pId != peerId && !discoveredPeers.containsKey(pId)) {
                                    println("[JVM-$peerId] Found service via dns-sd: $instanceName")
                                    val peerInfo = PeerInfo(
                                        id = pId,
                                        name = pName,
                                        address = pAddr,
                                        port = DISCOVERY_PORT
                                    )
                                    discoveredPeers[pId] = peerInfo
                                    incomingChannel.send(PeerMessage.Event.Discovered(peerInfo))
                                }
                            }
                        }
                    }
                }
            }

            process.destroy()
        } catch (e: Exception) {
            println("[JVM-$peerId] dns-sd discovery error: ${e.message}")
        }
    }

    private suspend fun listenForMessages() {
        val buffer = ByteArray(65535)
        println("[JVM-$peerId] Listening for messages on port $DISCOVERY_PORT")
        
        while (isRunning && discoverySocket != null) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                withContext(Dispatchers.IO) {
                    discoverySocket?.receive(packet)
                }
                
                val data = packet.data.copyOf(packet.length)
                // Message format: peerId:payload
                val message = String(data, Charsets.UTF_8)
                val separatorIndex = message.indexOf(':')
                if (separatorIndex > 0) {
                    val fromPeerId = message.substring(0, separatorIndex)
                    if (fromPeerId != peerId) {
                        val payload = data.copyOfRange(separatorIndex + 1, data.size)
                        incomingChannel.send(PeerMessage.Data(fromPeerId, payload))
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    // Socket closed or other error
                }
            }
        }
    }
    
    private suspend fun processOutgoingCommands() {
        for (command in outgoingChannel) {
            when (command) {
                is PeerCommand.SendTo -> {
                    val peer = discoveredPeers[command.peerId]
                    if (peer != null) {
                        sendToPeer(peer.address, peer.port, command.payload)
                    }
                }
                is PeerCommand.Broadcast -> {
                    discoveredPeers.values.forEach { peer ->
                        sendToPeer(peer.address, peer.port, command.payload)
                    }
                }
            }
        }
    }
    
    private suspend fun sendToPeer(address: String, port: Int, payload: ByteArray) {
        withContext(Dispatchers.IO) {
            try {
                val message = "$peerId:".toByteArray(Charsets.UTF_8) + payload
                val packet = DatagramPacket(
                    message,
                    message.size,
                    InetAddress.getByName(address),
                    port
                )
                DatagramSocket().use { socket ->
                    socket.send(packet)
                }
            } catch (e: Exception) {
                println("[JVM-$peerId] Send error: ${e.message}")
            }
        }
    }
    
    fun stop() {
        isRunning = false
        try {
            dnsSdProcess?.destroyForcibly()
            dnsSdProcess = null
        } catch (e: Exception) {
            println("[JVM-$peerId] Error stopping dns-sd: ${e.message}")
        }
        try {
            serviceInfo?.let { jmdns?.unregisterService(it) }
            jmdns?.close()
        } catch (e: Exception) {
            println("[JVM-$peerId] Error closing mDNS: ${e.message}")
        }
        discoverySocket?.close()
    }
    
    private fun getLocalIpAddress(): String {
        try {
            var preferredIp: String? = null
            var fallbackIp: String? = null
            
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        val ip = address.hostAddress ?: continue
                        if (!ip.startsWith("169.254.")) {
                            if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                                preferredIp = ip
                            } else if (fallbackIp == null) {
                                fallbackIp = ip
                            }
                        } else if (fallbackIp == null) {
                            fallbackIp = ip
                        }
                    }
                }
            }
            return preferredIp ?: fallbackIp ?: "127.0.0.1"
        } catch (e: Exception) {
            println("[JVM] Error getting local IP: ${e.message}")
        }
        return "127.0.0.1"
    }
}
