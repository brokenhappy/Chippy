package com.woutwerkman.net

import com.woutwerkman.util.withProcess
import kotlinx.coroutines.*
import java.net.*

internal actual fun createPlatformTransportConfig(
    config: PeerNetConfig,
    peerId: String,
    localAddress: String,
    socket: UdpSocket,
    sd: ServiceDiscovery,
): PlatformTransportConfig {
    val isMac = System.getProperty("os.name").lowercase().contains("mac")

    return if (!isMac) PlatformTransportConfig() else PlatformTransportConfig(
        platformTasks = {
            launch(Dispatchers.IO) {
                withProcess(
                    ProcessBuilder("dns-sd", "-B", "_${config.serviceName}._udp.", "local.")
                        .redirectErrorStream(true)
                ) { process ->
                    readDnsSdOutput(process, config.serviceName, peerId, sd)
                }
            }
        },
    )
}

/**
 * Reads dns-sd browse output and sends discovery events.
 * Blocks on readLine() — destroying the process unblocks it (readLine returns null).
 */
private fun readDnsSdOutput(
    process: Process,
    serviceName: String,
    peerId: String,
    sd: ServiceDiscovery,
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
                    sd.trySendEvent(ServiceDiscoveryEvent.Discovered(instanceName))
                }
            }
        }
    } catch (_: Exception) {}
}

internal actual fun getLocalIpAddress(): String {
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
