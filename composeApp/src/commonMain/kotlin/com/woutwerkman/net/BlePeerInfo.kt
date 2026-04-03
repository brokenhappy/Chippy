package com.woutwerkman.net

/**
 * Compact binary encoding of peer info for BLE GATT exchange.
 *
 * Format:
 * ```
 * Byte 0:       Flags (bit 0: 0=IPv4, 1=IPv6)
 * Bytes 1-4:    IPv4 address (4 bytes) OR
 * Bytes 1-16:   IPv6 address (16 bytes)
 * Next 2:       UDP port (big-endian)
 * Remaining:    peerId (UTF-8, variable length)
 * ```
 */

private const val FLAG_IPV6: Byte = 0x01

fun encodePeerInfoForBle(peerId: String, address: String, port: Int): ByteArray {
    val ipBytes = encodeIpAddress(address)
    val peerIdBytes = peerId.encodeToByteArray()
    val flags: Byte = if (ipBytes.size == 16) FLAG_IPV6 else 0
    val result = ByteArray(1 + ipBytes.size + 2 + peerIdBytes.size)
    var offset = 0
    result[offset++] = flags
    ipBytes.copyInto(result, offset)
    offset += ipBytes.size
    result[offset++] = (port shr 8).toByte()
    result[offset++] = (port and 0xFF).toByte()
    peerIdBytes.copyInto(result, offset)
    return result
}

fun decodePeerInfoFromBle(data: ByteArray): Triple<String, String, Int>? {
    if (data.isEmpty()) return null
    val isIpv6 = (data[0].toInt() and FLAG_IPV6.toInt()) != 0
    val ipSize = if (isIpv6) 16 else 4
    // flags(1) + ip(ipSize) + port(2) + at least 1 byte peerId
    if (data.size < 1 + ipSize + 2 + 1) return null
    var offset = 1
    val ipBytes = data.copyOfRange(offset, offset + ipSize)
    offset += ipSize
    val port = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
    offset += 2
    val peerId = data.copyOfRange(offset, data.size).decodeToString()
    val address = if (isIpv6) formatIpv6(ipBytes) else formatIpv4(ipBytes)
    return Triple(peerId, address, port)
}

private fun encodeIpAddress(address: String): ByteArray {
    // Strip IPv6 zone id (e.g. "%en0")
    val cleaned = if ('%' in address) address.substringBefore('%') else address
    return if (':' in cleaned) encodeIpv6(cleaned) else encodeIpv4(cleaned)
}

private fun encodeIpv4(address: String): ByteArray {
    val parts = address.split('.')
    require(parts.size == 4) { "Invalid IPv4 address: $address" }
    return ByteArray(4) { parts[it].toInt().toByte() }
}

private fun encodeIpv6(address: String): ByteArray {
    val result = ByteArray(16)
    // Handle :: expansion
    val halves = address.split("::")
    val leftGroups = if (halves[0].isEmpty()) emptyList() else halves[0].split(':')
    val rightGroups = if (halves.size > 1 && halves[1].isNotEmpty()) halves[1].split(':') else emptyList()
    val expandedSize = 8 - leftGroups.size - rightGroups.size

    var idx = 0
    for (group in leftGroups) {
        val value = group.toInt(16)
        result[idx++] = (value shr 8).toByte()
        result[idx++] = (value and 0xFF).toByte()
    }
    if (halves.size > 1) {
        idx += expandedSize * 2
    }
    for (group in rightGroups) {
        val value = group.toInt(16)
        result[idx++] = (value shr 8).toByte()
        result[idx++] = (value and 0xFF).toByte()
    }
    return result
}

private fun formatIpv4(bytes: ByteArray): String =
    bytes.joinToString(".") { (it.toInt() and 0xFF).toString() }

private fun formatIpv6(bytes: ByteArray): String {
    val groups = (0 until 8).map { i ->
        val high = bytes[i * 2].toInt() and 0xFF
        val low = bytes[i * 2 + 1].toInt() and 0xFF
        (high shl 8) or low
    }
    // Find longest run of zeros for :: compression
    var bestStart = -1
    var bestLen = 0
    var curStart = -1
    var curLen = 0
    for (i in groups.indices) {
        if (groups[i] == 0) {
            if (curStart == -1) curStart = i
            curLen++
            if (curLen > bestLen) {
                bestStart = curStart
                bestLen = curLen
            }
        } else {
            curStart = -1
            curLen = 0
        }
    }
    if (bestLen < 2) {
        // No compression
        return groups.joinToString(":") { it.toString(16) }
    }
    val left = groups.take(bestStart).joinToString(":") { it.toString(16) }
    val right = groups.drop(bestStart + bestLen).joinToString(":") { it.toString(16) }
    return "$left::$right"
}
