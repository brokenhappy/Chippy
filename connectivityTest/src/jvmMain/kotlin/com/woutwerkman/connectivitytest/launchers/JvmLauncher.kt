package com.woutwerkman.connectivitytest.launchers

import com.woutwerkman.connectivitytest.*
import com.woutwerkman.net.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * In-process JVM runner. Uses direct Kotlin channels (no TCP).
 * Runs the connectivity test in a coroutine within the same process.
 */
class JvmLauncher(
    private val showUi: Boolean = false,
    private val logger: (String) -> Unit = ::println,
) : PlatformRunner {

    override suspend fun <T> run(
        instanceId: String,
        targets: List<String>,
        block: suspend (toProcess: SendChannel<String>, fromProcess: ReceiveChannel<String>) -> T,
    ): T = coroutineScope {
        val toProcess = Channel<String>(Channel.BUFFERED)
        val fromProcess = Channel<String>(Channel.BUFFERED)

        val targetSet = targets.mapNotNull { TestPlatform.fromString(it) }.toSet()

        val uiState = MutableStateFlow(
            ConnectivityTestUiState(
                instanceId = instanceId,
                targets = targetSet.associateWith { false },
            )
        )

        if (showUi) {
            launch(Dispatchers.Default) { showConnectivityTestWindow(uiState) }
        }

        launch {
            try {
                runConnectivityTestProtocol(
                    instanceId = instanceId,
                    targets = targetSet,
                    uiState = uiState,
                    sendLine = { line ->
                        fromProcess.send(line)
                        logger("[jvm/$instanceId] Sent: $line")
                    },
                    readLine = {
                        toProcess.receive().also {
                            logger("[jvm/$instanceId] Received: $it")
                        }
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fromProcess.send("ERROR:${e.message}")
            }
        }

        block(toProcess, fromProcess)
    }
}
