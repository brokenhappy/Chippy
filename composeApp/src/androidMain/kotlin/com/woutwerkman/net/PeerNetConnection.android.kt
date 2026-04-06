package com.woutwerkman.net

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import java.net.*
import javax.jmdns.JmDNS

internal actual suspend fun <T> withTransport(
    config: PeerNetConfig,
    peerId: String,
    block: suspend CoroutineScope.(TransportHandle) -> T,
): T {
    val serviceType = "_${config.serviceName}._udp.local."
    val discoveryEvents = Channel<ServiceDiscoveryEvent>(Channel.BUFFERED)

    val localAddress = getLocalIpAddress()
    println("[PeerNet-$peerId] Local IP: $localAddress")

    return withUdpSocket(peerId) { socket ->
        println("[PeerNet-$peerId] Bound to port ${socket.localPort}")

        val jmdns = startJmdns(
            peerId, localAddress, socket.localPort, serviceType, config.displayName, discoveryEvents,
            jmdnsHostname = "peer-android-$peerId",
        )

        val handle = TransportHandle(
            localAddress = localAddress,
            localPort = socket.localPort,
            discoveryEvents = discoveryEvents,
            receivedPackets = socket.receivedPackets(),
            sendUdp = { address, port, message -> socket.send(address, port, message) },
            platformTasks = {
                launch {
                    pollMdnsAsJmdnsFallback(jmdns, serviceType, discoveryEvents)
                }
                if (localAddress == "10.0.2.15") {
                    launch {
                        probeEmulatorGateway(peerId, config.displayName, localAddress, socket.localPort, socket)
                    }
                }
                // BLE transport — discovery + data fallback (mirrors iOS pattern)
                launch {
                    withBleTransport(peerId, localAddress, socket.localPort) { ble ->
                        launch {
                            for (peerInfo in ble.discoveredPeers) {
                                val serviceName = formatServiceName(
                                    peerInfo.name, peerInfo.id, peerInfo.address, peerInfo.port,
                                )
                                discoveryEvents.trySend(ServiceDiscoveryEvent.Discovered(serviceName))
                            }
                        }
                        launch {
                            for ((fromPeerId, payload) in ble.incoming) {
                                // BLE incoming data needs to be formatted as "peerId:payload"
                                // to match the UDP message format expected by the common layer.
                                // Use trySend on a channel to bridge into the flow — but since
                                // receivedPackets is a Flow, we'd need another approach.
                                // TODO: This needs a channel bridge or a merged flow approach
                            }
                        }
                        awaitCancellation()
                    }
                }
            },
        )

        try {
            block(handle)
        } finally {
            try { jmdns.unregisterAllServices(); jmdns.close() } catch (_: Exception) {}
            println("[PeerNet-$peerId] Stopped")
        }
    }
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
    socket: UdpSocket,
) {
    while (true) {
        delay(1.seconds)
        socket.send(
            "10.0.2.2", MESSAGE_PORT,
            "$peerId:${formatHelloPayload(peerName, peerId, localAddress, localPort)}",
        )
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
