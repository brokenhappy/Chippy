package com.woutwerkman.connectivitytest

import com.woutwerkman.net.TestPlatform
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlin.time.Duration

sealed class TestResult {
    data object Success : TestResult()
    data class Failure(val message: String, val cause: Throwable? = null) : TestResult()
}

data class PlatformConfig(
    val type: TestPlatform,
    val instanceId: String,
    val runner: PlatformRunner,
)

interface PlatformRunner {
    suspend fun <T> run(
        instanceId: String,
        targets: List<String>,
        block: suspend (toProcess: SendChannel<String>, fromProcess: ReceiveChannel<String>) -> T,
    ): T
}

enum class DiscoveryTestState { LAUNCHING, READY, DONE }

/**
 * Launches all platforms, waits for them to initialize, gates discovery start,
 * and waits for all platforms to discover their targets.
 *
 * Each platform runner manages its own transport (in-process channels, TCP, etc.).
 * This function only orchestrates the READY → START → DONE protocol.
 */
suspend fun runConnectivityTest(
    platforms: List<PlatformConfig>,
    spinUpTimeout: Duration,
    discoveryTimeout: Duration,
    logger: (String) -> Unit,
): TestResult {
    val testState = MutableStateFlow(platforms.associate { it.instanceId to DiscoveryTestState.LAUNCHING })

    return try {
        coroutineScope {
            for (platform in platforms) {
                launch { runPlatformTestLifecycle(platform, platforms.map { it.type }, testState, logger) }
            }

            withTimeoutOrNull(spinUpTimeout) {
                testState.waitForAllPlatformsToBeReadyForDiscovery()
            } ?: throw spinUpTimeoutError(testState.value)

            logger("All ${platforms.size} platforms ready — sending START")

            withTimeoutOrNull(discoveryTimeout) {
                testState.waitForAllPlatformsToFinishDiscovery()
            } ?: throw discoveryTimeoutError(testState.value)

            logger("SUCCESS: All platforms connected!")
            TestResult.Success
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger("FAILURE: ${e.message}")
        TestResult.Failure("Test failed: ${e.message}", e)
    }
}

private suspend fun runPlatformTestLifecycle(
    platform: PlatformConfig,
    allPlatforms: List<TestPlatform>,
    testState: MutableStateFlow<Map<String, DiscoveryTestState>>,
    logger: (String) -> Unit,
) {
    val targets = discoveryTargetsFor(platform.type, allPlatforms.toSet())

    platform.runner.run(platform.instanceId, targets) { toProcess, fromProcess ->
        waitForReadySignal(fromProcess, platform.instanceId)
        logger("[${platform.instanceId}] READY")
        testState.update { it + (platform.instanceId to DiscoveryTestState.READY) }

        testState.waitForAllPlatformsToBeReadyForDiscovery()
        toProcess.send("START")

        waitForDoneSignal(fromProcess, platform.instanceId, logger)
        logger("[${platform.instanceId}] DONE")
        testState.update { it + (platform.instanceId to DiscoveryTestState.DONE) }
    }
}

private fun discoveryTargetsFor(platformType: TestPlatform, allTypes: Set<TestPlatform>): List<String> {
    val otherTypes = (allTypes - TestPlatform.MAC_BLE_HELPER) - platformType
    return if (otherTypes.isEmpty()) {
        listOf(platformType.toPlatformString())
    } else {
        otherTypes.map { it.toPlatformString() }
    }
}

private suspend fun waitForReadySignal(fromProcess: ReceiveChannel<String>, instanceId: String) {
    for (line in fromProcess) {
        if (line == "READY") return
        if (line.startsWith("ERROR:")) error("[$instanceId] ${line.substringAfter("ERROR:")}")
    }
    error("[$instanceId] Disconnected before sending READY")
}

private suspend fun waitForDoneSignal(
    fromProcess: ReceiveChannel<String>,
    instanceId: String,
    logger: (String) -> Unit,
) {
    for (line in fromProcess) {
        when {
            line.startsWith("FOUND:") -> logger("[$instanceId] $line")
            line == "DONE" -> return
            line.startsWith("ERROR:") -> error("[$instanceId] ${line.substringAfter("ERROR:")}")
        }
    }
    error("[$instanceId] Disconnected before sending DONE")
}

private suspend fun StateFlow<Map<String, DiscoveryTestState>>.waitForAllPlatformsToBeReadyForDiscovery() {
    first { state -> state.values.all { it >= DiscoveryTestState.READY } }
}

private suspend fun StateFlow<Map<String, DiscoveryTestState>>.waitForAllPlatformsToFinishDiscovery() {
    first { state -> state.values.all { it >= DiscoveryTestState.DONE } }
}

private fun spinUpTimeoutError(state: Map<String, DiscoveryTestState>): IllegalStateException {
    val notReady = state.entries.filter { it.value < DiscoveryTestState.READY }.map { it.key }
    return IllegalStateException("Spin-up timeout: platforms not ready: ${notReady.joinToString()}")
}

private fun discoveryTimeoutError(state: Map<String, DiscoveryTestState>): IllegalStateException {
    val notDone = state.entries.filter { it.value < DiscoveryTestState.DONE }.map { it.key }
    return IllegalStateException("Discovery timeout: platforms did not finish: ${notDone.joinToString()}")
}
