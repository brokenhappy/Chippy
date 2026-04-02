package com.woutwerkman.game.model

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
private fun dateNow(): Double = js("Date.now()")

actual fun currentTimeMillis(): Long = dateNow().toLong()
