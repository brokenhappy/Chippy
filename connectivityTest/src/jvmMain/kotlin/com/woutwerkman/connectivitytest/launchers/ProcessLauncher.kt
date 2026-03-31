package com.woutwerkman.connectivitytest.launchers

import com.woutwerkman.connectivitytest.PlatformLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import java.io.BufferedReader

/**
 * Base class for launchers that spawn external processes.
 * Handles process lifecycle, output streaming, and error detection.
 */
abstract class ProcessLauncher(
    private val logPrefix: String,
    private val logger: (String) -> Unit = ::println
) : PlatformLauncher {

    /**
     * Build the ProcessBuilder for this platform.
     */
    protected abstract fun buildProcess(instanceId: String, platformsString: String): ProcessBuilder

    /**
     * Detect when the app has started from log output.
     * Return true when the app is ready.
     */
    protected abstract fun isAppStarted(line: String): Boolean

    /**
     * Detect success from log output.
     * Return true when the test has passed.
     */
    protected abstract fun isSuccess(line: String): Boolean

    /**
     * Detect failure from log output.
     * Return error message if failure detected, null otherwise.
     */
    protected abstract fun isFailure(line: String): String?

    override suspend fun launch(
        instanceId: String,
        platformsString: String,
        onAppStarted: () -> Unit
    ) = coroutineScope {
        val processBuilder = buildProcess(instanceId, platformsString)
        val process = withContext(Dispatchers.IO) {
            processBuilder.start()
        }

        val completionChannel = Channel<Result<Unit>>(1)
        var appStartedSignaled = false

        // Stream output and detect status
        launch(Dispatchers.IO) {
            try {
                process.inputStream.bufferedReader().use { reader ->
                    reader.lineSequence().forEach { line ->
                        logger("[$logPrefix] $line")

                        // Check for app started
                        if (!appStartedSignaled && isAppStarted(line)) {
                            appStartedSignaled = true
                            onAppStarted()
                        }

                        // Check for success
                        if (isSuccess(line)) {
                            completionChannel.trySend(Result.success(Unit))
                            return@forEach
                        }

                        // Check for failure
                        isFailure(line)?.let { error ->
                            completionChannel.trySend(Result.failure(Exception(error)))
                            return@forEach
                        }
                    }
                }

                // Process ended without success/failure markers
                val exitCode = process.waitFor()
                if (exitCode == 0) {
                    completionChannel.trySend(Result.success(Unit))
                } else {
                    completionChannel.trySend(Result.failure(Exception("Process exited with code $exitCode")))
                }
            } catch (e: Exception) {
                completionChannel.trySend(Result.failure(e))
            }
        }

        // Wait for completion
        val result = completionChannel.receive()
        process.destroy()

        result.getOrThrow()
    }
}
