package com.woutwerkman.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BlePeerInfoTest {

    @Test
    fun roundTripIpv4() {
        val encoded = encodePeerInfoForBle("player-1", "192.168.1.42", 12345)
        val (peerId, address, port) = decodePeerInfoFromBle(encoded)!!
        assertEquals("player-1", peerId)
        assertEquals("192.168.1.42", address)
        assertEquals(12345, port)
    }

    @Test
    fun roundTripIpv4Size() {
        // 1 flag + 4 ip + 2 port + 8 peerId = 15 bytes
        val encoded = encodePeerInfoForBle("player-1", "10.0.0.1", 8080)
        assertEquals(15, encoded.size)
    }

    @Test
    fun roundTripIpv6Full() {
        val encoded = encodePeerInfoForBle("peer-abc", "2001:db8:85a3:0:0:8a2e:370:7334", 9999)
        val (peerId, address, port) = decodePeerInfoFromBle(encoded)!!
        assertEquals("peer-abc", peerId)
        assertEquals(9999, port)
        // Verify round-trip produces valid IPv6 that re-encodes to same bytes
        val reEncoded = encodePeerInfoForBle(peerId, address, port)
        assertEquals(encoded.toList(), reEncoded.toList())
    }

    @Test
    fun roundTripIpv6Compressed() {
        val encoded = encodePeerInfoForBle("test", "fe80::1", 5000)
        val (peerId, address, port) = decodePeerInfoFromBle(encoded)!!
        assertEquals("test", peerId)
        assertEquals(5000, port)
        // Should re-encode to same binary
        val reEncoded = encodePeerInfoForBle(peerId, address, port)
        assertEquals(encoded.toList(), reEncoded.toList())
    }

    @Test
    fun roundTripIpv6LinkLocal() {
        // Link-local with zone ID stripped
        val encoded = encodePeerInfoForBle("ios-1", "fe80::1%en0", 4321)
        val (peerId, address, port) = decodePeerInfoFromBle(encoded)!!
        assertEquals("ios-1", peerId)
        assertEquals(4321, port)
        // Zone ID is stripped in encoding, so address won't have %en0
        val reEncoded = encodePeerInfoForBle(peerId, address, port)
        assertEquals(encoded.toList(), reEncoded.toList())
    }

    @Test
    fun roundTripIpv6Size() {
        // 1 flag + 16 ip + 2 port + 4 peerId = 23 bytes
        val encoded = encodePeerInfoForBle("test", "::1", 80)
        assertEquals(23, encoded.size)
    }

    @Test
    fun roundTripMaxPort() {
        val encoded = encodePeerInfoForBle("p", "10.0.0.1", 65535)
        val (_, _, port) = decodePeerInfoFromBle(encoded)!!
        assertEquals(65535, port)
    }

    @Test
    fun roundTripPortZero() {
        val encoded = encodePeerInfoForBle("p", "10.0.0.1", 0)
        val (_, _, port) = decodePeerInfoFromBle(encoded)!!
        assertEquals(0, port)
    }

    @Test
    fun decodeEmptyReturnsNull() {
        assertNull(decodePeerInfoFromBle(ByteArray(0)))
    }

    @Test
    fun decodeTooShortIpv4ReturnsNull() {
        // Need at least 1 + 4 + 2 + 1 = 8 bytes for IPv4
        assertNull(decodePeerInfoFromBle(ByteArray(7)))
    }

    @Test
    fun decodeTooShortIpv6ReturnsNull() {
        // Need at least 1 + 16 + 2 + 1 = 20 bytes for IPv6
        val data = ByteArray(19)
        data[0] = 0x01 // IPv6 flag
        assertNull(decodePeerInfoFromBle(data))
    }

    @Test
    fun ipv4FlagBitIsZero() {
        val encoded = encodePeerInfoForBle("x", "1.2.3.4", 1)
        assertEquals(0, encoded[0].toInt() and 0x01)
    }

    @Test
    fun ipv6FlagBitIsOne() {
        val encoded = encodePeerInfoForBle("x", "::1", 1)
        assertEquals(1, encoded[0].toInt() and 0x01)
    }
}
