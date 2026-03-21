package com.woutwerkman.connectivitytest

import android.app.Activity
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.*

class ConnectivityTestActivity : Activity() {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val TAG = "ConnectivityTest"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val platformsStr = intent.getStringExtra("platforms") ?: "jvm,android-simulator"
        val instanceId = intent.getStringExtra("instanceId") ?: "android-unknown"

        Log.i(TAG, "[$instanceId] Connectivity Test Starting")
        Log.i(TAG, "[$instanceId] Platforms: $platformsStr")

        val targetPlatforms = platformsStr.split(",")
            .mapNotNull { TestPlatform.fromString(it) }
            .toSet()

        if (targetPlatforms.isEmpty()) {
            Log.e(TAG, "[$instanceId] No valid target platforms specified")
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        val config = ConnectivityTestConfig(
            instanceId = instanceId,
            targetPlatforms = targetPlatforms,
            discoveryTimeoutMs = 30_000,
            testTimeoutMs = 60_000
        )

        scope.launch {
            val result = runConnectivityTest(config)

            when (result) {
                is ConnectivityTestResult.Success -> {
                    Log.i(TAG, "[$instanceId] SUCCESS: Connectivity test passed!")
                    setResult(RESULT_OK)
                }
                is ConnectivityTestResult.Failure -> {
                    Log.e(TAG, "[$instanceId] FAILURE: ${result.message}")
                    result.cause?.let { Log.e(TAG, "Cause", it) }
                    setResult(RESULT_CANCELED)
                }
            }
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
