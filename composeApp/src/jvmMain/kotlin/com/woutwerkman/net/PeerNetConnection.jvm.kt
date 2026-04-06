package com.woutwerkman.net

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import java.net.*
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

internal actual suspend fun <T> withTransport(
    config: PeerNetConfig,
    peerId: String,
    block: suspend CoroutineScope.(TransportHandle) -> T,
): T {
    val serviceType = "_${config.serviceName}._udp.local."
    val discoveryEvents = Channel<ServiceDiscoveryEvent>(Channel.BUFFERED)
    val receivedPackets = Channel<ReceivedPacket>(Channel.BUFFERED)

    val localAddress = getLocalIpAddress()
    println("[PeerNet-$peerId] Local IP: $localAddress")

    val udpSocket = createUdpSocket(peerId)
    val localPort = udpSocket.localPort
    println("[PeerNet-$peerId] Bound to port $localPort")

    val jmdns = startJmdns(peerId, localAddress, localPort, serviceType, config.displayName, discoveryEvents)

    val isMac = System.getProperty("os.name").lowercase().contains("mac")
    val dnsSdProcess: Process? = if (isMac) {
        ProcessBuilder("dns-sd", "-B", "_${config.serviceName}._udp.", "local.")
            .redirectErrorStream(true)
            .start()
    } else null

    val handle = TransportHandle(
        localAddress = localAddress,
        localPort = localPort,
        discoveryEvents = discoveryEvents,
        receivedPackets = receivedPackets,
        sendUdp = { address, port, message -> sendUdp(udpSocket, address, port, message) },
        platformTasks = {
            if (dnsSdProcess != null) {
                launch(Dispatchers.IO) {
                    readDnsSdOutput(dnsSdProcess, config.serviceName, peerId, discoveryEvents)
                }
            }
        },
    )

    try {
        return coroutineScope {
            val receiveJob = launch(Dispatchers.IO) {
                listenForPackets(udpSocket, peerId, receivedPackets)
            }

            try {
                coroutineScope { block(handle) }
            } finally {
                dnsSdProcess?.destroyForcibly()
                receiveJob.cancel()
            }
        }
    } finally {
        println("[PeerNet-$peerId] Stopping")
        try { dnsSdProcess?.destroyForcibly() } catch (_: Exception) {}
        try { jmdns.unregisterAllServices(); jmdns.close() } catch (_: Exception) {}
        try { udpSocket.close() } catch (_: Exception) {}
        println("[PeerNet-$peerId] Stopped")
    }
}

private fun startJmdns(
    peerId: String,
    localAddress: String,
    localPort: Int,
    serviceType: String,
    peerName: String,
    discoveryEvents: SendChannel<ServiceDiscoveryEvent>,
): JmDNS {
    val inetAddress = InetAddress.getByName(localAddress)
    val jmdns = JmDNS.create(inetAddress, localAddress)
    println("[PeerNet-$peerId] JmDNS created on $localAddress")

    jmdns.addServiceListener(serviceType, object : ServiceListener {
        override fun serviceAdded(event: ServiceEvent) {
            jmdns.requestServiceInfo(event.type, event.name, true)
        }

        override fun serviceRemoved(event: ServiceEvent) {
            discoveryEvents.trySend(ServiceDiscoveryEvent.Removed(event.name))
        }

        override fun serviceResolved(event: ServiceEvent) {
            discoveryEvents.trySend(ServiceDiscoveryEvent.Discovered(event.name))
        }
    })

    val fullServiceName = formatServiceName(peerName, peerId, localAddress, localPort)
    val serviceInfo = ServiceInfo.create(serviceType, fullServiceName, localPort, "PeerNet")
    jmdns.registerService(serviceInfo)
    println("[PeerNet-$peerId] mDNS service registered: $fullServiceName")

    return jmdns
}

private suspend fun listenForPackets(
    udpSocket: DatagramSocket,
    peerId: String,
    receivedPackets: SendChannel<ReceivedPacket>,
) {
    val buffer = ByteArray(65535)
    println("[PeerNet-$peerId] Listening for messages on port ${udpSocket.localPort}")

    while (true) {
        try {
            val packet = DatagramPacket(buffer, buffer.size)
            withContext(Dispatchers.IO) { udpSocket.receive(packet) }
            val message = String(packet.data, 0, packet.length, Charsets.UTF_8)
            receivedPackets.send(ReceivedPacket(message, packet.address.hostAddress ?: "", packet.port))
        } catch (_: SocketTimeoutException) {
            // Expected: soTimeout (100ms) fires to allow cancellation checks
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("[PeerNet-$peerId] Socket receive error: ${e::class.simpleName}: ${e.message}")
        }
    }
}

/**
 * Reads dns-sd browse output and sends discovery events.
 * Blocks on readLine() — process destruction (in caller's finally) breaks it.
 */
private fun readDnsSdOutput(
    process: Process,
    serviceName: String,
    peerId: String,
    discoveryEvents: SendChannel<ServiceDiscoveryEvent>,
) {
    val browseServiceType = "_${serviceName}._udp."
    val reader = process.inputStream.bufferedReader()
    try {
        while (true) {
            val line = reader.readLine() ?: break
            if (line.contains("Add") && line.contains(browseServiceType)) {
                val instanceNameStart = line.indexOf(browseServiceType)
                if (instanceNameStart >= 0) {
                    val instanceName = line.substring(instanceNameStart + browseServiceType.length).trim()
                    discoveryEvents.trySend(ServiceDiscoveryEvent.Discovered(instanceName))
                }
            }
        }
    } catch (_: Exception) {}
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
