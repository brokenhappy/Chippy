package com.woutwerkman.util

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class WithProcessTest {

    @Test
    fun `cancelling withProcess kills blocking reader immediately`() = runBlocking {
        // Launch a long-lived process whose stdout we read with a blocking call.
        // When cancelled, the sentinel inside withProcess must kill the process
        // so the blocking read unblocks — otherwise this test deadlocks.
        val job = launch(Dispatchers.IO) {
            withProcess(ProcessBuilder("sleep", "60")) { process ->
                withContext(Dispatchers.IO) {
                    process.inputStream.bufferedReader().readText()
                }
            }
        }
        // Give the process time to start and the read to block
        delay(500)
        // Must complete within a few seconds; without the fix this hangs forever
        withTimeout(3.seconds) {
            job.cancelAndJoin()
        }
    }

    @Test
    fun `run throws on non-zero exit`() = runTest {
        val e = assertFailsWith<ProcessFailedException> {
            ProcessBuilder("sh", "-c", "echo oops >&2; exit 42").run()
        }
        assertEquals(42, e.exitCode)
        assertTrue(e.stderr.contains("oops"))
    }

    @Test
    fun `runAndReadOutput returns stdout`() = runTest {
        val output = ProcessBuilder("echo", "hello").runAndReadOutput()
        assertEquals("hello", output.trim())
    }
}
