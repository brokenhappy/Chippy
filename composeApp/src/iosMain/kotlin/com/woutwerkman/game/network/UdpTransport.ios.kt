package com.woutwerkman.game.network

import kotlinx.cinterop.*
import kotlinx.coroutines.*
import platform.Foundation.*
import platform.Network.*
import platform.darwin.*
import platform.posix.memcpy

// Use multicast address instead of broadcast (iOS blocks 255.255.255.255)
private const val MULTICAST_GROUP = "224.0.0.251"

@OptIn(ExperimentalForeignApi::class)
actual fun createUdpNetworkTransport(peerId: String, peerName: String): NetworkTransport {
    return IosUdpNetworkTransport(peerId, peerName)
}

@OptIn(ExperimentalForeignApi::class)
class IosUdpNetworkTransport(
    private val peerId: String,
    private val peerName: String
) : NetworkTransport {

    private var messageHandler: ((String) -> Unit)? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var discoveryListener: nw_listener_t = null
    private var gameListener: nw_listener_t = null
    private var multicastGroup: nw_connection_group_t = null
    private var isDiscovering = false
    private var isServerRunning = false

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

        withContext(Dispatchers.Main) {
            try {
                // Create UDP parameters for discovery listener
                val discoveryParams = nw_parameters_create_secure_udp(
                    NW_PARAMETERS_DISABLE_PROTOCOL,
                    NW_PARAMETERS_DEFAULT_CONFIGURATION
                )
                nw_parameters_set_reuse_local_address(discoveryParams, true)

                // Create multicast group descriptor
                val multicastEndpoint = nw_endpoint_create_host(MULTICAST_GROUP, DISCOVERY_PORT.toString())
                val groupDescriptor = nw_group_descriptor_create_multicast(multicastEndpoint)

                // Join multicast group for receiving
                multicastGroup = nw_connection_group_create(groupDescriptor, discoveryParams)

                multicastGroup?.let { group ->
                    nw_connection_group_set_queue(group, dispatch_get_main_queue())

                    nw_connection_group_set_receive_handler(group, 4096u, true) { content, _, isComplete ->
                        if (content != null) {
                            val message = dispatchDataToString(content)
                            if (message != null && !message.contains(peerId)) {
                                messageHandler?.invoke(message)
                            }
                        }
                    }

                    nw_connection_group_set_state_changed_handler(group) { state, _ ->
                        when (state) {
                            nw_connection_group_state_ready -> {
                                println("Multicast group ready")
                            }
                            nw_connection_group_state_failed -> {
                                println("Multicast group failed")
                            }
                            else -> {}
                        }
                    }

                    nw_connection_group_start(group)
                }

                // Also create a listener on the discovery port for direct messages
                discoveryListener = nw_listener_create_with_port(
                    DISCOVERY_PORT.toString(),
                    discoveryParams
                )

                discoveryListener?.let { listener ->
                    nw_listener_set_queue(listener, dispatch_get_main_queue())

                    nw_listener_set_new_connection_handler(listener) { connection ->
                        if (connection != null) {
                            handleIncomingConnection(connection)
                        }
                    }

                    nw_listener_set_state_changed_handler(listener) { state, _ ->
                        if (state == nw_listener_state_ready) {
                            println("Discovery listener ready on port $DISCOVERY_PORT")
                        }
                    }

                    nw_listener_start(listener)
                }

                // Create game listener for direct messages on dynamic port
                val gameParams = nw_parameters_create_secure_udp(
                    NW_PARAMETERS_DISABLE_PROTOCOL,
                    NW_PARAMETERS_DEFAULT_CONFIGURATION
                )

                gameListener = nw_listener_create(gameParams)

                gameListener?.let { listener ->
                    nw_listener_set_queue(listener, dispatch_get_main_queue())

                    nw_listener_set_new_connection_handler(listener) { connection ->
                        if (connection != null) {
                            handleIncomingConnection(connection)
                        }
                    }

                    nw_listener_set_state_changed_handler(listener) { state, _ ->
                        if (state == nw_listener_state_ready) {
                            localPort = nw_listener_get_port(listener).toInt()
                            println("Game listener ready on port $localPort")
                        }
                    }

                    nw_listener_start(listener)
                }
            } catch (e: Exception) {
                println("Error starting discovery: ${e.message}")
            }
        }
    }

    private fun handleIncomingConnection(connection: nw_connection_t) {
        nw_connection_set_queue(connection, dispatch_get_main_queue())

        nw_connection_set_state_changed_handler(connection) { state, _ ->
            if (state == nw_connection_state_ready) {
                receiveMessage(connection)
            }
        }

        nw_connection_start(connection)
    }

    private fun receiveMessage(connection: nw_connection_t) {
        nw_connection_receive(connection, 1u, 4096u) { content, _, isComplete, error ->
            if (content != null) {
                val message = dispatchDataToString(content)

                if (message != null && !message.contains(peerId)) {
                    messageHandler?.invoke(message)
                }
            }

            if (!isComplete && error == null) {
                receiveMessage(connection)
            }
        }
    }

    private fun dispatchDataToString(dispatchData: dispatch_data_t): String? {
        val size = dispatch_data_get_size(dispatchData).toInt()
        if (size == 0) return null

        val buffer = ByteArray(size)
        buffer.usePinned { pinned ->
            dispatch_data_apply(dispatchData) { _, offset, data, dataSize ->
                memcpy(pinned.addressOf(offset.toInt()), data, dataSize)
                true
            }
        }
        return buffer.decodeToString()
    }

    override suspend fun stopDiscovery() {
        isDiscovering = false
        withContext(Dispatchers.Main) {
            discoveryListener?.let { nw_listener_cancel(it) }
            multicastGroup?.let { nw_connection_group_cancel(it) }
        }
    }

    override suspend fun broadcast(message: String) {
        withContext(Dispatchers.Main) {
            // Send to multicast group (for iOS devices)
            val multicastEndpoint = nw_endpoint_create_host(MULTICAST_GROUP, DISCOVERY_PORT.toString())
            sendToEndpoint(multicastEndpoint, message)

            // Also send to broadcast address (for Android/JVM devices)
            val broadcastEndpoint = nw_endpoint_create_host("255.255.255.255", DISCOVERY_PORT.toString())
            sendToEndpoint(broadcastEndpoint, message)
        }
    }

    override suspend fun sendTo(address: String, port: Int, message: String) {
        withContext(Dispatchers.Main) {
            val endpoint = nw_endpoint_create_host(address, port.toString())
            sendToEndpoint(endpoint, message)
        }
    }

    private fun sendToEndpoint(endpoint: nw_endpoint_t?, message: String) {
        if (endpoint == null) return

        val params = nw_parameters_create_secure_udp(
            NW_PARAMETERS_DISABLE_PROTOCOL,
            NW_PARAMETERS_DEFAULT_CONFIGURATION
        )

        val connection = nw_connection_create(endpoint, params)

        nw_connection_set_queue(connection, dispatch_get_main_queue())

        nw_connection_set_state_changed_handler(connection) { state, _ ->
            if (state == nw_connection_state_ready) {
                val data = message.encodeToByteArray()
                val dispatchData = data.usePinned { pinned ->
                    dispatch_data_create(
                        pinned.addressOf(0),
                        data.size.toULong(),
                        dispatch_get_main_queue(),
                        null
                    )
                }

                nw_connection_send(
                    connection,
                    dispatchData,
                    NW_CONNECTION_DEFAULT_MESSAGE_CONTEXT,
                    true
                ) { _ ->
                    nw_connection_cancel(connection)
                }
            }
        }

        nw_connection_start(connection)
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
        discoveryListener?.let { nw_listener_cancel(it) }
        gameListener?.let { nw_listener_cancel(it) }
        multicastGroup?.let { nw_connection_group_cancel(it) }
    }
}
