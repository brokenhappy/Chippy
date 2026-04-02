package com.woutwerkman.connectivitytest

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.woutwerkman.net.*
import kotlinx.coroutines.*

class ConnectivityTestActivity : Activity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val instanceId = intent.getStringExtra("instanceId") ?: "android-1"
        val platformsStr = intent.getStringExtra("platforms") ?: "jvm,android-simulator"

        Log.i("ConnectivityTest", "[$instanceId] Starting connectivity test mode")

        val targetPlatforms = platformsStr.split(",")
            .mapNotNull { TestPlatform.fromString(it) }
            .toSet()

        val config = ConnectivityTestConfig(
            instanceId = instanceId,
            targetPlatforms = targetPlatforms
        )

        scope.launch {
            val result = runConnectivityTest(config)
            when (result) {
                is ConnectivityTestResult.Success -> {
                    Log.i("ConnectivityTest", "[$instanceId] SUCCESS: Connectivity test passed!")
                    setResult(RESULT_OK)
                }
                is ConnectivityTestResult.Failure -> {
                    Log.e("ConnectivityTest", "[$instanceId] FAILURE: ${result.message}")
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
