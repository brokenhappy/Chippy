package com.woutwerkman

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
private fun dateNow(): Double = js("Date.now()")

class WasmPlatform : Platform {
    override val name: String = "Web (WASM)"
}

actual fun getPlatform(): Platform = WasmPlatform()

actual fun currentTimeMillis(): Long = dateNow().toLong()
