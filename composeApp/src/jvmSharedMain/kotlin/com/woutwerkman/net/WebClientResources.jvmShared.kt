package com.woutwerkman.net

internal actual suspend fun loadWebClientResources(): WebClientResources {
    val classLoader = Thread.currentThread().contextClassLoader ?: return WebClientResources(emptyMap())
    val manifest = classLoader.getResource("manifest.txt")?.readText()
        ?.lines()?.filter { it.isNotBlank() }
        ?: emptyList()
    val files = manifest.associate { name ->
        name to (classLoader.getResource(name)?.readBytes() ?: byteArrayOf())
    }
    return WebClientResources(files)
}
