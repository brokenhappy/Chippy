package com.woutwerkman.connectivitytest.launchers

import com.woutwerkman.connectivitytest.ExternalProcessRunner
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import java.net.Socket

/**
 * Base class for runners that spawn external processes with TCP control channels.
 *
 * The process sends HELLO:<instanceId> as its first TCP message. The coordinator's
 * accept loop routes the socket to this runner via [socketDeferred].
 * Subsequent protocol messages (READY, START, FOUND, DONE, ERROR) flow over TCP.
 * Process stdout/stderr is captured for logging only.
 */
abstract class ProcessRunner(
    private val logPrefix: String,
    private val logger: (String) -> Unit = ::println,
) : ExternalProcessRunner {

    /**
     * Build the ProcessBuilder for this platform.
     * Must include --control-host and --control-port args.
     */
    protected abstract fun buildProcess(
        instanceId: String,
        targets: List<String>,
        controlHost: String,
        controlPort: Int,
    ): ProcessBuilder

    override suspend fun <T> run(
        instanceId: String,
        targets: List<String>,
        controlHost: String,
        controlPort: Int,
        socketDeferred: CompletableDeferred<Socket>?,
        block: suspend (toProcess: SendChannel<String>, fromProcess: ReceiveChannel<String>) -> T,
    ): T = coroutineScope {
        val process = withContext(Dispatchers.IO) {
            buildProcess(instanceId, targets, controlHost, controlPort).start()
        }

        // Log process stdout (informational only — protocol is over TCP)
        val stdoutJob = launch(Dispatchers.IO) {
            try {
                process.inputStream.bufferedReader().use { reader ->
                    reader.lineSequence().forEach { line ->
                        logger("[$logPrefix] $line")
                    }
                }
            } catch (_: Exception) { }
        }

        // Capture stderr for error reporting
        val stderrLines = mutableListOf<String>()
        val stderrJob = launch(Dispatchers.IO) {
            try {
                process.errorStream.bufferedReader().use { reader ->
                    reader.lineSequence().forEach { line ->
                        stderrLines.add(line)
                        logger("[$logPrefix/err] $line")
                    }
                }
            } catch (_: Exception) { }
        }

        // Wait for the coordinator's accept loop to route our socket
        val socket: Socket = socketDeferred?.await()
            ?: error("[$logPrefix] No socket deferred — ProcessRunner requires TCP connection")

        val toProcess = Channel<String>(Channel.BUFFERED)
        val fromProcess = Channel<String>(Channel.BUFFERED)

        // TCP reader → fromProcess channel
        // Note: the HELLO line was already consumed by the coordinator's accept loop.
        val readerJob = launch(Dispatchers.IO) {
            try {
                socket.getInputStream().bufferedReader().use { reader ->
                    reader.lineSequence().forEach { line ->
                        fromProcess.send(line)
                    }
                }
            } catch (_: Exception) { }
            fromProcess.close()
        }

        // toProcess channel → TCP writer
        val writerJob = launch(Dispatchers.IO) {
            try {
                socket.getOutputStream().bufferedWriter().use { writer ->
                    for (cmd in toProcess) {
                        writer.write(cmd)
                        writer.newLine()
                        writer.flush()
                    }
                }
            } catch (_: Exception) { }
        }

        // Monitor for premature process exit — if the process crashes before
        // completing the protocol, the error propagates up and cancels all siblings.
        // Once the block completes successfully, we suppress exit code errors because
        // some tools (e.g. devicectl --console) exit with 1 even on clean shutdown.
        val blockCompleted = java.util.concurrent.atomic.AtomicBoolean(false)
        val processMonitor = launch(Dispatchers.IO) {
            val exitCode = process.waitFor()
            if (exitCode != 0 && !blockCompleted.get()) {
                val stderr = stderrLines.joinToString("\n")
                error(
                    "[$logPrefix] Process exited with code $exitCode" +
                            if (stderr.isNotBlank()) ": $stderr" else ""
                )
            }
        }

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
                withContext(Dispatchers.IO) {
                    socket.close()
                    process.destroy()
                    val exited = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                    if (!exited) process.destroyForcibly()
                }
            }
        }
    }
}
