package com.openmine

import com.openmine.sandbox.ProotExecutor
import com.openmine.sandbox.RuntimeProcessManager
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** Real host process checks; Android/PRoot lifecycle still requires device verification. */
class RuntimeProcessManagerTest {
    private fun executor() = ProotExecutor("unused", "unused", "/tmp", "/tmp", "/tmp",
        processStarter = { args, _, _ -> ProcessBuilder("/bin/bash", "-c", args.last()).start() })

    @Test fun managedJobOutlivesTheStartingCallAndUnrelatedCommands() {
        val executor = executor()
        RuntimeProcessManager(executor).use { manager ->
            val job = manager.start("printf 'managed-ready\\n'; exec sleep 30")
            await { manager.snapshot(job.id)?.stdout?.contains("managed-ready") == true }
            assertEquals(true, executor.execute("printf 'foreground-finished'", 2)["success"])
            assertEquals("running", manager.snapshot(job.id)?.state)
            assertTrue(manager.stop(job.id))
            assertEquals("stopped", manager.snapshot(job.id)?.state)
            assertFalse(manager.stop(job.id))
            assertFalse(manager.stop("unknown"))
        }
    }

    @Test fun reportsActualExitAndBothOutputStreams() {
        RuntimeProcessManager(executor()).use { manager ->
            val job = manager.start("printf 'hello'; printf 'failure' >&2; exit 7")
            await { manager.snapshot(job.id)?.state == "exited" }
            val result = manager.snapshot(job.id)!!
            assertEquals(7, result.exitCode)
            assertEquals("hello\n", result.stdout)
            assertEquals("failure\n", result.stderr)
        }
    }

    @Test fun drainsOutputLargerThanPipesWhileRetainingBoundedTails() {
        RuntimeProcessManager(executor(), outputLimit = 256).use { manager ->
            val job = manager.start("printf '%070000d\\n' 0; printf '%070000d\\n' 0 >&2; printf STDOUT-END; printf STDERR-END >&2")
            await { manager.snapshot(job.id)?.state == "exited" }
            val result = manager.snapshot(job.id)!!
            assertEquals(0, result.exitCode)
            assertEquals(256, result.stdout.length)
            assertEquals(256, result.stderr.length)
            assertTrue(result.stdout.endsWith("STDOUT-END\n"))
            assertTrue(result.stderr.endsWith("STDERR-END\n"))
        }
    }

    @Test fun stopTerminatesDescendantsAndLeavesOtherJobsRunning() {
        val childPidFile = Files.createTempFile("openmine-child", ".pid").toFile()
        try {
            RuntimeProcessManager(executor()).use { manager ->
                val first = manager.start("sleep 30 & printf '%s' \$! > '${childPidFile.absolutePath}'; wait")
                val second = manager.start("exec sleep 30")
                await { childPidFile.readText().toIntOrNull() != null }
                val child = childPidFile.readText().toInt()
                assertTrue(isRunning(child))
                assertTrue(manager.stop(first.id))
                await { !isRunning(child) }
                assertEquals("running", manager.snapshot(second.id)?.state)
                manager.stopAll()
                assertEquals(listOf("stopped", "stopped"), manager.list().map { it.state })
            }
        } finally { childPidFile.delete() }
    }

    @Test fun waitsForOrdinaryBackgroundJobsAndCloseReleasesOwnership() {
        val finished = Files.createTempFile("openmine-background", ".txt").toFile()
        val manager = RuntimeProcessManager(executor())
        try {
            val job = manager.start("(sleep 0.2; printf complete > '${finished.absolutePath}') &")
            await { manager.snapshot(job.id)?.state == "exited" }
            assertEquals("complete", finished.readText())
            val active = manager.start("exec sleep 30")
            manager.close()
            assertEquals("stopped", manager.snapshot(active.id)?.state)
            assertThrows(IllegalStateException::class.java) { manager.start("true") }
        } finally { manager.close(); finished.delete() }
    }

    @Test fun interruptedStopStillKillsChildrenThatIgnoreTermination() {
        val childPidFile = Files.createTempFile("openmine-uncooperative-child", ".pid").toFile()
        try {
            RuntimeProcessManager(executor()).use { manager ->
                val command = "bash -c 'trap \"\" TERM; printf \"%s\" \$\$ > \"${childPidFile.absolutePath}\"; exec sleep 30' & wait"
                val job = manager.start(command)
                await { childPidFile.readText().toIntOrNull() != null }
                val child = childPidFile.readText().toInt()
                Thread.currentThread().interrupt()
                try {
                    assertTrue(manager.stop(job.id))
                    assertTrue(Thread.currentThread().isInterrupted)
                } finally { Thread.interrupted() }
                await { !isRunning(child) }
            }
        } finally { Thread.interrupted(); childPidFile.delete() }
    }

    @Test fun boundsRunningProcessesAndFinishedHistory() {
        RuntimeProcessManager(executor(), maxRunning = 1, historyLimit = 2).use { manager ->
            val active = manager.start("exec sleep 30")
            assertThrows(IllegalStateException::class.java) { manager.start("true") }
            manager.stop(active.id)
            repeat(3) {
                val finished = manager.start("true")
                await { manager.snapshot(finished.id)?.state == "exited" }
            }
            assertEquals(2, manager.list().size)
            assertNull(manager.snapshot(active.id))
        }
    }

    private fun isRunning(pid: Int): Boolean {
        val status = File("/proc/$pid/status")
        // Reaped children can briefly remain as zombies under the container's PID 1.
        return runCatching { status.readLines().first { it.startsWith("State:") }.let { !it.contains("Z (zombie)") } }.getOrDefault(false)
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue("Process state did not converge within five seconds", condition())
    }
}
