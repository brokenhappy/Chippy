package com.woutwerkman.connectivitytest.launchers

import com.woutwerkman.connectivitytest.PlatformLauncher
import com.woutwerkman.net.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Launcher for in-process JVM testing.
 * Runs the connectivity test directly in a coroutine.
 */
class JvmLauncher(
    private val config: ConnectivityTestConfig
) : PlatformLauncher {

    override suspend fun launch(
        instanceId: String,
        platformsString: String,
        onAppStarted: () -> Unit
    ) {
        withContext(Dispatchers.Default) {
            // Signal app started immediately (in-process)
            onAppStarted()

            // Run the connectivity test
            val result = runConnectivityTest(config)

            when (result) {
                is ConnectivityTestResult.Success -> {
                    // Success - return normally
                }
                is ConnectivityTestResult.Failure -> {
                    throw Exception("JVM test failed: ${result.message}", result.cause)
                }
            }
        }
    }
}
