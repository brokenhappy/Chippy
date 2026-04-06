package com.woutwerkman.connectivitytest.launchers

/**
 * Runner for the Mac BLE test helper app bundle.
 *
 * The BLE helper must run as a macOS app bundle (not a plain binary) to get
 * Bluetooth TCC authorization. It connects to the coordinator's TCP control
 * server for bidirectional protocol messages.
 */
class BleHelperLauncher(
    private val appBundlePath: String,
    logger: (String) -> Unit = ::println,
) : ProcessRunner("ble-helper", logger) {

    override suspend fun buildProcess(
        instanceId: String,
        targets: List<String>,
        controlHost: String,
        controlPort: Int,
    ): ProcessBuilder {
        return ProcessBuilder(
            "open", "-n", "-W", appBundlePath,
            "--args", instanceId,
            "--control-host", controlHost,
            "--control-port", controlPort.toString(),
        ).redirectErrorStream(true)
    }
}
