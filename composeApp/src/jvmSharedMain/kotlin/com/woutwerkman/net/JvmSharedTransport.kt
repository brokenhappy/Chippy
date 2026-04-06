package com.woutwerkman.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runInterruptible
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
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
    try {
        while (currentCoroutineContext().isActive) {
            buffer.clear()
            val sender = runInterruptible { channel.receive(buffer) } as InetSocketAddress
            buffer.flip()
            val message = Charsets.UTF_8.decode(buffer).toString()
            emit(ReceivedPacket(message, sender.address.hostAddress ?: "", sender.port))
        }
    } catch (_: java.nio.channels.ClosedByInterruptException) {
        // Expected on cancellation — runInterruptible interrupts the thread,
        // which closes the InterruptibleChannel. Not a CancellationException,
        // so we catch it to avoid poisoning the parent scope.
    }
}

// ==================== ServiceDiscovery actuals ====================

internal actual class ServiceDiscovery(
    val jmdns: JmDNS,
    val channel: Channel<ServiceDiscoveryEvent>,
    val serviceType: String,
)

internal actual suspend fun <T> withServiceDiscovery(
    peerId: String,
    serviceName: String,
    displayName: String,
    localAddress: String,
    localPort: Int,
    block: suspend CoroutineScope.(ServiceDiscovery) -> T,
): T {
    val serviceType = "_${serviceName}._udp.local."
    val channel = Channel<ServiceDiscoveryEvent>(Channel.BUFFERED)
    val inetAddress = InetAddress.getByName(localAddress)
    val jmdns = JmDNS.create(inetAddress, "peer-$peerId")

    jmdns.addServiceListener(serviceType, object : ServiceListener {
        override fun serviceAdded(event: ServiceEvent) {
            jmdns.requestServiceInfo(event.type, event.name, true)
        }

        override fun serviceRemoved(event: ServiceEvent) {
            channel.trySend(ServiceDiscoveryEvent.Removed(event.name))
        }

        override fun serviceResolved(event: ServiceEvent) {
            channel.trySend(ServiceDiscoveryEvent.Discovered(event.name))
        }
    })

    val fullServiceName = formatServiceName(displayName, peerId, localAddress, localPort)
    val serviceInfo = ServiceInfo.create(serviceType, fullServiceName, localPort, "PeerNet")
    jmdns.registerService(serviceInfo)
    println("[PeerNet-$peerId] mDNS service registered: $fullServiceName")

    val sd = ServiceDiscovery(jmdns, channel, serviceType)
    return try {
        coroutineScope { block(sd) }
    } finally {
        // JmDNS cleanup (unregister + close) blocks for seconds while sending goodbye
        // packets and waiting for internal threads to wind down. Run it all on a daemon
        // thread so coroutine cancellation/completion isn't blocked.
        Thread {
            try { jmdns.unregisterService(serviceInfo) } catch (_: Exception) {}
            try { jmdns.close() } catch (_: Exception) {}
        }.apply { isDaemon = true }.start()
    }
}

internal actual val ServiceDiscovery.events: ReceiveChannel<ServiceDiscoveryEvent>
    get() = channel

internal actual fun ServiceDiscovery.trySendEvent(event: ServiceDiscoveryEvent) {
    channel.trySend(event)
}
