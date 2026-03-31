package com.woutwerkman.connectivitytest

import com.woutwerkman.connectivitytest.launchers.*
import com.woutwerkman.net.*
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

/**
 * Entry point for the new structured connectivity test.
 * Usage: java -cp ... com.woutwerkman.connectivitytest.ConnectivityTestRunnerKt [platforms]
 *
 * Platforms can be: jvm, android-simulator, ios-simulator, ios-real-device
 * If no platforms specified, auto-detects available platforms.
 */
fun main(args: Array<String>) {
    val requestedPlatforms = if (args.isEmpty()) {
        emptyList()
    } else {
        args[0].split(",").map { it.trim() }
    }

    println("=".repeat(80))
    println("Chippy Connectivity Test")
    println("=".repeat(80))

    val result = runBlocking {
        runStructuredConnectivityTest(requestedPlatforms)
    }

    when (result) {
        is TestCoordinator.TestResult.Success -> {
            println("\n" + "=".repeat(80))
            println("✓ SUCCESS: All platforms connected!")
            println("=".repeat(80))
            exitProcess(0)
        }
        is TestCoordinator.TestResult.Failure -> {
            System.err.println("\n" + "=".repeat(80))
            System.err.println("✗ FAILURE: ${result.message}")
            result.cause?.printStackTrace()
            System.err.println("=".repeat(80))
            exitProcess(1)
        }
    }
}

suspend fun runStructuredConnectivityTest(
    requestedPlatforms: List<String>
): TestCoordinator.TestResult {
    // Detect available platforms
    val availablePlatforms = detectAvailablePlatforms(requestedPlatforms)

    if (availablePlatforms.isEmpty()) {
        return TestCoordinator.TestResult.Failure("No platforms available for testing")
    }

    println("Testing platforms: ${availablePlatforms.joinToString(", ") { it.type.toString() }}")
    println()

    // Create coordinator
    val coordinator = TestCoordinator(
        platforms = availablePlatforms,
        logger = ::println
    )

    return coordinator.run()
}

/**
 * Detect which platforms are available and create configs for them.
 *
 * Detects non-JVM platforms first, then adds JVM targeting only the
 * platforms that were actually found (not all possible platforms).
 */
fun detectAvailablePlatforms(requested: List<String>): List<PlatformConfig> {
    val configs = mutableListOf<PlatformConfig>()
    val platformsToCheck = if (requested.isEmpty()) {
        listOf("jvm", "android-simulator", "android-real-device", "ios-simulator", "ios-real-device")
    } else {
        requested
    }

    val includeJvm = platformsToCheck.any { it.lowercase() == "jvm" }

    // Detect non-JVM platforms first so we know what JVM should target
    for (platform in platformsToCheck) {
        when (platform.lowercase()) {
            "jvm" -> { /* handled after all other platforms are detected */ }

            "android-simulator", "android-real-device" -> {
                val devices = getConnectedAndroidDevices()
                val targetPlatform = TestPlatform.fromString(platform) ?: continue

                val matchingDevices = devices.filter { it.second == targetPlatform }

                if (matchingDevices.isEmpty() && requested.contains(platform)) {
                    throw IllegalStateException("No connected devices for platform: $platform")
                }

                if (matchingDevices.isNotEmpty()) {
                    val apkPath = buildAndroidApkOnce()
                    matchingDevices.forEachIndexed { i, (deviceId, platformType) ->
                        val instanceId = if (platformType == TestPlatform.ANDROID_SIMULATOR) "android-sim-${i + 1}" else "android-device-${i + 1}"
                        configs.add(
                            PlatformConfig(
                                type = platformType,
                                instanceId = instanceId,
                                launcher = AndroidLauncher(deviceId, apkPath)
                            )
                        )
                    }
                }
            }

            "ios-simulator" -> {
                val simulators = getBootedIosSimulators()
                if (simulators.isEmpty() && requested.contains("ios-simulator")) {
                    throw IllegalStateException("No iOS simulators booted")
                }
                if (simulators.isNotEmpty()) {
                    val simulator = simulators.first()
                    buildIosSimulatorApp(simulator.first) // Throws on failure
                    configs.add(
                        PlatformConfig(
                            type = TestPlatform.IOS_SIMULATOR,
                            instanceId = "ios-sim",
                            launcher = IosSimulatorLauncher(simulator.first)
                        )
                    )
                }
            }

            "ios-real-device" -> {
                val devices = getConnectedIosDevices()
                if (devices.isEmpty() && requested.contains("ios-real-device")) {
                    throw IllegalStateException("No iOS devices connected")
                }
                if (devices.isNotEmpty()) {
                    val device = devices.first()
                    buildIosDeviceApp(device.first) // Throws on failure
                    configs.add(
                        PlatformConfig(
                            type = TestPlatform.IOS_REAL_DEVICE,
                            instanceId = "ios-device",
                            launcher = IosDeviceLauncher(device.first)
                        )
                    )
                }
            }
        }
    }

    // Always add JVM when there are other platforms — it acts as a gossip relay
    // for peers on isolated networks (e.g., emulator on 10.0.2.x ↔ iPhone on WiFi).
    // The linearization engine re-broadcasts events, so all peers converge.
    if (configs.none { it.type == TestPlatform.JVM }) {
        val detectedNonJvmTypes = configs.map { it.type }.toSet()
        val hasOtherPlatforms = detectedNonJvmTypes.isNotEmpty()
        val jvmCount = if (hasOtherPlatforms) 1 else 2
        val jvmTargets = if (hasOtherPlatforms) {
            detectedNonJvmTypes
        } else {
            setOf(TestPlatform.JVM)
        }
        repeat(jvmCount) { i ->
            configs.add(
                PlatformConfig(
                    type = TestPlatform.JVM,
                    instanceId = "jvm-${i + 1}",
                    launcher = JvmLauncher(
                        ConnectivityTestConfig(
                            instanceId = "jvm-${i + 1}",
                            targetPlatforms = jvmTargets
                        )
                    )
                )
            )
        }
    }

    return configs
}

// Helper functions for platform detection and building
// These will call the existing functions from build.gradle.kts

private var cachedApkPath: String? = null

fun buildAndroidApkOnce(): String {
    cachedApkPath?.let { return it }
    val path = buildAndroidApk()
    cachedApkPath = path
    return path
}

fun getConnectedAndroidDevices(): List<Pair<String, TestPlatform>> {
    return try {
        val process = ProcessBuilder("adb", "devices").start()
        val output = process.inputStream.bufferedReader().readText()
        output.lines()
            .filter { it.isNotBlank() && it.contains("\tdevice") }
            .map { line ->
                val id = line.split("\\s+".toRegex())[0]
                val platform = if (id.startsWith("emulator-")) TestPlatform.ANDROID_SIMULATOR else TestPlatform.ANDROID_REAL_DEVICE
                Pair(id, platform)
            }
    } catch (e: Exception) {
        emptyList()
    }
}

fun getBootedIosSimulators(): List<Pair<String, String>> {
    return try {
        val process = ProcessBuilder("xcrun", "simctl", "list", "devices", "available").start()
        val output = process.inputStream.bufferedReader().readText()
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
    } catch (e: Exception) {
        emptyList()
    }
}

fun getConnectedIosDevices(): List<Pair<String, String>> {
    return try {
        val process = ProcessBuilder("xcrun", "devicectl", "list", "devices").start()
        val output = process.inputStream.bufferedReader().readText()
        val devices = mutableListOf<Pair<String, String>>()
        for (line in output.lines()) {
            // Format: "iPhone               iPhone.coredevice.local             866A6B20-95FD-558C-AD2F-8E276942D936   connected            iPhone 13 Pro"
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
    } catch (e: Exception) {
        emptyList()
    }
}

fun buildAndroidApk(): String {
    println("Building Android APK...")
    val rootDir = System.getProperty("project.root") ?: "."
    val process = ProcessBuilder(
        "$rootDir/gradlew",
        ":composeApp:assembleDebug",
        "--no-configuration-cache",
        "-q"
    ).directory(java.io.File(rootDir)).inheritIO().start()

    if (process.waitFor() != 0) {
        throw IllegalStateException("Failed to build Android APK")
    }
    return "$rootDir/composeApp/build/outputs/apk/debug/composeApp-debug.apk"
}

fun buildIosSimulatorApp(udid: String) {
    println("Building iOS simulator app...")
    val rootDir = System.getProperty("project.root") ?: "."

    // Build Kotlin framework
    var process = ProcessBuilder(
        "$rootDir/gradlew",
        ":composeApp:linkDebugFrameworkIosSimulatorArm64",
        "--no-configuration-cache",
        "-q"
    ).directory(java.io.File(rootDir)).inheritIO().start()

    if (process.waitFor() != 0) {
        throw IllegalStateException("Failed to build Kotlin framework for iOS simulator")
    }

    // Build Xcode project
    process = ProcessBuilder(
        "xcodebuild",
        "-project", "$rootDir/iosConnectivityTest/iosConnectivityTest.xcodeproj",
        "-scheme", "iosConnectivityTest",
        "-configuration", "Debug",
        "-sdk", "iphonesimulator",
        "-arch", "arm64",
        "-derivedDataPath", "$rootDir/build/ios-test-derived-data",
        "FRAMEWORK_SEARCH_PATHS=$rootDir/composeApp/build/bin/iosSimulatorArm64/debugFramework",
        "build"
    ).directory(java.io.File(rootDir)).inheritIO().start()

    if (process.waitFor() != 0) {
        throw IllegalStateException("Failed to build iOS simulator app with Xcode")
    }

    // Install on simulator
    process = ProcessBuilder(
        "xcrun", "simctl", "install",
        udid,
        "$rootDir/build/ios-test-derived-data/Build/Products/Debug-iphonesimulator/iosConnectivityTest.app"
    ).inheritIO().start()

    if (process.waitFor() != 0) {
        throw IllegalStateException("Failed to install iOS simulator app")
    }
}

fun buildIosDeviceApp(udid: String) {
    println("Building iOS device app...")
    val rootDir = System.getProperty("project.root") ?: "."

    // Build Kotlin framework for iOS device first
    var process = ProcessBuilder(
        "$rootDir/gradlew",
        ":composeApp:linkDebugFrameworkIosArm64",
        "--no-configuration-cache",
        "-q"
    ).directory(java.io.File(rootDir)).inheritIO().start()

    if (process.waitFor() != 0) {
        throw IllegalStateException("Failed to build Kotlin framework for iOS device")
    }

    // Build Xcode project - try using existing DerivedData first
    val xcodeDerivedDataApp = java.io.File(System.getProperty("user.home"))
        .resolve("Library/Developer/Xcode/DerivedData")
        .listFiles()
        ?.firstOrNull { it.name.startsWith("iosConnectivityTest") }
        ?.resolve("Build/Products/Debug-iphoneos/iosConnectivityTest.app")

    val appPath = if (xcodeDerivedDataApp?.exists() == true) {
        println("Using existing Xcode build from: ${xcodeDerivedDataApp.absolutePath}")
        xcodeDerivedDataApp.absolutePath
    } else {
        // Build with Xcode using -sdk iphoneos (avoids destination resolution issues)
        process = ProcessBuilder(
            "xcodebuild",
            "-project", "$rootDir/iosConnectivityTest/iosConnectivityTest.xcodeproj",
            "-scheme", "iosConnectivityTest",
            "-configuration", "Debug",
            "-sdk", "iphoneos",
            "-arch", "arm64",
            "-derivedDataPath", "$rootDir/build/ios-test-derived-data",
            "-allowProvisioningUpdates",
            "FRAMEWORK_SEARCH_PATHS=$rootDir/composeApp/build/bin/iosArm64/debugFramework",
            "build"
        ).directory(java.io.File(rootDir)).inheritIO().start()

        // Wait with timeout (120 seconds)
        if (!process.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw IllegalStateException("iOS device build timed out after 120 seconds - you may need to build once in Xcode first to approve code signing")
        }

        if (process.exitValue() != 0) {
            throw IllegalStateException("Failed to build iOS device app with Xcode")
        }

        "$rootDir/build/ios-test-derived-data/Build/Products/Debug-iphoneos/iosConnectivityTest.app"
    }

    // Install on device
    val installProcess = ProcessBuilder(
        "xcrun", "devicectl", "device", "install", "app",
        "--device", udid,
        appPath
    ).inheritIO().start()

    if (installProcess.waitFor() != 0) {
        throw IllegalStateException("Failed to install iOS device app")
    }
}
