package com.woutwerkman.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.withContext
import java.net.*
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

/**
 * Creates and configures a UDP socket, preferring [MESSAGE_PORT] but falling back to a random port.
 */
internal fun createUdpSocket(peerId: String): DatagramSocket {
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

internal fun sendUdp(udpSocket: DatagramSocket, address: String, port: Int, message: String) {
    try {
        val data = message.toByteArray(Charsets.UTF_8)
        val packet = DatagramPacket(data, data.size, InetAddress.getByName(address), port)
        udpSocket.send(packet)
    } catch (_: Exception) {}
}

/**
 * Blocking receive loop that forwards raw UDP packets to [receivedPackets].
 * Must run on [Dispatchers.IO] — the socket's soTimeout (100ms) allows cancellation checks.
 */
internal suspend fun listenForPackets(
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

/**
 * Creates a JmDNS instance, registers a service, and listens for peer discoveries.
 * Used by both Android and JVM.
 */
internal fun startJmdns(
    peerId: String,
    localAddress: String,
    localPort: Int,
    serviceType: String,
    peerName: String,
    discoveryEvents: SendChannel<ServiceDiscoveryEvent>,
    jmdnsHostname: String = localAddress,
): JmDNS {
    val inetAddress = InetAddress.getByName(localAddress)
    val jmdns = JmDNS.create(inetAddress, jmdnsHostname)

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
