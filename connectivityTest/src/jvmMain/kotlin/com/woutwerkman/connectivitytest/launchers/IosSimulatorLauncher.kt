package com.woutwerkman.connectivitytest.launchers

/**
 * Launcher for iOS Simulator.
 * Launches the connectivity test app on a booted simulator.
 */
class IosSimulatorLauncher(
    private val simulatorUdid: String,
    logger: (String) -> Unit = ::println
) : ProcessLauncher("ios-sim", logger) {

    override fun buildProcess(instanceId: String, platformsString: String): ProcessBuilder {
        return ProcessBuilder(
            "xcrun", "simctl", "launch",
            "--console-pty",
            simulatorUdid,
            "com.woutwerkman.ChippyConnectivityTest",
            "--instance-id", instanceId,
            "--platforms", platformsString
        ).redirectErrorStream(true)
    }

    override fun isAppStarted(line: String): Boolean {
        return line.contains("[iOS-Test] Starting:") ||
                line.contains("PeerNet") && line.contains("Starting peer discovery")
    }

    override fun isSuccess(line: String): Boolean {
        return line.contains("[iOS-Test] SUCCESS!")
    }

    override fun isFailure(line: String): String? {
        if (line.contains("[iOS-Test] FAILED:")) {
            return line.substringAfter("[iOS-Test] FAILED:").trim()
        }
        if (line.contains("[iOS-Test] Timeout")) {
            return "iOS test timed out"
        }
        if (line.contains("[iOS-Test] Error:")) {
            return line.substringAfter("[iOS-Test] Error:").trim()
        }
        return null
    }
}
