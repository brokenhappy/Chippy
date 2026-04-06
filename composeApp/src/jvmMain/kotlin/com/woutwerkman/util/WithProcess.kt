package com.woutwerkman.util

import kotlinx.coroutines.*
import java.util.concurrent.CompletableFuture

class ProcessFailedException(
    val exitCode: Int,
    val stderr: String,
    description: String,
) : Exception("$description (exit code $exitCode)${if (stderr.isNotBlank()) ":\n$stderr" else ""}")

/**
 * Runs [block] with a managed [Process]. The process is destroyed when [block]
 * completes or the coroutine is cancelled.
 *
 * If the process exited on its own before destruction, a non-zero exit code
 * throws [ProcessFailedException] with captured stderr.
 *
 * Stderr is drained concurrently to prevent pipe-buffer deadlocks.
 */
suspend fun <T> withProcess(
    builder: ProcessBuilder,
    block: suspend CoroutineScope.(Process) -> T,
): T {
    val process = withContext(Dispatchers.IO) { builder.start() }
    // Drain stderr on a background thread to prevent the OS pipe buffer from
    // filling up and blocking the child process.
    val stderrFuture = if (builder.redirectErrorStream()) null
    else CompletableFuture.supplyAsync { process.errorStream.bufferedReader().readText() }
    try {
        val result = coroutineScope { block(process) }
        if (!process.isAlive) {
            val exitCode = process.exitValue()
            if (exitCode != 0) {
                val stderr = stderrFuture?.get().orEmpty()
                throw ProcessFailedException(exitCode, stderr, builder.command().joinToString(" "))
            }
        }
        return result
    } finally {
        withContext(NonCancellable + Dispatchers.IO) {
            process.destroyForcibly()
            process.waitFor()
        }
    }
}

/**
 * Runs the process to completion.
 * Throws [ProcessFailedException] with stderr on non-zero exit.
 */
suspend fun ProcessBuilder.run() = withProcess(this) { process ->
    withContext(Dispatchers.IO) { process.waitFor() }
}

/**
 * Runs the process to completion and returns its stdout.
 * Throws [ProcessFailedException] with stderr on non-zero exit.
 */
suspend fun ProcessBuilder.runAndReadOutput(): String = withProcess(this) { process ->
    withContext(Dispatchers.IO) {
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        output
    }
}
