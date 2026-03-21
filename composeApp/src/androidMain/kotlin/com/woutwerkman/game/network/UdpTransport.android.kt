package com.woutwerkman.game.network

import kotlinx.coroutines.*
import java.net.*
import java.util.concurrent.ConcurrentHashMap
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

private const val SERVICE_TYPE = "_chippy._udp.local."

actual fun createUdpNetworkTransport(peerId: String, peerName: String): NetworkTransport {
    return AndroidUdpNetworkTransport(peerId, peerName)
}

class AndroidUdpNetworkTransport(
    private val peerId: String,
    private val peerName: String
) : NetworkTransport {

    private var messageHandler: ((String) -> Unit)? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var discoverySocket: DatagramSocket? = null
    private var gameSocket: DatagramSocket? = null
    private var isDiscovering = false
    private var isServerRunning = false

    // Bonjour/mDNS
    private var jmdns: JmDNS? = null
    private var serviceInfo: ServiceInfo? = null

    private val connectedPeers = ConcurrentHashMap<String, PeerAddress>()
    private var localAddress: String = "0.0.0.0"
    private var localPort: Int = 0

    data class PeerAddress(val address: String, val port: Int)

    override fun setMessageHandler(handler: (String) -> Unit) {
        messageHandler = handler
    }

    override suspend fun startDiscovery() {
        if (isDiscovering) return
        isDiscovering = true

        withContext(Dispatchers.IO) {
            try {
                // Get local IP first
                localAddress = getLocalIpAddress()
                println("Android: Local IP address: $localAddress")

                // Create discovery socket to listen for direct messages
                discoverySocket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(DISCOVERY_PORT))
                }

                // Create game socket for direct communication
                gameSocket = DatagramSocket().apply {
                    reuseAddress = true
                }
                localPort = gameSocket?.localPort ?: GAME_PORT_START

                // Start listening for discovery messages
                scope.launch {
                    listenForDiscovery()
                }

                // Start listening for game messages
                scope.launch {
                    listenForGameMessages()
                }

                // Start Bonjour/mDNS service
                startBonjour()

            } catch (e: Exception) {
                println("Android: Error starting discovery: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    private fun startBonjour() {
        try {
            val addr = InetAddress.getByName(localAddress)
            jmdns = JmDNS.create(addr, "chippy-android")
            println("Android: JmDNS created on $localAddress")

            // Register our service - include peer info in service name
            // Format: peerName|peerId|address
            val serviceName = "$peerName|$peerId|$localAddress"
            serviceInfo = ServiceInfo.create(
                SERVICE_TYPE,
                serviceName,
                DISCOVERY_PORT,
                "Chippy Game"
            )
            jmdns?.registerService(serviceInfo)
            println("Android: Bonjour service registered: $serviceName")

            // Listen for other services
            jmdns?.addServiceListener(SERVICE_TYPE, object : ServiceListener {
                override fun serviceAdded(event: ServiceEvent) {
                    println("Android: Bonjour service added: ${event.name}")
                    // Request resolution to get full info
                    jmdns?.requestServiceInfo(event.type, event.name, true)
                }

                override fun serviceRemoved(event: ServiceEvent) {
                    println("Android: Bonjour service removed: ${event.name}")
                }

                override fun serviceResolved(event: ServiceEvent) {
                    println("Android: Bonjour service resolved: ${event.name}")
                    val parts = event.name.split("|")
                    if (parts.size >= 3) {
                        val pName = parts[0]
                        val pId = parts[1]
                        val pAddr = parts[2]

                        if (pId != peerId) {
                            println("Android: Found peer via Bonjour: $pName ($pId) at $pAddr")
                            // Create a discovery message to feed into the normal flow
                            val discoveryJson = """{"type":"com.woutwerkman.game.model.NetworkMessage.Discovery","peer":{"id":"$pId","name":"$pName","address":"$pAddr","port":$DISCOVERY_PORT}}"""
                            messageHandler?.invoke(discoveryJson)
                        }
                    }
                }
            })
            println("Android: Bonjour listener started")

        } catch (e: Exception) {
            println("Android: Error starting Bonjour: ${e.message}")
            e.printStackTrace()
        }
    }

    private suspend fun listenForDiscovery() {
        val buffer = ByteArray(4096)
        println("Android: Starting discovery listener on port $DISCOVERY_PORT")
        while (isDiscovering && discoverySocket != null) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                withContext(Dispatchers.IO) {
                    discoverySocket?.receive(packet)
                }
                val message = String(packet.data, 0, packet.length)
                println("Android: Discovery received from ${packet.address.hostAddress}:${packet.port} - ${message.take(80)}...")

                // Don't process our own messages
                if (!message.contains(peerId)) {
                    messageHandler?.invoke(message)
                }
            } catch (e: Exception) {
                if (isDiscovering) {
                    println("Android: Discovery error: ${e.message}")
                }
            }
        }
    }

    private suspend fun listenForGameMessages() {
        val buffer = ByteArray(4096)
        while (isServerRunning || isDiscovering) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                withContext(Dispatchers.IO) {
                    gameSocket?.receive(packet)
                }
                val message = String(packet.data, 0, packet.length)
                println("Android: Game message received: ${message.take(80)}...")
                messageHandler?.invoke(message)
            } catch (e: Exception) {
                if (isServerRunning || isDiscovering) {
                    // Only log if still active
                }
            }
        }
    }

    override suspend fun stopDiscovery() {
        isDiscovering = false
    }

    override suspend fun broadcast(message: String) {
        // With Bonjour, we don't need to broadcast for discovery
        // But we keep this for compatibility
        withContext(Dispatchers.IO) {
            try {
                val data = message.toByteArray()
                val socket = DatagramSocket().apply {
                    broadcast = true
                }
                val broadcastAddresses = getBroadcastAddresses()
                for (broadcastAddr in broadcastAddresses) {
                    try {
                        val packet = DatagramPacket(
                            data,
                            data.size,
                            InetAddress.getByName(broadcastAddr),
                            DISCOVERY_PORT
                        )
                        socket.send(packet)
                    } catch (e: Exception) {
                        // Try next broadcast address
                    }
                }
                socket.close()
            } catch (e: Exception) {
                println("Android: Broadcast error: ${e.message}")
            }
        }
    }

    override suspend fun sendTo(address: String, port: Int, message: String) {
        withContext(Dispatchers.IO) {
            try {
                println("Android: sendTo $address:$port - ${message.take(50)}...")
                val data = message.toByteArray()
                val packet = DatagramPacket(
                    data,
                    data.size,
                    InetAddress.getByName(address),
                    port
                )
                DatagramSocket().use { socket ->
                    socket.send(packet)
                }
                println("Android: Message sent successfully")
            } catch (e: Exception) {
                println("Android: SendTo error: ${e.message}")
            }
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
        scope.cancel()

        // Cleanup Bonjour
        try {
            serviceInfo?.let { jmdns?.unregisterService(it) }
            jmdns?.close()
        } catch (e: Exception) {
            println("Android: Error closing JmDNS: ${e.message}")
        }

        discoverySocket?.close()
        gameSocket?.close()
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue

                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        return address.hostAddress ?: "127.0.0.1"
                    }
                }
            }
        } catch (e: Exception) {
            println("Android: Error getting local IP: ${e.message}")
        }
        return "127.0.0.1"
    }

    private fun getBroadcastAddresses(): List<String> {
        val addresses = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue

                for (interfaceAddress in networkInterface.interfaceAddresses) {
                    val broadcast = interfaceAddress.broadcast
                    if (broadcast != null) {
                        addresses.add(broadcast.hostAddress ?: continue)
                    }
                }
            }
        } catch (e: Exception) {
            println("Android: Error getting broadcast addresses: ${e.message}")
        }

        if (addresses.isEmpty()) {
            addresses.add("255.255.255.255")
        }

        return addresses
    }
}
