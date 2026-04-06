package com.woutwerkman.connectivitytest.launchers

/**
 * Runner for iOS real device.
 * Launches the connectivity test app using devicectl with TCP control channel.
 */
class IosDeviceLauncher(
    private val deviceUdid: String,
    logger: (String) -> Unit = ::println,
) : ProcessRunner("ios-device", logger) {

    override suspend fun buildProcess(
        instanceId: String,
        targets: List<String>,
        controlHost: String,
        controlPort: Int,
    ): ProcessBuilder {
        return ProcessBuilder(
            "xcrun", "devicectl", "device", "process", "launch",
            "--device", deviceUdid,
            "--console",
            "com.woutwerkman.ChippyConnectivityTest",
            "--instance-id", instanceId,
            "--platforms", targets.joinToString(","),
            "--control-host", controlHost,
            "--control-port", controlPort.toString(),
        ).redirectErrorStream(true)
    }
}
