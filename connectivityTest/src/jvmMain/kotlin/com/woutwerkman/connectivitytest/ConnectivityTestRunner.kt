package com.woutwerkman.connectivitytest

import com.woutwerkman.connectivitytest.launchers.AndroidLauncher
import com.woutwerkman.connectivitytest.launchers.IosDeviceLauncher
import com.woutwerkman.connectivitytest.launchers.IosSimulatorLauncher
import com.woutwerkman.connectivitytest.launchers.JvmLauncher
import com.woutwerkman.net.*
import com.woutwerkman.util.run
import com.woutwerkman.util.runAndReadOutput
import com.woutwerkman.util.withProcess
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds

/**
 * Entry point for the structured connectivity test.
 *
 * By default runs ALL platforms and errors if any is unavailable.
 * Use --skip-platform <name> to exclude specific platforms.
 *
 * Platforms: jvm, android-simulator, android-real-device, ios-simulator, ios-real-device
 */
fun main(args: Array<String>) {
    val noHeadless = args.contains("--no-headless")
    val skippedPlatforms = mutableListOf<String>()
    var i = 0
    while (i < args.size) {
        if (args[i] == "--skip-platform" && i + 1 < args.size) {
            skippedPlatforms.addAll(args[i + 1].split(",").map { it.trim().lowercase() })
            i += 2
        } else {
            i++
        }
    }

    println("=".repeat(80))
    println("Chippy Connectivity Test")
    println("=".repeat(80))

    val result = runBlocking {
        runStructuredConnectivityTest(skippedPlatforms.toSet(), showJvmUi = noHeadless)
    }

    when (result) {
        is TestResult.Success -> {
            println("\n" + "=".repeat(80))
            println("SUCCESS: All platforms connected!")
            println("=".repeat(80))
            exitProcess(0)
        }
        is TestResult.Failure -> {
            System.err.println("\n" + "=".repeat(80))
            System.err.println("FAILURE: ${result.message}")
            result.cause?.printStackTrace()
            System.err.println("=".repeat(80))
            exitProcess(1)
        }
    }
}

suspend fun runStructuredConnectivityTest(
    skippedPlatforms: Set<String> = emptySet(),
    showJvmUi: Boolean = false,
): TestResult {
    val availablePlatforms = detectAvailablePlatforms(skippedPlatforms, showJvmUi = showJvmUi)

    println("Testing platforms: ${availablePlatforms.joinToString(", ") { it.type.toString() }}")
    println()

    val hasBle = availablePlatforms.any {
        it.type == TestPlatform.ANDROID_REAL_DEVICE || it.type == TestPlatform.IOS_REAL_DEVICE
    }
    return runConnectivityTest(
        platforms = availablePlatforms,
        spinUpTimeout = 15.seconds,
        // BLE discovery adds overhead: scan (2-5s) + connect + read characteristic (3-5s).
        // LAN-only worst case is ~10s: mDNS propagation (1-2s) + Android fallback poll (5s)
        //   + iOS resolveWithTimeout (5s) + handshake retries (1-3s at 1s intervals).
        // DO NOT change these timeouts without understanding the full discovery chain.
        // Each delay is documented in the PeerNetConnection platform files.
        discoveryTimeout = if (hasBle) 20.seconds else 12.seconds,
        logger = ::println,
    )
}

/**
 * Detect platforms and create configs. All platforms are required by default.
 * Platforms in [skippedPlatforms] are silently excluded.
 * Any non-skipped platform that is unavailable causes an error.
 */
suspend fun detectAvailablePlatforms(skippedPlatforms: Set<String>, showJvmUi: Boolean = false): List<PlatformConfig> {
    val configs = mutableListOf<PlatformConfig>()
    val allPlatforms = listOf("jvm", "android-simulator", "android-real-device", "ios-simulator", "ios-real-device")

    val invalidPlatforms = skippedPlatforms - allPlatforms.toSet()
    require(invalidPlatforms.isEmpty()) {
        "Unknown platform(s): ${invalidPlatforms.joinToString(", ")}. Valid platforms: ${allPlatforms.joinToString(", ")}"
    }

    if (skippedPlatforms.isNotEmpty()) {
        println("Skipping platforms: ${skippedPlatforms.joinToString(", ")}")
    }

    for (platform in allPlatforms) {
        if (platform in skippedPlatforms) continue

        when (platform) {
            "jvm" -> { /* handled after all other platforms are detected */ }

            "android-simulator", "android-real-device" -> {
                val devices = getConnectedAndroidDevices()
                val targetPlatform = TestPlatform.fromString(platform)!!
                val matchingDevices = devices.filter { it.second == targetPlatform }

                if (matchingDevices.isEmpty()) {
                    error("No connected devices for platform: $platform (use --skip-platform $platform to skip)")
                }

                val apkPath = buildAndroidApkOnce()
                matchingDevices.forEachIndexed { i, (deviceId, platformType) ->
                    val instanceId = if (platformType == TestPlatform.ANDROID_SIMULATOR) "android-sim-${i + 1}" else "android-device-${i + 1}"
                    configs.add(
                        PlatformConfig(
                            type = platformType,
                            instanceId = instanceId,
                            runner = AndroidLauncher(deviceId, apkPath),
                        )
                    )
                }
            }

            "ios-simulator" -> {
                val simulators = getBootedIosSimulators()
                if (simulators.isEmpty()) {
                    error("No iOS simulators booted (use --skip-platform ios-simulator to skip)")
                }
                val simulator = simulators.first()
                buildIosSimulatorApp(simulator.first)
                configs.add(
                    PlatformConfig(
                        type = TestPlatform.IOS_SIMULATOR,
                        instanceId = "ios-sim",
                        runner = IosSimulatorLauncher(simulator.first),
                    )
                )
            }

            "ios-real-device" -> {
                val devices = getConnectedIosDevices()
                if (devices.isEmpty()) {
                    error("No iOS devices connected (use --skip-platform ios-real-device to skip)")
                }
                val device = devices.first()
                buildIosDeviceApp(device.first)
                configs.add(
                    PlatformConfig(
                        type = TestPlatform.IOS_REAL_DEVICE,
                        instanceId = "ios-device",
                        runner = IosDeviceLauncher(device.first),
                    )
                )
            }

        }
    }

    // Always add JVM when there are other platforms — it acts as a gossip relay
    if ("jvm" !in skippedPlatforms) {
        val hasOtherPlatforms = configs.isNotEmpty()
        val jvmCount = if (hasOtherPlatforms) 1 else 2
        repeat(jvmCount) { i ->
            configs.add(
                PlatformConfig(
                    type = TestPlatform.JVM,
                    instanceId = "jvm-${i + 1}",
                    runner = JvmLauncher(showUi = showJvmUi),
                )
            )
        }
    }

    return configs
}

// Helper functions for platform detection and building

private var cachedApkPath: String? = null

suspend fun buildAndroidApkOnce(): String {
    cachedApkPath?.let { return it }
    val path = buildAndroidApk()
    cachedApkPath = path
    return path
}

suspend fun getConnectedAndroidDevices(): List<Pair<String, TestPlatform>> {
    return try {
        val output = ProcessBuilder("adb", "devices").runAndReadOutput()
        output.lines()
            .filter { it.isNotBlank() && it.contains("\tdevice") }
            .map { line ->
                val id = line.split("\\s+".toRegex())[0]
                val platform = if (id.startsWith("emulator-")) TestPlatform.ANDROID_SIMULATOR else TestPlatform.ANDROID_REAL_DEVICE
                Pair(id, platform)
            }
    } catch (_: Exception) {
        emptyList()
    }
}

suspend fun getBootedIosSimulators(): List<Pair<String, String>> {
    return try {
        val output = ProcessBuilder("xcrun", "simctl", "list", "devices", "available").runAndReadOutput()
        val simulators = mutableListOf<Pair<String, String>>()
        for (line in output.lines()) {
            if (line.contains("(Booted)") && line.contains("iPhone")) {
                val nameMatch = Regex("^\\s+(.+?)\\s+\\(").find(line)
                val udidMatch = Regex("\\(([0-9A-F-]+)\\)\\s+\\(Booted\\)").find(line)
                if (nameMatch != null && udidMatch != null) {
                    simulators.add(Pair(udidMatch.groupValues[1], nameMatch.groupValues[1]))
                }
            }
        }
        simulators
    } catch (_: Exception) {
        emptyList()
    }
}

suspend fun getConnectedIosDevices(): List<Pair<String, String>> {
    return try {
        val output = ProcessBuilder("xcrun", "devicectl", "list", "devices").runAndReadOutput()
        val devices = mutableListOf<Pair<String, String>>()
        for (line in output.lines()) {
            if (line.contains("connected") && line.contains("iPhone")) {
                val parts = line.split(Regex("\\s+"))
                if (parts.size >= 3) {
                    val name = parts[0]
                    val udid = parts[2]
                    if (udid.matches(Regex("[0-9A-F-]+"))) {
                        devices.add(Pair(udid, name))
                    }
                }
            }
        }
        devices
    } catch (_: Exception) {
        emptyList()
    }
}

suspend fun buildAndroidApk(): String {
    println("Building Android APK...")
    val rootDir = System.getProperty("project.root") ?: "."
    ProcessBuilder(
        "$rootDir/gradlew",
        ":connectivityTestAndroidApp:assembleDebug",
        "--no-configuration-cache",
        "-q"
    ).directory(java.io.File(rootDir)).inheritIO().run()
    return "$rootDir/connectivityTestAndroidApp/build/outputs/apk/debug/connectivityTestAndroidApp-debug.apk"
}

suspend fun buildIosSimulatorApp(udid: String) {
    println("Building iOS simulator app...")
    val rootDir = System.getProperty("project.root") ?: "."

    ProcessBuilder(
        "$rootDir/gradlew",
        ":connectivityTest:linkDebugFrameworkIosSimulatorArm64",
        "--no-configuration-cache",
        "-q"
    ).directory(java.io.File(rootDir)).inheritIO().run()

    ProcessBuilder(
        "xcodebuild",
        "-project", "$rootDir/iosConnectivityTest/iosConnectivityTest.xcodeproj",
        "-scheme", "iosConnectivityTest",
        "-configuration", "Debug",
        "-sdk", "iphonesimulator",
        "-arch", "arm64",
        "-derivedDataPath", "$rootDir/build/ios-test-derived-data",
        "FRAMEWORK_SEARCH_PATHS=$rootDir/connectivityTest/build/bin/iosSimulatorArm64/debugFramework",
        "build"
    ).directory(java.io.File(rootDir)).inheritIO().run()

    ProcessBuilder(
        "xcrun", "simctl", "install",
        udid,
        "$rootDir/build/ios-test-derived-data/Build/Products/Debug-iphonesimulator/iosConnectivityTest.app"
    ).inheritIO().run()
}

suspend fun buildIosDeviceApp(udid: String) {
    println("Building iOS device app...")
    val rootDir = System.getProperty("project.root") ?: "."

    ProcessBuilder(
        "$rootDir/gradlew",
        ":connectivityTest:linkDebugFrameworkIosArm64",
        "--no-configuration-cache",
        "-q"
    ).directory(java.io.File(rootDir)).inheritIO().run()

    val simFrameworkDir = java.io.File("$rootDir/connectivityTest/build/bin/iosSimulatorArm64/debugFramework")
    val simFramework = java.io.File(simFrameworkDir, "ConnectivityTest.framework")
    if (!simFramework.exists()) {
        simFrameworkDir.mkdirs()
        val deviceFramework = java.io.File("$rootDir/connectivityTest/build/bin/iosArm64/debugFramework/ConnectivityTest.framework")
        ProcessBuilder("ln", "-s", deviceFramework.absolutePath, simFramework.absolutePath).run()
    }

    val xcodeDerivedDataApp = java.io.File(System.getProperty("user.home"))
        .resolve("Library/Developer/Xcode/DerivedData")
        .listFiles()
        ?.firstOrNull { it.name.startsWith("iosConnectivityTest") }
        ?.resolve("Build/Products/Debug-iphoneos/iosConnectivityTest.app")

    val appPath = if (xcodeDerivedDataApp?.exists() == true) {
        println("Using existing Xcode build from: ${xcodeDerivedDataApp.absolutePath}")
        xcodeDerivedDataApp.absolutePath
    } else {
        withTimeoutOrNull(120.seconds) {
            ProcessBuilder(
                "xcodebuild",
                "-project", "$rootDir/iosConnectivityTest/iosConnectivityTest.xcodeproj",
                "-scheme", "iosConnectivityTest",
                "-configuration", "Debug",
                "-sdk", "iphoneos",
                "-arch", "arm64",
                "-derivedDataPath", "$rootDir/build/ios-test-derived-data",
                "-allowProvisioningUpdates",
                "FRAMEWORK_SEARCH_PATHS=$rootDir/connectivityTest/build/bin/iosArm64/debugFramework",
                "build"
            ).directory(java.io.File(rootDir)).inheritIO().run()
        } ?: throw IllegalStateException(
            "iOS device build timed out after 120 seconds — you may need to build once in Xcode first to approve code signing",
        )

        "$rootDir/build/ios-test-derived-data/Build/Products/Debug-iphoneos/iosConnectivityTest.app"
    }

    ProcessBuilder(
        "xcrun", "devicectl", "device", "install", "app",
        "--device", udid,
        appPath
    ).inheritIO().run()
}
