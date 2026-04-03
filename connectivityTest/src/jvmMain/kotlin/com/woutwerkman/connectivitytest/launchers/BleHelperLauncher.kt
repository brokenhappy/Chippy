package com.woutwerkman.connectivitytest.launchers

import com.woutwerkman.connectivitytest.PlatformLauncher
import kotlinx.coroutines.*
import java.io.File

/**
 * Launcher for the Mac BLE test helper app bundle.
 *
 * The BLE helper must run as a macOS app bundle (not a plain binary) to get
 * Bluetooth TCC authorization. It writes output to a log file that we tail.
 */
class BleHelperLauncher(
    private val appBundlePath: String,
    private val logger: (String) -> Unit = ::println,
) : PlatformLauncher {

    override suspend fun launch(
        instanceId: String,
        platformsString: String,
        onAppStarted: () -> Unit,
    ) = coroutineScope {
        val logFile = File("/tmp/ble-test-helper.log")
        logFile.delete()

        // Launch the app bundle via `open`
        val process = withContext(Dispatchers.IO) {
            ProcessBuilder(
                "open", "-W", appBundlePath,
                "--args", instanceId, "60"
            ).redirectErrorStream(true).start()
        }

        var appStartedSignaled = false
        var result: Result<Unit>? = null

        // Tail the log file
        val tailJob = launch(Dispatchers.IO) {
            // Wait for log file to appear
            var attempts = 0
            while (!logFile.exists() && attempts < 100) {
                delay(100)
                attempts++
            }
            if (!logFile.exists()) {
                result = Result.failure(Exception("BLE helper log file never appeared"))
                return@launch
            }

            var offset = 0L
            while (isActive) {
                val length = logFile.length()
                if (length > offset) {
                    val content = logFile.readText()
                    val newContent = content.substring(offset.toInt().coerceAtMost(content.length))
                    offset = length

                    for (line in newContent.lines()) {
                        if (line.isBlank()) continue
                        logger("[ble-helper] $line")

                        if (!appStartedSignaled && line.contains("[BLE-Helper] App started")) {
                            appStartedSignaled = true
                            onAppStarted()
                        }

                        if (line.contains("SUCCESS:")) {
                            result = Result.success(Unit)
                            return@launch
                        }

                        if (line.contains("TIMEOUT:") || line.contains("FAILED:")) {
                            val msg = line.substringAfter("[BLE-Helper] ").trim()
                            result = Result.failure(Exception(msg))
                            return@launch
                        }
                    }
                }
                delay(200)
            }
        }

        // Wait for result from log tailing or process exit
        val processJob = launch(Dispatchers.IO) {
            process.waitFor()
        }

        // Wait for either the log tailer to find a result or the process to exit
        while (result == null && processJob.isActive) {
            delay(200)
        }

        tailJob.cancel()
        processJob.cancel()
        process.destroy()

        val finalResult = result ?: Result.failure(Exception("BLE helper exited without result"))
        finalResult.getOrThrow()
    }
}
