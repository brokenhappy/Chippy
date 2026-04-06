package com.woutwerkman.connectivitytest.launchers

import com.woutwerkman.connectivitytest.PlatformRunner
import com.woutwerkman.util.withProcess
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import java.net.*

/**
 * Base class for runners that spawn external processes with TCP control channels.
 *
 * Each ProcessRunner creates its own TCP server, passes the port to the external process,
 * and accepts a single connection. Protocol messages (READY, START, FOUND, DONE, ERROR)
 * flow over that connection. Process stdout/stderr is captured for logging only.
 */
abstract class ProcessRunner(
    private val logPrefix: String,
    private val logger: (String) -> Unit = ::println,
) : PlatformRunner {

    protected abstract suspend fun buildProcess(
        instanceId: String,
        targets: List<String>,
        controlHost: String,
        controlPort: Int,
    ): ProcessBuilder

    override suspend fun <T> run(
        instanceId: String,
        targets: List<String>,
        block: suspend (toProcess: SendChannel<String>, fromProcess: ReceiveChannel<String>) -> T,
    ): T {
        val controlServer = withContext(Dispatchers.IO) {
            ServerSocket(0).also { it.soTimeout = 0 }
        }
        val controlPort = controlServer.localPort
        val controlHost = getLocalIpAddress()

        try {
            return withProcess(buildProcess(instanceId, targets, controlHost, controlPort)) { process ->
                val stdoutJob = launchStreamLogger(process.inputStream, logPrefix)
                val stderrLines = mutableListOf<String>()
                val stderrJob = launchStreamLogger(process.errorStream, "$logPrefix/err", stderrLines)

                val socket = acceptControlConnection(controlServer, instanceId)

                val toProcess = Channel<String>(Channel.BUFFERED)
                val fromProcess = Channel<String>(Channel.BUFFERED)
                val readerJob = launchTcpReader(socket, fromProcess)
                val writerJob = launchTcpWriter(socket, toProcess)

                val blockCompleted = java.util.concurrent.atomic.AtomicBoolean(false)
                val processMonitor = launchProcessMonitor(process, blockCompleted, stderrLines)

                try {
                    val result = block(toProcess, fromProcess)
                    blockCompleted.set(true)
                    result
                } finally {
                    processMonitor.cancel()
                    withContext(NonCancellable) {
                        toProcess.close()
                        readerJob.cancel()
                        writerJob.cancel()
                        stdoutJob.cancel()
                        stderrJob.cancel()
                        withContext(Dispatchers.IO) { socket.close() }
                    }
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                controlServer.close()
            }
        }
    }

    private suspend fun acceptControlConnection(
        controlServer: ServerSocket,
        instanceId: String,
    ): Socket {
        val socket = withContext(Dispatchers.IO) {
            controlServer.accept()
        }
        val helloLine = withContext(Dispatchers.IO) { readLineRaw(socket) }
        val expectedPrefix = "HELLO:$instanceId"
        if (helloLine == null || !helloLine.startsWith(expectedPrefix)) {
            socket.close()
            error("[$logPrefix] Expected $expectedPrefix, got: $helloLine")
        }
        logger("[$logPrefix] TCP control connected")
        return socket
    }

    private fun CoroutineScope.launchStreamLogger(
        stream: java.io.InputStream,
        prefix: String,
        collectInto: MutableList<String>? = null,
    ) = launch(Dispatchers.IO) {
        try {
            stream.bufferedReader().use { reader ->
                reader.lineSequence().forEach { line ->
                    collectInto?.add(line)
                    logger("[$prefix] $line")
                }
            }
        } catch (_: Exception) {}
    }

    private fun CoroutineScope.launchTcpReader(
        socket: Socket,
        fromProcess: Channel<String>,
    ) = launch(Dispatchers.IO) {
        try {
            socket.getInputStream().bufferedReader().use { reader ->
                reader.lineSequence().forEach { line ->
                    fromProcess.send(line)
                }
            }
        } catch (_: Exception) {}
        fromProcess.close()
    }

    private fun CoroutineScope.launchTcpWriter(
        socket: Socket,
        toProcess: Channel<String>,
    ) = launch(Dispatchers.IO) {
        try {
            socket.getOutputStream().bufferedWriter().use { writer ->
                for (cmd in toProcess) {
                    writer.write(cmd)
                    writer.newLine()
                    writer.flush()
                }
            }
        } catch (_: Exception) {}
    }

    /**
     * Monitors for premature process exit — if the process crashes before completing the
     * protocol, the error propagates up and cancels all siblings. Once the block completes
     * successfully, exit code errors are suppressed because some tools (e.g. devicectl --console)
     * exit with 1 even on clean shutdown.
     */
    private fun CoroutineScope.launchProcessMonitor(
        process: Process,
        blockCompleted: java.util.concurrent.atomic.AtomicBoolean,
        stderrLines: List<String>,
    ) = launch(Dispatchers.IO) {
        val exitCode = process.waitFor()
        if (exitCode != 0 && !blockCompleted.get()) {
            val stderr = stderrLines.joinToString("\n")
            error(
                "[$logPrefix] Process exited with code $exitCode" +
                        if (stderr.isNotBlank()) ": $stderr" else ""
            )
        }
    }
}

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

private fun getLocalIpAddress(): String {
    return try {
        val candidates = mutableListOf<Pair<String, String>>()
        val interfaces = NetworkInterface.getNetworkInterfaces()
        for (iface in interfaces) {
            if (iface.isLoopback || !iface.isUp) continue
            for (addr in iface.inetAddresses) {
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    candidates.add(addr.hostAddress to iface.name)
                }
            }
        }
        candidates
            .filter { !it.first.startsWith("169.254.") }
            .maxByOrNull { it.second == "en0" }
            ?.first
            ?: candidates.firstOrNull()?.first
            ?: "127.0.0.1"
    } catch (_: Exception) {
        "127.0.0.1"
    }
}
