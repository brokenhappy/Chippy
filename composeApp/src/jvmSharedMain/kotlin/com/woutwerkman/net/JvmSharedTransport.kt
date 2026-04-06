package com.woutwerkman.net

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

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
