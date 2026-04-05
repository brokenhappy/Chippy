package com.woutwerkman.connectivitytest

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket

class ConnectivityTestActivity : ComponentActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val instanceId = intent.getStringExtra("instanceId") ?: "android-1"
        val platformsStr = intent.getStringExtra("platforms") ?: "jvm"
        val controlHost = intent.getStringExtra("controlHost")
            ?: error("controlHost intent extra is required")
        val controlPort = intent.getIntExtra("controlPort", 0)
        require(controlPort > 0) { "controlPort intent extra is required" }

        val targets = platformsStr.split(",")
            .mapNotNull { TestPlatform.fromString(it) }
            .toSet()

        val uiState = MutableStateFlow(
            ConnectivityTestUiState(
                instanceId = instanceId,
                targets = targets.associateWith { false },
            )
        )

        setContent { ConnectivityTestScreen(uiState) }

        Log.i("ConnectivityTest", "[$instanceId] Starting, control=$controlHost:$controlPort")

        scope.launch {
            try {
                val socket = withContext(Dispatchers.IO) { Socket(controlHost, controlPort) }
                val writer = PrintWriter(socket.getOutputStream(), true)
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

                writer.println("HELLO:$instanceId")

                runConnectivityTestProtocol(
                    instanceId = instanceId,
                    targets = targets,
                    uiState = uiState,
                    sendLine = { line ->
                        withContext(Dispatchers.IO) { writer.println(line) }
                        Log.i("ConnectivityTest", "[$instanceId] Sent: $line")
                    },
                    readLine = {
                        withContext(Dispatchers.IO) { reader.readLine() }.also {
                            Log.i("ConnectivityTest", "[$instanceId] Received: $it")
                        }
                    },
                )

                Log.i("ConnectivityTest", "[$instanceId] SUCCESS!")
                setResult(RESULT_OK)
                withContext(Dispatchers.IO) { socket.close() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("ConnectivityTest", "[$instanceId] Error: ${e.message}")
                setResult(RESULT_CANCELED)
            }
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
