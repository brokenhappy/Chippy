package com.woutwerkman.connectivitytest

import com.woutwerkman.net.*
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

fun main() {
    val platformsStr = System.getProperty("test.platforms", "jvm")
    val instanceId = System.getProperty("test.instance.id", "jvm-unknown")

    println("[$instanceId] Connectivity Test Starting")
    println("[$instanceId] Platforms: $platformsStr")

    val targetPlatforms = platformsStr.split(",")
        .mapNotNull { TestPlatform.fromString(it) }
        .toSet()

    if (targetPlatforms.isEmpty()) {
        System.err.println("[$instanceId] No valid target platforms specified")
        exitProcess(1)
    }

    val config = ConnectivityTestConfig(
        instanceId = instanceId,
        targetPlatforms = targetPlatforms,
        testTimeoutMs = 60_000
    )

    val result = runBlocking {
        runConnectivityTest(config)
    }

    when (result) {
        is ConnectivityTestResult.Success -> {
            println("[$instanceId] SUCCESS: Connectivity test passed!")
            exitProcess(0)
        }
        is ConnectivityTestResult.Failure -> {
            System.err.println("[$instanceId] FAILURE: ${result.message}")
            result.cause?.printStackTrace()
            exitProcess(1)
        }
    }
}
