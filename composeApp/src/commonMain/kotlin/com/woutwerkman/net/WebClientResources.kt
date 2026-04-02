package com.woutwerkman.net

/**
 * Pre-loaded web client files served to browsers by the host device.
 *
 * Files are loaded at server startup using platform-specific mechanisms
 * (JVM classpath, iOS app bundle) and served from memory.
 */
internal class WebClientResources(private val files: Map<String, ByteArray>) {
    fun get(path: String): ByteArray? = files[path]

    fun contentType(path: String): String = when {
        path.endsWith(".html") -> "text/html"
        path.endsWith(".js") -> "application/javascript"
        path.endsWith(".wasm") -> "application/wasm"
        path.endsWith(".css") -> "text/css"
        else -> "application/octet-stream"
    }

    companion object
}

/**
 * Load all web client files into memory.
 * Platform-specific: JVM reads from classpath, iOS from app bundle.
 */
internal expect suspend fun loadWebClientResources(): WebClientResources
