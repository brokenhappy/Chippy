package com.woutwerkman.connectivitytest

import kotlinx.coroutines.*
import platform.Foundation.NSLog

/**
 * Entry point for iOS connectivity test.
 * Call this from the iOS app when running in test mode.
 */
fun runIosConnectivityTest(
    instanceId: String,
    platforms: String,
    onComplete: (success: Boolean, message: String) -> Unit
) {
    NSLog("[iOS-Test] Starting connectivity test: instanceId=$instanceId, platforms=$platforms")

    val targetPlatforms = platforms.split(",")
        .mapNotNull { TestPlatform.fromString(it.trim()) }
        .toSet()

    if (targetPlatforms.isEmpty()) {
        onComplete(false, "No valid platforms specified: $platforms")
        return
    }

    val config = ConnectivityTestConfig(
        instanceId = instanceId,
        targetPlatforms = targetPlatforms,
        discoveryTimeoutMs = 60_000,
        testTimeoutMs = 90_000
    )

    CoroutineScope(Dispatchers.Main + SupervisorJob()).launch {
        try {
            val result = runConnectivityTest(config)
            when (result) {
                is ConnectivityTestResult.Success -> {
                    NSLog("[iOS-Test] SUCCESS!")
                    onComplete(true, "Connectivity test passed")
                }
                is ConnectivityTestResult.Failure -> {
                    NSLog("[iOS-Test] FAILED: ${result.message}")
                    onComplete(false, result.message)
                }
            }
        } catch (e: Exception) {
            NSLog("[iOS-Test] ERROR: ${e.message}")
            onComplete(false, "Exception: ${e.message}")
        }
    }
}
