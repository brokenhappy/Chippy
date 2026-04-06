package com.woutwerkman.connectivitytest.launchers

import com.woutwerkman.util.run

/**
 * Runner for Android emulator/device.
 * Installs APK and launches the app with TCP control channel args.
 */
class AndroidLauncher(
    private val emulatorId: String,
    private val apkPath: String,
    logger: (String) -> Unit = ::println,
) : ProcessRunner("android-$emulatorId", logger) {

    override suspend fun buildProcess(
        instanceId: String,
        targets: List<String>,
        controlHost: String,
        controlPort: Int,
    ): ProcessBuilder {
        // Uninstall first to avoid signature mismatch (ignore failure if not installed)
        try {
            ProcessBuilder("adb", "-s", emulatorId, "uninstall", "com.woutwerkman.connectivitytest").run()
        } catch (_: Exception) {}

        // Set up port forwarding for emulator connectivity
        if (emulatorId.startsWith("emulator-")) {
            ProcessBuilder("adb", "-s", emulatorId, "reverse", "tcp:$controlPort", "tcp:$controlPort").run()
            ProcessBuilder("adb", "-s", emulatorId, "reverse", "tcp:47391", "tcp:47391").run()
        }

        // Install APK
        ProcessBuilder("adb", "-s", emulatorId, "install", "-r", apkPath).inheritIO().run()

        // Clear logcat
        ProcessBuilder("adb", "-s", emulatorId, "logcat", "-c").run()

        // For emulators, use 10.0.2.2 (host loopback alias)
        val effectiveControlHost = if (emulatorId.startsWith("emulator-")) "10.0.2.2" else controlHost

        // Launch the app with control channel args as intent extras
        ProcessBuilder(
            "adb", "-s", emulatorId, "shell",
            "am start -n com.woutwerkman.connectivitytest/.ConnectivityTestActivity " +
                    "--es instanceId $instanceId " +
                    "--es platforms ${targets.joinToString(",")} " +
                    "--es controlHost $effectiveControlHost " +
                    "--ei controlPort $controlPort"
        ).inheritIO().run()

        // Return ProcessBuilder for logcat to capture output
        return ProcessBuilder(
            "adb", "-s", emulatorId, "logcat",
            "ConnectivityTest:I", "PeerNet:I", "System.out:I", "*:S"
        ).redirectErrorStream(true)
    }
}
