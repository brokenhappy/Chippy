package com.woutwerkman

import com.woutwerkman.net.*
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import platform.Foundation.NSLog
import platform.posix.*

private fun htons(value: UShort): UShort =
    ((value.toInt() and 0xFF) shl 8 or (value.toInt() shr 8 and 0xFF)).toUShort()

/**
 * Entry point for iOS connectivity test with TCP control channel.
 * Called from Swift AppDelegate.
 */
fun runIosConnectivityTest(
    instanceId: String,
    platforms: String,
    controlHost: String,
    controlPort: Int,
    onComplete: (success: Boolean, message: String) -> Unit,
) {
    NSLog("[iOS-Test] Starting: instanceId=$instanceId, platforms=$platforms, control=$controlHost:$controlPort")

    val targets = platforms.split(",")
        .mapNotNull { TestPlatform.fromString(it.trim()) }
        .toSet()

    if (targets.isEmpty()) {
        onComplete(false, "No valid platforms: $platforms")
        return
    }

    // Must use GlobalScope — called from AppDelegate which has no coroutine parent.
    // The iOS app process lifetime is managed by the coordinator; when coordinator
    // cancels, the process is killed, so there's no leak risk.
    @OptIn(DelicateCoroutinesApi::class)
    GlobalScope.launch(Dispatchers.Main) {
        try {
            val controlSocket = connectToControlServer(controlHost, controlPort)
            if (controlSocket < 0) {
                onComplete(false, "Failed to connect to control server")
                return@launch
            }

            try {
                // Identify ourselves to the coordinator's accept loop
                sendControlLine(controlSocket, "HELLO:$instanceId")

                withPeerNetConnection(
                    PeerNetConfig(serviceName = "chippy-test", displayName = instanceId)
                ) { conn ->
                    sendControlLine(controlSocket, "READY")
                    NSLog("[iOS-Test] Sent READY, waiting for START...")

                    // Wait for START from coordinator
                    val startLine = readControlLine(controlSocket)
                    if (startLine != "START") {
                        sendControlLine(controlSocket, "ERROR:Expected START, got: $startLine")
                        onComplete(false, "Expected START, got: $startLine")
                        return@withPeerNetConnection
                    }
                    NSLog("[iOS-Test] Received START, beginning discovery")

                    // Monitor state and report discovered platforms
                    val found = mutableSetOf<TestPlatform>()
                    conn.state.first { state ->
                        val matched = matchedPlatforms(state, instanceId, targets)
                        for (p in matched - found) {
                            found.add(p)
                            sendControlLine(controlSocket, "FOUND:${p.toPlatformString()}")
                            NSLog("[iOS-Test] Found ${p.toPlatformString()}")
                        }
                        found.containsAll(targets)
                    }

                    sendControlLine(controlSocket, "DONE")
                    NSLog("[iOS-Test] SUCCESS! All platforms found")
                    onComplete(true, "All platforms connected")
                }
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
}

// Keep the old signature for backwards compatibility during transition
fun runIosConnectivityTest(
    instanceId: String,
    platforms: String,
    onComplete: (success: Boolean, message: String) -> Unit,
) {
    NSLog("[iOS-Test] WARNING: No control channel provided, running in legacy mode")
    onComplete(false, "No control channel — coordinator must pass --control-host/--control-port")
}

private fun matchedPlatforms(
    state: PeerNetState,
    instanceId: String,
    targets: Set<TestPlatform>,
): Set<TestPlatform> {
    val matched = mutableSetOf<TestPlatform>()
    for ((peerId, peer) in state.discoveredPeers) {
        if (peer.name == instanceId) continue
        val platform = TestPlatform.fromPeerId(peerId) ?: continue
        val normalized = normalizePlatform(platform, targets)
        if (normalized != null) matched.add(normalized)
    }
    return matched
}

private fun normalizePlatform(platform: TestPlatform, targets: Set<TestPlatform>): TestPlatform? {
    if (platform in targets) return platform
    if (platform == TestPlatform.IOS_REAL_DEVICE || platform == TestPlatform.IOS_SIMULATOR) {
        if (TestPlatform.IOS_REAL_DEVICE in targets) return TestPlatform.IOS_REAL_DEVICE
        if (TestPlatform.IOS_SIMULATOR in targets) return TestPlatform.IOS_SIMULATOR
    }
    if (platform == TestPlatform.ANDROID_REAL_DEVICE || platform == TestPlatform.ANDROID_SIMULATOR) {
        if (TestPlatform.ANDROID_REAL_DEVICE in targets) return TestPlatform.ANDROID_REAL_DEVICE
        if (TestPlatform.ANDROID_SIMULATOR in targets) return TestPlatform.ANDROID_SIMULATOR
    }
    return null
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
        // Parse IPv4 address manually (inet_pton not available in all K/N interops)
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
