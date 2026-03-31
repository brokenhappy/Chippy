package com.woutwerkman.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.serialization.Serializable

/**
 * Represents a discovered peer on the network.
 */
@Serializable
data class PeerInfo(
    val id: String,
    val name: String,
    val address: String,
    val port: Int
)

/**
 * Messages that can be sent/received over the peer network.
 */
@Serializable
sealed class RawPeerMessage {
    @Serializable
    sealed class Event : RawPeerMessage() {
        @Serializable
        data class Connected(val peer: PeerInfo) : Event()

        @Serializable
        data class Disconnected(val peerId: String) : Event()
    }

    @Serializable
    data class Received(
        val fromPeerId: String,
        val payload: ByteArray
    ) : RawPeerMessage() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false
            other as Received
            return fromPeerId == other.fromPeerId && payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int {
            var result = fromPeerId.hashCode()
            result = 31 * result + payload.contentHashCode()
            return result
        }
    }
}

/**
 * Command to send to the peer network.
 */
@Serializable
sealed class PeerCommand {
    /**
     * Send data to a specific peer.
     */
    @Serializable
    data class SendTo(
        val peerId: String,
        val payload: ByteArray
    ) : PeerCommand() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false
            other as SendTo
            return peerId == other.peerId && payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int {
            var result = peerId.hashCode()
            result = 31 * result + payload.contentHashCode()
            return result
        }
    }

    /**
     * Broadcast data to all discovered peers.
     */
    @Serializable
    data class Broadcast(
        val payload: ByteArray
    ) : PeerCommand() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false
            other as Broadcast
            return payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int = payload.contentHashCode()
    }
}

/** A raw connection to the peer network (internal transport layer). */
class RawPeerNetConnection(
    val localPeerId: String,
    val incoming: ReceiveChannel<RawPeerMessage>,
    val outgoing: SendChannel<PeerCommand>,
)

/**
 * Configuration for the peer network connection.
 */
data class PeerNetConfig(
    val serviceName: String = "chippy",
    val displayName: String = "Peer",
)

/**
 * Establishes a raw connection to the peer network (internal transport layer).
 */
internal suspend fun <T> withRawPeerNetConnection(
    config: PeerNetConfig = PeerNetConfig(),
    block: suspend CoroutineScope.(RawPeerNetConnection) -> T
): T = withRawPeerNetConnectionImpl(config, block)

/**
 * Platform-specific implementation of [withRawPeerNetConnection].
 */
internal expect suspend fun <T> withRawPeerNetConnectionImpl(
    config: PeerNetConfig,
    block: suspend CoroutineScope.(RawPeerNetConnection) -> T
): T
