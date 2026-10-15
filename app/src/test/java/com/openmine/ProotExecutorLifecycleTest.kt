package com.openmine

import com.openmine.sandbox.ProotExecutor
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Process ownership regression tests using actual host processes, not Android verification. */
class ProotExecutorLifecycleTest {
    @Test fun interruptedWaitDestroysTheOwnedProcess() {
        val spawned = CountDownLatch(1)
        val process = AtomicReference<Process>()
        val failure = AtomicReference<Throwable>()
        val executor = ProotExecutor("unused", "unused", "/tmp", "/tmp", "/tmp",
            processStarter = { args, _, _ -> ProcessBuilder("/bin/bash", "-c", args.last()).directory(File("/tmp")).start().also { process.set(it); spawned.countDown() } })
        val worker = Thread {
            try { executor.execute("exec sleep 20", 30) } catch (e: Throwable) { failure.set(e) }
        }
        try {
            worker.start()
            assertTrue(spawned.await(3, TimeUnit.SECONDS))
            worker.interrupt()
            worker.join(3000)
            assertFalse("Interrupted execution must finish", worker.isAlive)
            assertTrue(failure.get() is InterruptedException)
            assertTrue("Cancelled subprocess must be destroyed", process.get().waitFor(3, TimeUnit.SECONDS))
        } finally { process.get()?.destroyForcibly(); worker.interrupt() }
    }

    @Test fun reviewedToolCancellationIsPolledAndDestroysProcess() {
        val spawned = CountDownLatch(1)
        val process = AtomicReference<Process>()
        val cancelled = AtomicBoolean(false)
        val failure = AtomicReference<Throwable>()
        val executor = ProotExecutor("unused", "unused", "/tmp", "/tmp", "/tmp",
            processStarter = { args, _, _ -> ProcessBuilder("/bin/bash", "-c", args.last()).start().also { process.set(it); spawned.countDown() } })
        val worker = Thread {
            try { executor.execute("exec sleep 20", 30, checkCancelled = { if (cancelled.get()) throw CancellationException("User cancelled") }) }
            catch (e: Throwable) { failure.set(e) }
        }
        try {
            worker.start()
            assertTrue(spawned.await(3, TimeUnit.SECONDS))
            cancelled.set(true)
            worker.join(3000)
            assertFalse(worker.isAlive)
            assertTrue(failure.get() is CancellationException)
            assertTrue(process.get().waitFor(3, TimeUnit.SECONDS))
        } finally { process.get()?.destroyForcibly(); worker.interrupt() }
    }
}
