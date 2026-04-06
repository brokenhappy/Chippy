package com.woutwerkman.connectivitytest.launchers

/**
 * Runner for iOS Simulator.
 * Launches the connectivity test app on a booted simulator with TCP control channel.
 */
class IosSimulatorLauncher(
    private val simulatorUdid: String,
    logger: (String) -> Unit = ::println,
) : ProcessRunner("ios-sim", logger) {

    override suspend fun buildProcess(
        instanceId: String,
        targets: List<String>,
        controlHost: String,
        controlPort: Int,
    ): ProcessBuilder {
        return ProcessBuilder(
            "xcrun", "simctl", "launch",
            "--console-pty",
            simulatorUdid,
            "com.woutwerkman.ChippyConnectivityTest",
            "--instance-id", instanceId,
            "--platforms", targets.joinToString(","),
            "--control-host", controlHost,
            "--control-port", controlPort.toString(),
        ).redirectErrorStream(true)
    }
}
