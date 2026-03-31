package com.woutwerkman.connectivitytest.launchers

import java.io.File

/**
 * Launcher for Android emulator.
 * Installs APK and launches the app with connectivity test intent.
 */
class AndroidLauncher(
    private val emulatorId: String,
    private val apkPath: String,
    logger: (String) -> Unit = ::println
) : ProcessLauncher("android-$emulatorId", logger) {

    override fun buildProcess(instanceId: String, platformsString: String): ProcessBuilder {
        // Attempt to uninstall first to avoid signature mismatch (ignore failure if not installed)
        ProcessBuilder(
            "adb", "-s", emulatorId, "uninstall", "com.woutwerkman"
        ).start().waitFor()

        // Install APK (synchronous)
        val installResult = ProcessBuilder(
            "adb", "-s", emulatorId, "install", "-r", apkPath
        ).inheritIO().start().waitFor()

        if (installResult != 0) {
            throw Exception("Failed to install APK on $emulatorId")
        }

        // Clear logcat to avoid old logs
        ProcessBuilder("adb", "-s", emulatorId, "logcat", "-c").start().waitFor()

        // Launch the app
        val launchResult = ProcessBuilder(
            "adb", "-s", emulatorId, "shell",
            "am start -n com.woutwerkman/.MainActivity " +
                    "--ez connectivity_test true " +
                    "--es instanceId $instanceId " +
                    "--es platforms $platformsString"
        ).inheritIO().start().waitFor()

        if (launchResult != 0) {
            throw Exception("Failed to start app on $emulatorId")
        }

        // Return ProcessBuilder for logcat to capture output
        return ProcessBuilder(
            "adb", "-s", emulatorId, "logcat", "ConnectivityTest:I", "PeerNet:I", "*:S"
        ).redirectErrorStream(true)
    }

    override fun isAppStarted(line: String): Boolean {
        return line.contains("Starting connectivity test") ||
                line.contains("PeerNet") && line.contains("Starting peer discovery")
    }

    override fun isSuccess(line: String): Boolean {
        return line.contains("SUCCESS: All platforms connected") ||
                line.contains("SUCCESS: Connectivity test passed")
    }

    override fun isFailure(line: String): String? {
        if (line.contains("FAILURE:")) {
            return line.substringAfter("FAILURE:").trim()
        }
        if (line.contains("Error during test:")) {
            return line.substringAfter("Error during test:").trim()
        }
        return null
    }
}
