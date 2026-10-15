package com.openmine.sandbox

import java.io.Closeable
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Owns jobs for the runtime service, independently of terminal commands and their timeouts.
 *
 * Run servers in the foreground (for example `python3 -m http.server 8000`). Ordinary shell
 * background jobs are waited for, but commands that daemonize, double-fork, or create their
 * own session are unsupported. Ownership lasts until the runtime stops or Android kills it;
 * this does not restore processes after an app/process restart.
 */
class RuntimeProcessManager(
    private val executor: ProotExecutor,
    private val outputLimit: Int = 15_000,
    private val maxRunning: Int = 16,
    private val historyLimit: Int = 64,
) : Closeable {
    data class ProcessInfo(
        val id: String,
        val command: String,
        val state: String,
        val exitCode: Int?,
        val stdout: String,
        val stderr: String,
    )

    private class Entry(val id: String, val command: String) {
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val group = AtomicInteger()
        val started = CountDownLatch(1)
        var handle: ProotHandle? = null
        var state = "running"
        var exitCode: Int? = null
        var stopping = false

        fun snapshot() = synchronized(this) {
            ProcessInfo(id, command, state, exitCode, stdout.toString(), stderr.toString())
        }
    }

    private val entries = linkedMapOf<String, Entry>()
    private val watchers = Executors.newCachedThreadPool { task ->
        Thread(task, "openmine-runtime-process").apply { isDaemon = true }
    }
    private var closed = false

    init {
        require(outputLimit > 0 && maxRunning > 0 && historyLimit >= maxRunning)
    }

    /** Returns once the owning shell is ready. The command has no execution deadline. */
    @Synchronized
    fun start(command: String): ProcessInfo {
        check(!closed) { "Runtime process manager is closed" }
        require(command.isNotBlank()) { "Enter a command to start" }
        require(command.length <= 8192 && '\u0000' !in command) { "Command is too large or contains NUL" }
        check(entries.values.count { it.snapshot().state == "running" } < maxRunning) {
            "Too many managed processes; stop a process before starting another"
        }
        while (entries.size >= historyLimit) {
            val finished = entries.entries.firstOrNull { it.value.snapshot().state != "running" } ?: break
            entries.remove(finished.key)
        }
        val entry = Entry(UUID.randomUUID().toString(), command)
        val marker = "\u001eOPENMINE_PROCESS_${entry.id}:"
        // setsid gives every managed job its own signal group. The input gate ensures a
        // startup timeout cannot leave an already-running user command without an owner.
        val script = "printf '%s%d\\n' ${quote(marker)} \"\$\$\" >&2; " +
            "IFS= read -r __openmine_start || exit 125; " +
            "[ \"\$__openmine_start\" = run ] || exit 125; " +
            "eval ${quote(command)}; __openmine_exit=\$?; wait; exit \"\$__openmine_exit\""
        val handle = executor.executeStreaming(
            "exec setsid bash --noprofile --norc -c ${quote(script)}",
            onStdout = { append(entry, it, false) },
            onStderr = { line ->
                val pid = if (line.startsWith(marker)) line.removePrefix(marker).toIntOrNull() else null
                if (pid != null && pid > 1 && entry.group.compareAndSet(0, pid)) entry.started.countDown()
                else append(entry, line, true)
            },
        )
        entry.handle = handle
        try {
            check(entry.started.await(5, TimeUnit.SECONDS)) {
                "Managed process failed to start: ${entry.snapshot().stderr.ifBlank { "no startup response" }}"
            }
            check(handle.writeInput("run")) { "Managed process input closed during startup" }
            entries[entry.id] = entry
            watchers.execute {
                val exit = try { handle.awaitExit(0) } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    -1
                }
                synchronized(entry) {
                    entry.exitCode = exit
                    entry.state = if (entry.stopping) "stopped" else "exited"
                }
            }
            return entry.snapshot()
        } catch (failure: Throwable) {
            terminate(entry)
            entries.remove(entry.id)
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            throw failure
        }
    }

    @Synchronized
    fun snapshot(id: String): ProcessInfo? = entries[id]?.snapshot()

    @Synchronized
    fun list(): List<ProcessInfo> = entries.values.map { it.snapshot() }

    /** Stops this job's process group, including children, without touching other jobs. */
    @Synchronized
    fun stop(id: String): Boolean {
        val entry = entries[id] ?: return false
        synchronized(entry) {
            if (entry.state != "running") return false
            entry.stopping = true
        }
        terminate(entry)
        synchronized(entry) {
            entry.state = "stopped"
            if (entry.exitCode == null) entry.exitCode = -1
        }
        return true
    }

    @Synchronized
    fun stopAll() {
        entries.keys.toList().forEach { stop(it) }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        stopAll()
        watchers.shutdown()
    }

    private fun terminate(entry: Entry) {
        entry.handle?.let { executor.stopProcessGroup(it, entry.group.get()) }
    }

    private fun append(entry: Entry, line: String, stderr: Boolean) = synchronized(entry) {
        val output = if (stderr) entry.stderr else entry.stdout
        output.append(line).append('\n')
        if (output.length > outputLimit) output.delete(0, output.length - outputLimit)
    }

    private fun quote(value: String) = "'${value.replace("'", "'\\''")}'"
}
