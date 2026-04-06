package com.woutwerkman.net

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
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

    val handle = TransportHandle(
        localAddress = localAddress,
        localPort = localPort,
        discoveryEvents = discoveryEvents,
        receivedPackets = receivedPackets,
        sendUdp = { address, port, message -> sendUdp(udpSocket, address, port, message) },
        platformTasks = {
            launch {
                pollMdnsAsJmdnsFallback(jmdns, serviceType, discoveryEvents)
            }
            if (localAddress == "10.0.2.15") {
                launch {
                    probeEmulatorGateway(peerId, config.displayName, localAddress, localPort, udpSocket)
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
                receiveJob.cancel()
            }
        }
    } finally {
        println("[PeerNet-$peerId] Stopping")
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
    val addr = InetAddress.getByName(localAddress)
    val jmdns = JmDNS.create(addr, "peer-android-$peerId")

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

/**
 * JmDNS's passive [ServiceListener] sometimes misses service events on Android, especially
 * on emulators. This fallback actively queries mDNS to catch anything the listener dropped.
 */
private suspend fun pollMdnsAsJmdnsFallback(
    jmdns: JmDNS,
    serviceType: String,
    discoveryEvents: SendChannel<ServiceDiscoveryEvent>,
) {
    delay(500.milliseconds)
    while (true) {
        try {
            val services = jmdns.list(serviceType)
            services?.forEach {
                discoveryEvents.trySend(ServiceDiscoveryEvent.Discovered(it.name))
            }
        } catch (_: Exception) {}
        delay(5.seconds)
    }
}

/**
 * On Android emulators (10.0.2.15), probes the host gateway (10.0.2.2) since mDNS doesn't
 * cross the emulator NAT — this is the only way to discover the host JVM peer.
 */
private suspend fun probeEmulatorGateway(
    peerId: String,
    peerName: String,
    localAddress: String,
    localPort: Int,
    udpSocket: DatagramSocket,
) {
    while (true) {
        delay(1.seconds)
        sendUdp(
            udpSocket, "10.0.2.2", MESSAGE_PORT,
            "$peerId:${formatHelloPayload(peerName, peerId, localAddress, localPort)}",
        )
    }
}

private suspend fun listenForPackets(
    udpSocket: DatagramSocket,
    peerId: String,
    receivedPackets: SendChannel<ReceivedPacket>,
) {
    val buffer = ByteArray(65535)
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
            println("[PeerNet-$peerId] Receive error: ${e.message}")
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
