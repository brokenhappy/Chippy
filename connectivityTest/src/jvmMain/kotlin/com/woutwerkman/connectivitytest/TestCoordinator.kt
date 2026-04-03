package com.woutwerkman.connectivitytest

import com.woutwerkman.net.TestPlatform
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Structured concurrency based test coordinator with bidirectional control channels.
 *
 * Flow:
 * 1. Start TCP control server, launch all platforms in parallel
 * 2. Accept TCP connections, route to correct platform by HELLO message
 * 3. Wait for all to send READY (spin-up phase)
 * 4. Send START to all (discovery gate)
 * 5. Wait for all to send DONE (discovery phase)
 *
 * Fail fast: any platform failure cancels all siblings via structured concurrency.
 */
class TestCoordinator(
    private val platforms: List<PlatformConfig>,
    private val spinUpTimeout: Duration = 15.seconds,
    private val discoveryTimeout: Duration = 20.seconds,
    private val logger: (String) -> Unit = ::println,
) {

    sealed class TestResult {
        data object Success : TestResult()
        data class Failure(val message: String, val cause: Throwable? = null) : TestResult()
    }

    suspend fun run(): TestResult {
        val controlServer = withContext(Dispatchers.IO) {
            ServerSocket(0).also { it.soTimeout = 0 }
        }
        val controlPort = controlServer.localPort
        val controlHost = getLocalIpAddress()

        logger("Control server listening on $controlHost:$controlPort")

        return try {
            coroutineScope {
                // Pending socket channels: each runner waits for its socket
                val pendingSockets = ConcurrentHashMap<String, CompletableDeferred<Socket>>()
                val toProcessChannels = ConcurrentHashMap<String, SendChannel<String>>()
                val allReady = Channel<PlatformConfig>(platforms.size)
                val allDone = Channel<PlatformConfig>(platforms.size)

                // Count how many platforms need TCP connections (exclude JVM — it uses in-process channels)
                val tcpPlatforms = platforms.filter { it.runner is ExternalProcessRunner }
                tcpPlatforms.forEach { pendingSockets[it.instanceId] = CompletableDeferred() }

                // Accept TCP connections and route by HELLO:<instanceId>
                // Reads HELLO line byte-by-byte to avoid buffering past the first line.
                val acceptJob = launch(Dispatchers.IO) {
                    var remaining = tcpPlatforms.size
                    while (remaining > 0 && isActive) {
                        val socket = controlServer.accept()
                        val helloLine = readLineRaw(socket)
                        if (helloLine != null && helloLine.startsWith("HELLO:")) {
                            val id = helloLine.substringAfter("HELLO:")
                            val deferred = pendingSockets[id]
                            if (deferred != null) {
                                logger("[$id] TCP control connected")
                                deferred.complete(socket)
                                remaining--
                            } else {
                                logger("Unknown platform connected: $id")
                                socket.close()
                            }
                        } else {
                            logger("Unexpected first message: $helloLine")
                            socket.close()
                        }
                    }
                }

                // Launch all platforms in parallel.
                // Each is a child coroutine — if any fails, the scope cancels all siblings.
                platforms.forEach { platform ->
                    val otherTypes = platforms.map { it.type }.toSet() - platform.type - TestPlatform.MAC_BLE_HELPER
                    val targets = otherTypes.map { it.toPlatformString() }
                    launch {
                        platform.runner.run(
                            instanceId = platform.instanceId,
                            targets = targets,
                            controlHost = controlHost,
                            controlPort = controlPort,
                            socketDeferred = pendingSockets[platform.instanceId],
                        ) { toProcess, fromProcess ->
                            toProcessChannels[platform.instanceId] = toProcess

                            // Wait for READY
                            for (line in fromProcess) {
                                if (line == "READY") {
                                    logger("[${platform.instanceId}] READY")
                                    allReady.send(platform)
                                    break
                                }
                                if (line.startsWith("ERROR:")) {
                                    error("[${platform.instanceId}] ${line.substringAfter("ERROR:")}")
                                }
                            }

                            // Wait for DONE
                            for (line in fromProcess) {
                                when {
                                    line.startsWith("FOUND:") -> {
                                        logger("[${platform.instanceId}] $line")
                                    }
                                    line == "DONE" -> {
                                        logger("[${platform.instanceId}] DONE")
                                        allDone.send(platform)
                                        return@run
                                    }
                                    line.startsWith("ERROR:") -> {
                                        error("[${platform.instanceId}] ${line.substringAfter("ERROR:")}")
                                    }
                                }
                            }
                        }
                    }
                }

                // Phase 1: Wait for ALL platforms to be ready
                withTimeout(spinUpTimeout) {
                    repeat(platforms.size) { allReady.receive() }
                }
                logger("All ${platforms.size} platforms ready — sending START")

                // Send START to all platforms
                toProcessChannels.values.forEach { it.send("START") }

                // Phase 2: Wait for all DONE (should be fast — everyone is already listening)
                withTimeout(discoveryTimeout) {
                    repeat(platforms.size) { allDone.receive() }
                }

                acceptJob.cancel()
                logger("SUCCESS: All platforms connected!")
                TestResult.Success
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger("FAILURE: ${e.message}")
            TestResult.Failure("Test failed: ${e.message}", e)
        } finally {
            withContext(Dispatchers.IO + NonCancellable) {
                controlServer.close()
            }
        }
    }
}

private fun getLocalIpAddress(): String {
    return try {
        val candidates = mutableListOf<Pair<String, String>>() // (ip, ifaceName)
        val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
        for (iface in interfaces) {
            if (iface.isLoopback || !iface.isUp) continue
            for (addr in iface.inetAddresses) {
                if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                    candidates.add(addr.hostAddress to iface.name)
                }
            }
        }
        // Prefer non-link-local (169.254.x.x) addresses, and prefer en0 (WiFi)
        candidates
            .filter { !it.first.startsWith("169.254.") }
            .sortedByDescending { it.second == "en0" }
            .firstOrNull()?.first
            ?: candidates.firstOrNull()?.first
            ?: "127.0.0.1"
    } catch (_: Exception) {
        "127.0.0.1"
    }
}

/**
 * Configuration for a platform to test.
 */
data class PlatformConfig(
    val type: TestPlatform,
    val instanceId: String,
    val runner: PlatformRunner,
)

/**
 * Bidirectional platform runner abstraction.
 *
 * The block receives channels for protocol communication:
 * - toProcess (SendChannel): coordinator → platform (START, STOP)
 * - fromProcess (ReceiveChannel): platform → coordinator (READY, FOUND, DONE, ERROR)
 */
interface PlatformRunner {
    suspend fun <T> run(
        instanceId: String,
        targets: List<String>,
        controlHost: String,
        controlPort: Int,
        socketDeferred: CompletableDeferred<Socket>?,
        block: suspend (toProcess: SendChannel<String>, fromProcess: ReceiveChannel<String>) -> T,
    ): T
}

/**
 * Marker interface for runners that spawn external processes
 * and need a TCP connection from the coordinator's accept loop.
 */
interface ExternalProcessRunner : PlatformRunner

/**
 * Read a single line from a socket byte-by-byte.
 * Avoids creating a BufferedReader which might buffer past the first line.
 */
private fun readLineRaw(socket: Socket): String? {
    val sb = StringBuilder()
    val input = socket.getInputStream()
    while (true) {
        val b = input.read()
        if (b == -1) return if (sb.isEmpty()) null else sb.toString()
        if (b == '\n'.code) return sb.toString().trimEnd('\r')
        sb.append(b.toChar())
    }
}
