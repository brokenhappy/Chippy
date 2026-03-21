import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.util.concurrent.TimeUnit

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
}

@Suppress("DEPRECATION")
kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":composeApp"))
            implementation(libs.kotlinx.coroutines.core)
        }

        jvmMain.dependencies {
            implementation(libs.kotlinx.coroutinesSwing)
        }

        androidMain.dependencies {
        }
    }
}

android {
    namespace = "com.woutwerkman.connectivitytest"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// ============================================================================
// Connectivity Test Infrastructure
// ============================================================================

// Check if Android emulator is available
fun isAndroidEmulatorAvailable(): Boolean {
    return try {
        val process = ProcessBuilder("adb", "devices")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        val lines = output.lines().filter { it.isNotBlank() && !it.startsWith("List of devices") }
        lines.any { it.contains("emulator") || it.contains("device") }
    } catch (e: Exception) {
        false
    }
}

// Get list of connected Android emulators
fun getConnectedEmulators(): List<String> {
    return try {
        val process = ProcessBuilder("adb", "devices")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        output.lines()
            .filter { it.contains("emulator") || (it.contains("device") && !it.startsWith("List")) }
            .mapNotNull { line ->
                val parts = line.split("\\s+".toRegex())
                if (parts.isNotEmpty() && parts[0].isNotBlank()) parts[0] else null
            }
    } catch (e: Exception) {
        emptyList()
    }
}

// Check if iOS real device is connected
fun isIosRealDeviceAvailable(): Boolean {
    return getConnectedIosDevices().isNotEmpty()
}

// Get connected iOS real device info (returns list of Pair<UDID, DeviceName>)
fun getConnectedIosDevices(): List<Pair<String, String>> {
    return try {
        val process = ProcessBuilder("xcrun", "xctrace", "list", "devices")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()

        val devices = mutableListOf<Pair<String, String>>()
        var inDevicesSection = false

        for (line in output.lines()) {
            if (line.startsWith("== Devices ==")) {
                inDevicesSection = true
                continue
            }
            if (line.startsWith("== Simulators ==")) {
                break // Stop at simulators section
            }
            if (inDevicesSection && line.isNotBlank() && !line.startsWith("==")) {
                // Parse: "Device Name (OS Version) (UDID)" where UDID can be various formats
                // e.g., "iPhone (26.3.1) (00008110-001A68212E9A801E)"
                // Skip devices that are clearly not iPhones/iPads (like Macs, Apple Watches)
                if (line.contains("iPhone") || line.contains("iPad")) {
                    // Extract the last parenthesized value as UDID
                    val lastParenMatch = Regex("\\(([^)]+)\\)$").find(line.trim())
                    if (lastParenMatch != null) {
                        val udid = lastParenMatch.groupValues[1]
                        val name = line.substringBefore(" (").trim()
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

// Capture values at configuration time for configuration cache compatibility
val rootDirPath: String = rootProject.projectDir.absolutePath
val jvmClasspath = kotlin.jvm().compilations["main"].runtimeDependencyFiles +
        kotlin.jvm().compilations["main"].output.allOutputs

// Task to run JVM connectivity test instance
tasks.register<JavaExec>("runConnectivityTestJvm") {
    group = "connectivity-test"
    description = "Run a single JVM instance of the connectivity test"

    mainClass.set("com.woutwerkman.connectivitytest.ConnectivityTestMainKt")
    classpath = jvmClasspath

    // Pass platform targets as system properties
    systemProperty("test.platforms", project.findProperty("platforms")?.toString() ?: "jvm,android-simulator")
    systemProperty("test.instance.id", project.findProperty("instanceId")?.toString() ?: "jvm-1")

    // Ensure we can see output
    standardOutput = System.out
    errorOutput = System.err
}

// Task to print classpath for direct Java invocation
tasks.register("printJvmClasspath") {
    group = "connectivity-test"
    description = "Print the JVM classpath for direct Java invocation"

    dependsOn("jvmMainClasses")

    doLast {
        println(jvmClasspath.asPath)
    }
}

// Main task to orchestrate the connectivity test
tasks.register("testConnectibility") {
    group = "verification"
    description = "Test network connectivity between platforms"

    // Capture config-time values
    val platformsArg = project.findProperty("platforms")?.toString() ?: ""
    // Use -PfailIfMissing=true or -Pfail-if-any-device-or-simulator-is-not-present
    val failIfMissing = project.hasProperty("failIfMissing") ||
            project.hasProperty("fail-if-any-device-or-simulator-is-not-present")
    val capturedRootDir = rootDirPath

    doLast {
        val requestedPlatforms = if (platformsArg.isBlank()) {
            listOf("jvm", "android-simulator")
        } else {
            platformsArg.split(",").map { it.trim().lowercase() }
        }

        val availablePlatforms = mutableListOf<String>()
        val unavailablePlatforms = mutableListOf<String>()

        for (platform in requestedPlatforms) {
            val isAvailable = when (platform) {
                "jvm" -> true
                "android-simulator" -> isAndroidEmulatorAvailable()
                "ios-real-device" -> isIosRealDeviceAvailable()
                else -> false
            }

            if (isAvailable) {
                availablePlatforms.add(platform)
            } else {
                unavailablePlatforms.add(platform)
            }
        }

        if (unavailablePlatforms.isNotEmpty()) {
            val message = "Unavailable platforms: ${unavailablePlatforms.joinToString(", ")}"
            if (failIfMissing) {
                throw GradleException(message)
            } else {
                logger.warn("WARNING: $message - skipping these platforms")
            }
        }

        if (availablePlatforms.isEmpty()) {
            throw GradleException("No platforms available for testing")
        }

        logger.lifecycle("Testing connectivity between platforms: ${availablePlatforms.joinToString(", ")}")

        val platformsString = availablePlatforms.joinToString(",")
        val processes = mutableListOf<Process>()

        // Pre-build phase: Build all platforms BEFORE launching any instances
        var jvmClasspath: String? = null
        var androidApkPath: String? = null
        var iosDeviceUdid: String? = null
        var iosDeviceName: String? = null

        try {
            // Pre-build JVM
            if (availablePlatforms.contains("jvm")) {
                logger.lifecycle("Pre-building JVM classes and getting classpath...")
                val classpathBuilder = ProcessBuilder(
                    "$capturedRootDir/gradlew",
                    ":connectivityTest:printJvmClasspath",
                    "--no-configuration-cache",
                    "-q"
                )
                    .directory(File(capturedRootDir))
                    .redirectErrorStream(true)
                classpathBuilder.environment().putAll(System.getenv())
                val classpathProcess = classpathBuilder.start()
                val classpath = classpathProcess.inputStream.bufferedReader().readText().trim()
                classpathProcess.waitFor()

                if (classpath.isEmpty() || classpathProcess.exitValue() != 0) {
                    throw GradleException("Failed to get JVM classpath")
                }
                jvmClasspath = classpath
            }

            // Pre-build iOS (takes the longest, so start early)
            if (availablePlatforms.contains("ios-real-device")) {
                val iosDevices = getConnectedIosDevices()
                if (iosDevices.isNotEmpty()) {
                    val device = iosDevices.first()
                    iosDeviceUdid = device.first
                    iosDeviceName = device.second

                    logger.lifecycle("Pre-building iOS app for device: $iosDeviceName ($iosDeviceUdid)")

                    // Build with xcodebuild
                    val xcodeBuildProcess = ProcessBuilder(
                        "xcodebuild",
                        "-project", "$capturedRootDir/iosApp/iosApp.xcodeproj",
                        "-scheme", "iosApp",
                        "-configuration", "Debug",
                        "-destination", "id=$iosDeviceUdid",
                        "-derivedDataPath", "$capturedRootDir/build/ios-derived-data",
                        "build"
                    )
                        .directory(File(capturedRootDir))
                        .inheritIO()
                    xcodeBuildProcess.environment().putAll(System.getenv())
                    val xcodeBuild = xcodeBuildProcess.start()
                    val xcodeExitCode = xcodeBuild.waitFor()

                    if (xcodeExitCode != 0) {
                        logger.warn("WARNING: Failed to build iOS app")
                        iosDeviceUdid = null
                    } else {
                        // Install the app
                        logger.lifecycle("Installing iOS app on device...")
                        val installProcess = ProcessBuilder(
                            "xcrun", "devicectl", "device", "install", "app",
                            "--device", iosDeviceUdid!!,
                            "$capturedRootDir/build/ios-derived-data/Build/Products/Debug-iphoneos/iosApp.app"
                        )
                            .inheritIO()
                            .start()
                        installProcess.waitFor()
                    }
                }
            }

            // Pre-build Android
            if (availablePlatforms.contains("android-simulator")) {
                logger.lifecycle("Pre-building Android APK...")
                val buildProcess = ProcessBuilder(
                    "$capturedRootDir/gradlew",
                    ":composeApp:assembleDebug",
                    "--no-configuration-cache"
                )
                    .directory(File(capturedRootDir))
                    .inheritIO()
                    .start()
                val buildExitCode = buildProcess.waitFor()
                if (buildExitCode == 0) {
                    androidApkPath = "$capturedRootDir/composeApp/build/outputs/apk/debug/composeApp-debug.apk"
                }
            }

            // ============================================================
            // LAUNCH PHASE: All builds complete, now launch all instances
            // ============================================================
            logger.lifecycle("All builds complete, launching instances...")

            // Launch iOS first (it takes time to start)
            if (iosDeviceUdid != null) {
                logger.lifecycle("Launching iOS app on $iosDeviceName...")
                val launchProcess = ProcessBuilder(
                    "xcrun", "devicectl", "device", "process", "launch",
                    "--device", iosDeviceUdid!!,
                    "com.woutwerkman.Chippy"
                )
                    .inheritIO()
                    .start()
                launchProcess.waitFor()

                // Give iOS a head start
                Thread.sleep(3000)
            }

            // Launch JVM instances
            if (jvmClasspath != null) {
                val javaHome = System.getProperty("java.home")
                val javaCmd = "$javaHome/bin/java"

                repeat(2) { i ->
                    val instanceId = "jvm-${i + 1}"
                    logger.lifecycle("Starting JVM instance: $instanceId")

                    val processBuilder = ProcessBuilder(
                        javaCmd,
                        "-cp", jvmClasspath!!,
                        "-Dtest.platforms=$platformsString",
                        "-Dtest.instance.id=$instanceId",
                        "com.woutwerkman.connectivitytest.ConnectivityTestMainKt"
                    )
                        .directory(File(capturedRootDir))
                        .redirectErrorStream(true)
                    processBuilder.environment().putAll(System.getenv())
                    val process = processBuilder.start()

                    processes.add(process)

                    // Log output in background thread
                    Thread {
                        process.inputStream.bufferedReader().forEachLine { line ->
                            logger.lifecycle("[$instanceId] $line")
                        }
                    }.start()
                }
            }

            // Launch Android emulator instances (using pre-built APK)
            if (androidApkPath != null && availablePlatforms.contains("android-simulator")) {
                val emulators = getConnectedEmulators()
                val emulatorsToUse = emulators.take(2)

                if (emulatorsToUse.isNotEmpty()) {
                    emulatorsToUse.forEachIndexed { i, emulatorId ->
                        val instanceId = "android-${i + 1}"
                        logger.lifecycle("Installing and launching on $emulatorId: $instanceId")

                        // Install APK
                        val installProcess = ProcessBuilder(
                            "adb", "-s", emulatorId, "install", "-r", androidApkPath!!
                        ).inheritIO().start()
                        installProcess.waitFor()

                        // Launch the app in connectivity test mode
                        val launchProcess = ProcessBuilder(
                            "adb", "-s", emulatorId, "shell", "am", "start",
                            "-n", "com.woutwerkman/.MainActivity",
                            "--ez", "connectivity_test", "true",
                            "--es", "instanceId", instanceId,
                            "--es", "platforms", platformsString
                        ).inheritIO().start()
                        launchProcess.waitFor()

                        logger.lifecycle("Launched connectivity test on $emulatorId")
                    }

                    logger.lifecycle("Waiting for Android instances to start discovery...")
                    Thread.sleep(5000)
                }
            }

            // Wait for all processes with timeout
            val timeoutMs = 120_000L
            val startTime = System.currentTimeMillis()

            for ((index, process) in processes.withIndex()) {
                val remainingTime = timeoutMs - (System.currentTimeMillis() - startTime)
                if (remainingTime <= 0) {
                    throw GradleException("Connectivity test timed out")
                }

                val finished = process.waitFor(remainingTime, TimeUnit.MILLISECONDS)
                if (!finished) {
                    throw GradleException("Process $index timed out")
                }

                val exitCode = process.exitValue()
                if (exitCode != 0) {
                    throw GradleException("Process $index failed with exit code $exitCode")
                }
            }

            logger.lifecycle("All connectivity tests passed!")

        } catch (e: Exception) {
            processes.forEach { process ->
                if (process.isAlive) {
                    process.destroyForcibly()
                }
            }
            throw e
        }
    }
}

// Capture build directory at configuration time
val buildDirPath: String = project.layout.buildDirectory.get().asFile.absolutePath

// Task to install and run on Android emulator
tasks.register("runConnectivityTestAndroid") {
    group = "connectivity-test"
    description = "Run connectivity test on Android emulator"

    val instanceIdProp = project.findProperty("instanceId")?.toString() ?: "android-1"
    val platformsProp = project.findProperty("platforms")?.toString() ?: "jvm,android-simulator"
    val emulatorIdProp = project.findProperty("emulatorId")?.toString()
    val capturedRootDir = rootDirPath
    val capturedBuildDir = buildDirPath

    doLast {
        val emulators = getConnectedEmulators()
        if (emulators.isEmpty()) {
            throw GradleException("No Android emulators available")
        }

        val emulatorId = emulatorIdProp ?: emulators.first()

        // Build and install the test APK using ProcessBuilder
        val buildProcess = ProcessBuilder(
            "$capturedRootDir/gradlew",
            ":connectivityTest:assembleDebug",
            "--no-configuration-cache"
        )
            .directory(File(capturedRootDir))
            .inheritIO()
            .start()

        val buildExitCode = buildProcess.waitFor()
        if (buildExitCode != 0) {
            throw GradleException("Failed to build APK")
        }

        // Install APK on emulator
        val apkPath = "$capturedBuildDir/outputs/apk/debug/connectivityTest-debug.apk"
        val installProcess = ProcessBuilder("adb", "-s", emulatorId, "install", "-r", apkPath)
            .inheritIO()
            .start()

        val installExitCode = installProcess.waitFor()
        if (installExitCode != 0) {
            throw GradleException("Failed to install APK")
        }

        // Start the test activity with parameters
        val startProcess = ProcessBuilder(
            "adb", "-s", emulatorId, "shell", "am", "start",
            "-n", "com.woutwerkman.connectivitytest/.ConnectivityTestActivity",
            "--es", "platforms", platformsProp,
            "--es", "instanceId", instanceIdProp
        )
            .inheritIO()
            .start()

        startProcess.waitFor()

        println("Started connectivity test on emulator $emulatorId with instanceId=$instanceIdProp")
    }
}
