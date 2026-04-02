package com.woutwerkman

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.lifecycleScope
import com.woutwerkman.net.*
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Check if we're in connectivity test mode
        val isTestMode = intent.getBooleanExtra("connectivity_test", false)
        val instanceId = intent.getStringExtra("instanceId") ?: "android-1"
        val platformsStr = intent.getStringExtra("platforms") ?: "jvm,android-simulator"

        if (isTestMode) {
            Log.i("ConnectivityTest", "[$instanceId] Starting connectivity test mode")
            runAndroidConnectivityTest(instanceId, platformsStr)
        } else {
            setContent {
                App()
            }
        }
    }

    private fun runAndroidConnectivityTest(instanceId: String, platformsStr: String) {
        val targetPlatforms = platformsStr.split(",")
            .mapNotNull { TestPlatform.fromString(it) }
            .toSet()

        val config = ConnectivityTestConfig(
            instanceId = instanceId,
            targetPlatforms = targetPlatforms
        )

        lifecycleScope.launch {
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
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
