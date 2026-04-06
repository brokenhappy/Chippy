package com.woutwerkman.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runInterruptible
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ClosedByInterruptException
import java.nio.channels.DatagramChannel
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

internal actual class UdpSocket(val channel: DatagramChannel)

internal actual suspend fun <T> withUdpSocket(
    peerId: String,
    block: suspend CoroutineScope.(UdpSocket) -> T,
): T = DatagramChannel.open().apply {
    socket().broadcast = true
    try {
        bind(InetSocketAddress(MESSAGE_PORT))
    } catch (_: Exception) {
        println("[PeerNet-$peerId] Port $MESSAGE_PORT busy, using random port")
        bind(InetSocketAddress(0))
    }
}.use { channel ->
    coroutineScope { block(UdpSocket(channel)) }
}

internal actual val UdpSocket.localPort: Int
    get() = (channel.localAddress as InetSocketAddress).port

internal actual fun UdpSocket.send(address: String, port: Int, message: String) {
    channel.send(ByteBuffer.wrap(message.toByteArray()), InetSocketAddress(address, port))
}

internal actual fun UdpSocket.receivedPackets(): Flow<ReceivedPacket> = flow {
    val buffer = ByteBuffer.allocate(65535)
    while (currentCoroutineContext().isActive) {
        buffer.clear()
        val sender = runInterruptible { channel.receive(buffer) } as InetSocketAddress
        buffer.flip()
        val message = Charsets.UTF_8.decode(buffer).toString()
        emit(ReceivedPacket(message, sender.address.hostAddress ?: "", sender.port))
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
