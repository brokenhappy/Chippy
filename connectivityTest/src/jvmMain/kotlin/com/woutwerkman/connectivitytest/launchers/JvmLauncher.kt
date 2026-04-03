package com.woutwerkman.connectivitytest.launchers

import com.woutwerkman.connectivitytest.PlatformRunner
import com.woutwerkman.net.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.first
import java.net.Socket

/**
 * In-process JVM runner. Uses direct Kotlin channels (no TCP).
 * Runs the connectivity test in a coroutine within the same process.
 */
class JvmLauncher(
    private val logger: (String) -> Unit = ::println,
) : PlatformRunner {

    override suspend fun <T> run(
        instanceId: String,
        targets: List<String>,
        controlHost: String,
        controlPort: Int,
        socketDeferred: CompletableDeferred<Socket>?,
        block: suspend (toProcess: SendChannel<String>, fromProcess: ReceiveChannel<String>) -> T,
    ): T = coroutineScope {
        val toProcess = Channel<String>(Channel.BUFFERED)
        val fromProcess = Channel<String>(Channel.BUFFERED)

        launch {
            try {
                withPeerNetConnection(
                    PeerNetConfig(serviceName = "chippy-test", displayName = instanceId)
                ) { conn ->
                    fromProcess.send("READY")

                    // Wait for START
                    for (cmd in toProcess) {
                        if (cmd == "START") break
                    }

                    // Monitor state and report discovered platforms
                    val targetSet = targets.mapNotNull { TestPlatform.fromString(it) }.toSet()
                    val found = mutableSetOf<TestPlatform>()

                    conn.state.first { state ->
                        val matched = matchedPlatforms(state, instanceId, targetSet)
                        for (p in matched - found) {
                            found.add(p)
                            fromProcess.send("FOUND:${p.toPlatformString()}")
                            logger("[jvm/$instanceId] Found ${p.toPlatformString()}")
                        }
                        found.containsAll(targetSet)
                    }

                    fromProcess.send("DONE")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fromProcess.send("ERROR:${e.message}")
            }
        }

        block(toProcess, fromProcess)
    }
}

/**
 * Match discovered peers against target platforms.
 */
internal fun matchedPlatforms(
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
