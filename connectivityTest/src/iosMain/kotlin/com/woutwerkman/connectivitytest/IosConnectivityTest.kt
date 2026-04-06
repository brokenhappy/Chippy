package com.woutwerkman.connectivitytest

import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import platform.Foundation.NSLog
import platform.posix.*

private fun htons(value: UShort): UShort =
    ((value.toInt() and 0xFF) shl 8 or (value.toInt() shr 8 and 0xFF)).toUShort()

/**
 * Entry point for iOS connectivity test with TCP control channel.
 * Called from Swift AppDelegate. Returns a StateFlow for the Compose UI.
 */
fun runIosConnectivityTest(
    instanceId: String,
    platforms: String,
    controlHost: String,
    controlPort: Int,
    onComplete: (success: Boolean, message: String) -> Unit,
): StateFlow<ConnectivityTestUiState> {
    val targets = platforms.split(",")
        .mapNotNull { TestPlatform.fromString(it.trim()) }
        .toSet()

    val uiState = MutableStateFlow(
        ConnectivityTestUiState(
            instanceId = instanceId,
            targets = targets.associateWith { false },
        )
    )

    if (targets.isEmpty()) {
        onComplete(false, "No valid platforms: $platforms")
        return uiState
    }

    NSLog("[iOS-Test] Starting: instanceId=$instanceId, platforms=$platforms, control=$controlHost:$controlPort")

    // Must use GlobalScope — called from AppDelegate which has no coroutine parent.
    // The iOS app process lifetime is managed by the coordinator; when coordinator
    // cancels, the process is killed, so there's no leak risk.
    @OptIn(DelicateCoroutinesApi::class)
    GlobalScope.launch(Dispatchers.Default) {
        try {
            val controlSocket = connectToControlServer(controlHost, controlPort)
            if (controlSocket < 0) {
                onComplete(false, "Failed to connect to control server")
                return@launch
            }

            try {
                sendControlLine(controlSocket, "HELLO:$instanceId")

                runConnectivityTestProtocol(
                    instanceId = instanceId,
                    targets = targets,
                    uiState = uiState,
                    sendLine = { line ->
                        sendControlLine(controlSocket, line)
                        NSLog("[iOS-Test] Sent: $line")
                    },
                    readLine = {
                        readControlLine(controlSocket).also {
                            NSLog("[iOS-Test] Received: $it")
                        }
                    },
                )

                NSLog("[iOS-Test] SUCCESS!")
                onComplete(true, "All platforms connected")
            } finally {
                close(controlSocket)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            NSLog("[iOS-Test] Error: ${e.message}")
            onComplete(false, "Error: ${e.message}")
        }
    }

    return uiState
}

// --- TCP Control Channel (POSIX sockets) ---

@OptIn(ExperimentalForeignApi::class)
private fun connectToControlServer(host: String, port: Int): Int {
    val fd = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP)
    if (fd < 0) {
        NSLog("[iOS-Test] Failed to create TCP socket")
        return -1
    }

    val result = memScoped {
        val addr = alloc<sockaddr_in>()
        addr.sin_family = AF_INET.toUByte()
        addr.sin_port = htons(port.toUShort())
        val parts = host.split(".").map { it.toUByte() }
        if (parts.size == 4) {
            val ipBytes = parts[0].toUInt() or
                    (parts[1].toUInt() shl 8) or
                    (parts[2].toUInt() shl 16) or
                    (parts[3].toUInt() shl 24)
            addr.sin_addr.s_addr = ipBytes
        }
        connect(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().toUInt())
    }

    if (result < 0) {
        NSLog("[iOS-Test] Failed to connect to $host:$port (errno=$errno)")
        close(fd)
        return -1
    }

    NSLog("[iOS-Test] Connected to control server $host:$port")
    return fd
}

@OptIn(ExperimentalForeignApi::class)
private fun sendControlLine(fd: Int, line: String) {
    val data = "$line\n".encodeToByteArray()
    data.usePinned { pinned ->
        write(fd, pinned.addressOf(0), data.size.toULong())
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun readControlLine(fd: Int): String {
    val buffer = StringBuilder()
    val buf = ByteArray(1)
    buf.usePinned { pinned ->
        while (true) {
            val n = read(fd, pinned.addressOf(0), 1u)
            if (n <= 0) break
            val ch = buf[0].toInt().toChar()
            if (ch == '\n') break
            buffer.append(ch)
        }
    }
    return buffer.toString().trimEnd('\r')
}
