package com.woutwerkman.net

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.stringWithContentsOfFile

@OptIn(ExperimentalForeignApi::class)
internal actual suspend fun loadWebClientResources(): WebClientResources {
    val bundlePath = NSBundle.mainBundle.resourcePath ?: error("No resource path")
    val wasmDir = "$bundlePath/wasmWebClient"

    val manifestPath = "$wasmDir/manifest.txt"
    val manifestText = NSString.stringWithContentsOfFile(manifestPath, NSUTF8StringEncoding, null) as? String
        ?: error("Could not read WASM manifest at $manifestPath")

    val names = manifestText.lines().filter { it.isNotBlank() }
    val files = names.associate { name ->
        val data = NSData.dataWithContentsOfFile("$wasmDir/$name")
            ?: error("Could not read WASM resource: $name")
        name to data.toByteArray()
    }
    return WebClientResources(files)
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val length = this.length.toInt()
    if (length == 0) return byteArrayOf()
    val bytes = ByteArray(length)
    bytes.usePinned { pinned ->
        platform.posix.memcpy(pinned.addressOf(0), this.bytes, this.length)
    }
    return bytes
}
