package com.woutwerkman.net

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import java.net.*

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
            jmdnsHostname = "peer-jvm-$peerId",
        )

        val isMac = System.getProperty("os.name").lowercase().contains("mac")
        val dnsSdProcess: Process? = if (isMac) {
            ProcessBuilder("dns-sd", "-B", "_${config.serviceName}._udp.", "local.")
                .redirectErrorStream(true)
                .start()
        } else null

        val handle = TransportHandle(
            localAddress = localAddress,
            localPort = socket.localPort,
            discoveryEvents = discoveryEvents,
            receivedPackets = socket.receivedPackets(),
            sendUdp = { address, port, message -> socket.send(address, port, message) },
            platformTasks = {
                if (dnsSdProcess != null) {
                    launch(Dispatchers.IO) {
                        readDnsSdOutput(dnsSdProcess, config.serviceName, peerId, discoveryEvents)
                    }
                }
            },
        )

        try {
            block(handle)
        } finally {
            dnsSdProcess?.destroyForcibly()
            try { jmdns.unregisterAllServices(); jmdns.close() } catch (_: Exception) {}
            println("[PeerNet-$peerId] Stopped")
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
