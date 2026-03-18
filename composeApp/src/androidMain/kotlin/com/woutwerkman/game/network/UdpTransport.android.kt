package com.woutwerkman.game.network

import kotlinx.coroutines.*
import java.net.*
import java.util.concurrent.ConcurrentHashMap

// Multicast group for cross-platform discovery (iOS requires this)
private const val MULTICAST_GROUP = "224.0.0.251"

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
    private var multicastSocket: MulticastSocket? = null
    private var gameSocket: DatagramSocket? = null
    private var isDiscovering = false
    private var isServerRunning = false

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

                // Create discovery socket to listen for broadcasts
                discoverySocket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(DISCOVERY_PORT))
                }

                // Create multicast socket for iOS interoperability
                try {
                    multicastSocket = MulticastSocket(DISCOVERY_PORT).apply {
                        reuseAddress = true
                        joinGroup(InetSocketAddress(MULTICAST_GROUP, DISCOVERY_PORT), null)
                    }
                } catch (e: Exception) {
                    println("Warning: Could not join multicast group: ${e.message}")
                }

                // Create game socket for direct communication
                gameSocket = DatagramSocket().apply {
                    reuseAddress = true
                }
                localPort = gameSocket?.localPort ?: GAME_PORT_START

                // Start listening for discovery broadcasts
                scope.launch {
                    listenForDiscovery()
                }

                // Start listening for multicast messages
                scope.launch {
                    listenForMulticast()
                }

                // Start listening for game messages
                scope.launch {
                    listenForGameMessages()
                }

            } catch (e: Exception) {
                println("Error starting discovery: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    private suspend fun listenForDiscovery() {
        val buffer = ByteArray(4096)
        while (isDiscovering && discoverySocket != null) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                withContext(Dispatchers.IO) {
                    discoverySocket?.receive(packet)
                }
                val message = String(packet.data, 0, packet.length)

                // Don't process our own broadcasts
                if (!message.contains(peerId)) {
                    messageHandler?.invoke(message)
                }
            } catch (e: Exception) {
                if (isDiscovering) {
                    // Only log if still active
                }
            }
        }
    }

    private suspend fun listenForMulticast() {
        val buffer = ByteArray(4096)
        while (isDiscovering && multicastSocket != null) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                withContext(Dispatchers.IO) {
                    multicastSocket?.receive(packet)
                }
                val message = String(packet.data, 0, packet.length)

                // Don't process our own broadcasts
                if (!message.contains(peerId)) {
                    messageHandler?.invoke(message)
                }
            } catch (e: Exception) {
                if (isDiscovering) {
                    // Only log if still active
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
        withContext(Dispatchers.IO) {
            try {
                val data = message.toByteArray()

                // Send to multicast group (for iOS)
                try {
                    val multicastPacket = DatagramPacket(
                        data,
                        data.size,
                        InetAddress.getByName(MULTICAST_GROUP),
                        DISCOVERY_PORT
                    )
                    DatagramSocket().use { socket ->
                        socket.send(multicastPacket)
                    }
                } catch (e: Exception) {
                    println("Multicast send error: ${e.message}")
                }

                // Also send to broadcast addresses (for other platforms)
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
                println("Broadcast error: ${e.message}")
            }
        }
    }

    override suspend fun sendTo(address: String, port: Int, message: String) {
        withContext(Dispatchers.IO) {
            try {
                val data = message.toByteArray()
                val packet = DatagramPacket(
                    data,
                    data.size,
                    InetAddress.getByName(address),
                    port
                )
                // Use a fresh socket to send to avoid conflicts
                DatagramSocket().use { socket ->
                    socket.send(packet)
                }
            } catch (e: Exception) {
                println("SendTo error: ${e.message}")
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
        try {
            multicastSocket?.leaveGroup(InetSocketAddress(MULTICAST_GROUP, DISCOVERY_PORT), null)
        } catch (e: Exception) {
            // Ignore
        }
        discoverySocket?.close()
        multicastSocket?.close()
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
            println("Error getting local IP: ${e.message}")
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
                        addresses.add(broadcast.hostAddress)
                    }
                }
            }
        } catch (e: Exception) {
            println("Error getting broadcast addresses: ${e.message}")
        }

        // Fallback to common broadcast address
        if (addresses.isEmpty()) {
            addresses.add("255.255.255.255")
        }

        return addresses
    }
}
