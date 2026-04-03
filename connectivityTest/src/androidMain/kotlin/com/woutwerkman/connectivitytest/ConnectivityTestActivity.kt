package com.woutwerkman.connectivitytest

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.woutwerkman.net.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket

class ConnectivityTestActivity : Activity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val instanceId = intent.getStringExtra("instanceId") ?: "android-1"
        val platformsStr = intent.getStringExtra("platforms") ?: "jvm,android-simulator"
        val controlHost = intent.getStringExtra("controlHost")
            ?: error("controlHost intent extra is required")
        val controlPort = intent.getIntExtra("controlPort", 0)
        require(controlPort > 0) { "controlPort intent extra is required" }

        Log.i("ConnectivityTest", "[$instanceId] Starting, control=$controlHost:$controlPort")

        val targets = platformsStr.split(",")
            .mapNotNull { TestPlatform.fromString(it) }
            .toSet()

        scope.launch {
            try {
                val socket = withContext(Dispatchers.IO) {
                    Socket(controlHost, controlPort)
                }
                val writer = PrintWriter(socket.getOutputStream(), true)
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

                try {
                    writer.println("HELLO:$instanceId")

                    withPeerNetConnection(
                        PeerNetConfig(serviceName = "chippy-test", displayName = instanceId)
                    ) { conn ->
                        writer.println("READY")
                        Log.i("ConnectivityTest", "[$instanceId] Sent READY")

                        val startLine = withContext(Dispatchers.IO) { reader.readLine() }
                        if (startLine != "START") {
                            writer.println("ERROR:Expected START, got: $startLine")
                            return@withPeerNetConnection
                        }
                        Log.i("ConnectivityTest", "[$instanceId] Received START")

                        val found = mutableSetOf<TestPlatform>()
                        conn.state.first { state ->
                            val matched = matchedPlatforms(state, instanceId, targets)
                            for (p in matched - found) {
                                found.add(p)
                                writer.println("FOUND:${p.toPlatformString()}")
                                Log.i("ConnectivityTest", "[$instanceId] Found ${p.toPlatformString()}")
                            }
                            found.containsAll(targets)
                        }

                        writer.println("DONE")
                        Log.i("ConnectivityTest", "[$instanceId] SUCCESS!")
                        setResult(RESULT_OK)
                    }
                } finally {
                    withContext(Dispatchers.IO + NonCancellable) {
                        socket.close()
                    }
                }
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

private fun matchedPlatforms(
    state: PeerNetState,
    instanceId: String,
    targets: Set<TestPlatform>,
): Set<TestPlatform> {
    val matched = mutableSetOf<TestPlatform>()
    for ((peerId, peer) in state.discoveredPeers) {
        if (peer.name == instanceId) continue
        val platform = TestPlatform.fromPeerId(peerId) ?: continue
        val normalized = normalizePlatform(platform, targets)
        if (normalized != null) matched.add(normalized)
    }
    return matched
}

private fun normalizePlatform(platform: TestPlatform, targets: Set<TestPlatform>): TestPlatform? {
    if (platform in targets) return platform
    if (platform == TestPlatform.IOS_REAL_DEVICE || platform == TestPlatform.IOS_SIMULATOR) {
        if (TestPlatform.IOS_REAL_DEVICE in targets) return TestPlatform.IOS_REAL_DEVICE
        if (TestPlatform.IOS_SIMULATOR in targets) return TestPlatform.IOS_SIMULATOR
    }
    if (platform == TestPlatform.ANDROID_REAL_DEVICE || platform == TestPlatform.ANDROID_SIMULATOR) {
        if (TestPlatform.ANDROID_REAL_DEVICE in targets) return TestPlatform.ANDROID_REAL_DEVICE
        if (TestPlatform.ANDROID_SIMULATOR in targets) return TestPlatform.ANDROID_SIMULATOR
    }
    return null
}
