package com.woutwerkman.connectivitytest.launchers

/**
 * Runner for Android emulator/device.
 * Installs APK and launches the app with TCP control channel args.
 */
class AndroidLauncher(
    private val emulatorId: String,
    private val apkPath: String,
    logger: (String) -> Unit = ::println,
) : ProcessRunner("android-$emulatorId", logger) {

    override fun buildProcess(
        instanceId: String,
        targets: List<String>,
        controlHost: String,
        controlPort: Int,
    ): ProcessBuilder {
        // Uninstall first to avoid signature mismatch (ignore failure if not installed)
        ProcessBuilder("adb", "-s", emulatorId, "uninstall", "com.woutwerkman.connectivitytest")
            .start().waitFor()

        // Set up port forwarding for emulator connectivity
        if (emulatorId.startsWith("emulator-")) {
            ProcessBuilder(
                "adb", "-s", emulatorId, "reverse", "tcp:$controlPort", "tcp:$controlPort"
            ).start().waitFor()
            ProcessBuilder(
                "adb", "-s", emulatorId, "reverse", "tcp:47391", "tcp:47391"
            ).start().waitFor()
        }

        // Install APK
        val installResult = ProcessBuilder(
            "adb", "-s", emulatorId, "install", "-r", apkPath
        ).inheritIO().start().waitFor()

        if (installResult != 0) {
            throw Exception("Failed to install APK on $emulatorId")
        }

        // Clear logcat
        ProcessBuilder("adb", "-s", emulatorId, "logcat", "-c").start().waitFor()

        // For emulators, use 10.0.2.2 (host loopback alias)
        val effectiveControlHost = if (emulatorId.startsWith("emulator-")) "10.0.2.2" else controlHost

        // Launch the app with control channel args as intent extras
        val launchResult = ProcessBuilder(
            "adb", "-s", emulatorId, "shell",
            "am start -n com.woutwerkman.connectivitytest/.ConnectivityTestActivity " +
                    "--es instanceId $instanceId " +
                    "--es platforms ${targets.joinToString(",")} " +
                    "--es controlHost $effectiveControlHost " +
                    "--ei controlPort $controlPort"
        ).inheritIO().start().waitFor()

        if (launchResult != 0) {
            throw Exception("Failed to start app on $emulatorId")
        }

        // Return ProcessBuilder for logcat to capture output
        return ProcessBuilder(
            "adb", "-s", emulatorId, "logcat",
            "ConnectivityTest:I", "PeerNet:I", "System.out:I", "*:S"
        ).redirectErrorStream(true)
    }
}
