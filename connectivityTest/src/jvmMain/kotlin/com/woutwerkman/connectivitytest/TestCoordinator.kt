package com.woutwerkman.connectivitytest

import com.woutwerkman.net.TestPlatform
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Clean, structured concurrency based test coordinator.
 *
 * Follows the pattern:
 * - Launch all platforms in parallel
 * - Wait for all to signal they've started
 * - Start timeout only after all are running
 * - Cancel everything immediately on any failure
 */
class TestCoordinator(
    private val platforms: List<PlatformConfig>,
    private val discoveryTimeout: Duration = 30.seconds,
    private val logger: (String) -> Unit = ::println
) {

    sealed class TestResult {
        data object Success : TestResult()
        data class Failure(val message: String, val cause: Throwable? = null) : TestResult()
    }

    @OptIn(ExperimentalAtomicApi::class)
    suspend fun run(): TestResult = coroutineScope {
        val outerScope = this
        var timeoutTask: Job? = null
        val appsStartedCount = AtomicInt(platforms.size)

        try {
            // Launch all platform tests in parallel
            coroutineScope {
                platforms.forEach { platform ->
                    launch {
                        // Any exception here will cancel all siblings and propagate up
                        // Each platform targets only the OTHER platform types.
                        // MAC_BLE_HELPER is excluded from network peer targets — it's a
                        // standalone BLE discovery test, not a gossip network participant.
                        val otherTypes = platforms.map { it.type }.toSet() - platform.type - TestPlatform.MAC_BLE_HELPER
                        platform.launcher.launch(
                            instanceId = platform.instanceId,
                            platformsString = otherTypes.joinToString(",") { it.toPlatformString() },
                            onAppStarted = {
                                logger("[${platform.instanceId}] App started")
                                // Start timeout when all apps have started
                                if (appsStartedCount.decrementAndFetch() == 0) {
                                    logger("All apps started, beginning ${discoveryTimeout.inWholeSeconds}s discovery timeout")
                                    timeoutTask = outerScope.launch {
                                        delay(discoveryTimeout)
                                        error("Discovery took longer than $discoveryTimeout")
                                    }
                                }
                            }
                        )
                    }
                }
            }

            logger("SUCCESS: All platforms connected!")
            TestResult.Success
        } catch (e: CancellationException) {
            throw e // Re-throw cancellation
        } catch (e: Exception) {
            logger("FAILURE: ${e.message}")
            TestResult.Failure("Test failed: ${e.message}", e)
        } finally {
            // If we reach here, all platforms completed successfully
            timeoutTask?.cancel()
        }
    }
}

/**
 * Configuration for a platform to test
 */
data class PlatformConfig(
    val type: TestPlatform,
    val instanceId: String,
    val launcher: PlatformLauncher
)

/**
 * Platform launcher abstraction
 */
interface PlatformLauncher {
    /**
     * Launch the platform test.
     *
     * @param instanceId Unique ID for this test instance
     * @param platformsString Comma-separated list of all platforms being tested
     * @param onAppStarted Called when the app has launched and is ready
     *
     * Suspends until the test completes (success or failure).
     * Throws exception on failure to propagate to coordinator.
     */
    suspend fun launch(
        instanceId: String,
        platformsString: String,
        onAppStarted: () -> Unit
    )
}
