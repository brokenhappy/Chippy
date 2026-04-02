package com.woutwerkman.net

import kotlinx.cinterop.*
import platform.darwin.*
import platform.posix.*

@OptIn(ExperimentalForeignApi::class)
internal fun getLocalIpAddress(): String {
    memScoped {
        val ifaddrsVar = alloc<CPointerVar<ifaddrs>>()
        if (getifaddrs(ifaddrsVar.ptr) == 0) {
            var current = ifaddrsVar.value
            var wifiIp: String? = null
            var otherIp: String? = null

            while (current != null) {
                val addr = current.pointed
                if (addr.ifa_addr != null && addr.ifa_addr!!.pointed.sa_family == AF_INET.toUByte()) {
                    val name = addr.ifa_name?.toKString() ?: ""
                    val sockaddr = addr.ifa_addr!!.reinterpret<sockaddr_in>()
                    val ip = inet_ntoa(sockaddr.pointed.sin_addr.readValue())?.toKString()

                    if (ip != null && !ip.startsWith("127.") && !ip.startsWith("169.254.") &&
                        !ip.startsWith("100.64.") && !ip.startsWith("100.96.")) {
                        when {
                            name == "en0" || name == "bridge100" -> wifiIp = ip
                            !name.startsWith("pdp_ip") && !name.startsWith("ipsec") &&
                            !name.startsWith("utun") -> otherIp = otherIp ?: ip
                        }
                    }
                }
                current = addr.ifa_next
            }
            freeifaddrs(ifaddrsVar.value)

            return wifiIp ?: otherIp ?: "0.0.0.0"
        }
    }
    return "0.0.0.0"
}
