package com.woutwerkman.net

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.awaitCancellation
import java.net.*

internal actual fun createPlatformTransportConfig(
    config: PeerNetConfig,
    peerId: String,
    localAddress: String,
    socket: UdpSocket,
    sd: ServiceDiscovery,
): PlatformTransportConfig = PlatformTransportConfig(
    platformTasks = {
        launch {
            pollMdnsAsJmdnsFallback(sd)
        }
        if (localAddress == "10.0.2.15") {
            launch {
                probeEmulatorGateway(peerId, config.displayName, localAddress, socket.localPort, socket)
            }
        }
        launch {
            withBleTransport(peerId, localAddress, socket.localPort) { ble ->
                launch {
                    for (peerInfo in ble.discoveredPeers) {
                        val serviceName = formatServiceName(
                            peerInfo.name, peerInfo.id, peerInfo.address, peerInfo.port,
                        )
                        sd.trySendEvent(ServiceDiscoveryEvent.Discovered(serviceName))
                    }
                }
                launch {
                    for ((fromPeerId, payload) in ble.incoming) {
                        // TODO: This needs a channel bridge or a merged flow approach
                    }
                }
                awaitCancellation()
            }
        }
    },
)

/**
 * JmDNS's passive [ServiceListener] sometimes misses service events on Android, especially
 * on emulators. This fallback actively queries mDNS to catch anything the listener dropped.
 */
private suspend fun pollMdnsAsJmdnsFallback(sd: ServiceDiscovery) {
    delay(500.milliseconds)
    while (true) {
        try {
            val services = sd.jmdns.list(sd.serviceType)
            services?.forEach {
                sd.trySendEvent(ServiceDiscoveryEvent.Discovered(it.name))
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

internal actual fun getLocalIpAddress(): String {
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
