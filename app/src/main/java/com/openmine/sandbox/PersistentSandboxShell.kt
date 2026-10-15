package com.openmine.sandbox

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private const val MAX_OUTPUT_LENGTH = 15_000
private const val RS = "\u001e"
private const val US = "\u001f"
private const val PID_PROBE_PREFIX = "${RS}OPENMINEPID$US"

/** A persistent Bash process. This is a shell, not a security boundary for hostile commands. */
class PersistentSandboxShell(
    private val executor: ProotExecutor,
    private val tmpPath: String,
    initialCwd: String = "/root",
    private val guestTmpPath: String = "/tmp",
) {
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateLock = Any()
    @Volatile private var handle: ProotHandle? = null
    @Volatile private var bashPid: Int? = null
    @Volatile private var processToken: ProcessIdentity? = null
    @Volatile private var lastCwd = initialCwd
    private var exportedState = ""
    private val cancelLock = Any()
    private var watchdog: Job? = null
    private var cleanup: Job? = null
    private val currentSink = AtomicReference<CommandSink?>(null)

    private class ProcessIdentity {
        val group = AtomicInteger()
        val started = CountDownLatch(1)
        val cleaning = AtomicBoolean()
        val cleaned = CountDownLatch(1)
    }

    private class CommandSink(
        val nonce: String,
        val onStdout: ((String) -> Unit)?,
        val onStderr: ((String) -> Unit)?,
    ) {
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val done = CompletableDeferred<Result>()
        @Volatile var cancelled = false
        var stdoutFinished = false
        var stderrResult: Result? = null
    }

    data class Result(val exitCode: Int, val cwd: String, val bashPid: Int, val shellDied: Boolean = false)

    /** Sources commands to retain cwd/export, and drains BOTH output pipes before returning. */
    suspend fun run(
        command: String,
        timeoutSeconds: Long,
        onStdout: ((String) -> Unit)? = null,
        onStderr: ((String) -> Unit)? = null,
    ): Map<String, Any> = mutex.withLock {
        require(command.isNotBlank()) { "Enter a shell command" }
        require(command.length <= 8192 && '\u0000' !in command) { "Command is too large or contains NUL" }
        val sink = CommandSink(UUID.randomUUID().toString().replace("-", ""), onStdout, onStderr)
        val cmdFile = File(tmpPath, ".openmine_cmd_${sink.nonce}")
        val stateFile = File(tmpPath, ".openmine_env_${sink.nonce}")
        try {
            val fresh = ensureShell()
            val identity = processToken ?: error("Shell failed to start")
            check(runInterruptible(Dispatchers.IO) { identity.started.await(5, TimeUnit.SECONDS) }) { "Shell PID unavailable" }
            currentSink.set(sink)
            cmdFile.writeText(command)
            stateFile.writeText(exportedState)
            val statePath = quote("$guestTmpPath/${stateFile.name}")
            val path = quote("$guestTmpPath/${cmdFile.name}")
            // Dedicated descriptors survive ordinary `exec >file` / `exec 2>file` redirects.
            // Both pipes need a barrier: a stderr marker alone can race stdout delivery,
            // and a command like `printf hello` otherwise never releases readLine().
            val line = (if (fresh) ". $statePath; " else "") +
                ". $path; __openmine_status=\$?; command rm -f $path; builtin export -p > $statePath; " +
                "builtin printf '\\n\\036%s\\036\\n' '${sink.nonce}' >&19; " +
                "builtin printf '\\n\\036%s\\037%d\\037%d\\037%s\\036\\n' '${sink.nonce}' \"\$__openmine_status\" \"\$\$\" \"\$PWD\" >&20"
            check(handle?.writeInput(line) == true) { "Shell input closed; retry the command" }
            val result = withTimeoutOrNull(timeoutSeconds.coerceIn(1, 1800) * 1000) { sink.done.await() }
            if (result == null) {
                cancelForeground()
                val recovered = withTimeoutOrNull(2000) { sink.done.await() }
                if (recovered == null) reset()
                return@withLock buildResult(sink, recovered ?: Result(-1, lastCwd, 0, true), timedOut = true)
            }
            if (!result.shellDied && !sink.cancelled) {
                lastCwd = result.cwd; bashPid = result.bashPid
                exportedState = stateFile.readText()
            }
            buildResult(sink, result)
        } catch (e: CancellationException) {
            // A cancelled caller must not release the mutex while its command keeps
            // running; otherwise a subsequent command inherits the old output/process.
            withContext(NonCancellable) {
                cancelForeground()
                withTimeoutOrNull(1500) { sink.done.await() }
                reset()?.join()
            }
            throw e
        } catch (e: Exception) {
            withContext(NonCancellable) { reset()?.join() }
            synchronized(sink) { appendBounded(sink.stderr, e.message ?: "Shell command failed") }
            buildResult(sink, Result(-1, lastCwd, 0, true))
        } finally {
            if (sink.cancelled) withContext(NonCancellable) { reset()?.join() }
            currentSink.compareAndSet(sink, null)
            cmdFile.delete()
            stateFile.delete()
        }
    }

    fun writeInput(line: String) { handle?.writeInput(line) }

    /** Freeze the shell before terminating its children, so no later statement can run. */
    fun cancelForeground() {
        val sink = currentSink.get() ?: return
        sink.cancelled = true
        scope.launch {
            synchronized(cancelLock) {
                if (currentSink.get() === sink) reset() else null
            }?.join()
        }
    }

    /** Detach immediately; callers stopping the runtime may join the returned cleanup job. */
    fun reset(): Job? = synchronized(stateLock) {
        val old = handle
        val identity = processToken
        handle = null
        processToken = null
        bashPid = null
        watchdog?.cancel()
        watchdog = null
        if (old != null) {
            val previousCleanup = cleanup
            cleanup = scope.launch { previousCleanup?.join(); cleanProcess(old, identity) }
        }
        currentSink.getAndSet(null)?.done?.complete(Result(-1, lastCwd, 0, true))
        cleanup
    }

    private fun cleanProcess(old: ProotHandle, identity: ProcessIdentity?) {
        if (identity != null && !identity.cleaning.compareAndSet(false, true)) {
            try { identity.cleaned.await() } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            return
        }
        // PID output may arrive after reset detached this session. Keep its
        // identity until cleanup so early Cancel can still kill the right group.
        try {
            if (identity != null && identity.group.get() == 0) identity.started.await(5, TimeUnit.SECONDS)
        } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        finally {
            try { executor.stopProcessGroup(old, identity?.group?.get() ?: 0, graceful = false) }
            finally { identity?.cleaned?.countDown() }
        }
    }

    private fun ensureShell() = synchronized(stateLock) {
        if (handle != null) return@synchronized false
        val token = ProcessIdentity()
        processToken = token
        val h = executor.executeStreaming(
            command = "exec setsid bash --noprofile --norc",
            onStdout = { dispatch(it, false, token) },
            onStderr = { dispatch(it, true, token) },
        )
        handle = h
        check(h.writeInput("exec 19>&1 20>&2; cd ${quote(lastCwd)} 2>/dev/null || cd /root")) { "Bash failed to start" }
        check(h.writeInput("builtin printf '\\n\\036OPENMINEPID\\037%d\\036\\n' \"\$\$\" >&20")) { "Bash failed to start" }
        watchdog = scope.launch {
            h.awaitExit(0)
            // An explicit shell exit must also release background children whose
            // pipes may otherwise keep the old session alive after replacement.
            cleanProcess(h, token)
            synchronized(stateLock) {
                // A delayed watcher from the old process must never tear down a new one.
                if (handle === h) {
                    handle = null
                    bashPid = null
                    currentSink.getAndSet(null)?.done?.complete(Result(-1, lastCwd, 0, true))
                }
            }
        }
        true
    }

    private fun dispatch(line: String, stderr: Boolean, token: ProcessIdentity) {
        if (stderr && line.startsWith(PID_PROBE_PREFIX) && line.endsWith(RS)) {
            line.substring(PID_PROBE_PREFIX.length, line.length - 1).toIntOrNull()?.takeIf { it > 1 }?.let {
                if (token.group.compareAndSet(0, it)) token.started.countDown()
                if (processToken === token) bashPid = token.group.get()
            }
            return
        }
        if (processToken !== token) return
        val sink = currentSink.get() ?: return
        synchronized(sink) {
            if (!stderr && line == "$RS${sink.nonce}$RS") {
                sink.stdoutFinished = true
            } else if (stderr && line.startsWith("$RS${sink.nonce}$US") && line.endsWith(RS)) {
                val parts = line.substring(1, line.length - 1).split(US, limit = 4)
                if (parts.size == 4) sink.stderrResult = Result(parts[1].toIntOrNull() ?: -1, parts[3], parts[2].toIntOrNull() ?: 0)
            } else if (line.isNotEmpty()) {
                appendBounded(if (stderr) sink.stderr else sink.stdout, line)
                if (stderr) sink.onStderr?.invoke(line) else sink.onStdout?.invoke(line)
            }
            val result = sink.stderrResult
            if (sink.stdoutFinished && result != null) sink.done.complete(result)
        }
    }

    private fun buildResult(sink: CommandSink, result: Result, timedOut: Boolean = false): Map<String, Any> = synchronized(sink) {
        mapOf(
            "success" to (!sink.cancelled && !timedOut && !result.shellDied && result.exitCode == 0),
            "cancelled" to sink.cancelled,
            "stdout" to sink.stdout.toString(),
            "stderr" to (sink.stderr.toString() + if (timedOut) "\nCommand timed out" else if (sink.cancelled) "\nCommand cancelled; completed cwd and exported environment retained" else if (result.shellDied) "\nShell session ended; the next command starts a new session" else "").take(MAX_OUTPUT_LENGTH),
            "exit_code" to if (timedOut) 124 else if (sink.cancelled) 130 else result.exitCode,
            "timed_out" to timedOut,
            "cwd" to result.cwd,
            "shell_died" to result.shellDied,
        )
    }
}

private fun quote(value: String) = "'${value.replace("'", "'\\''")}'"
private fun appendBounded(buf: StringBuilder, line: String) {
    if (buf.length >= MAX_OUTPUT_LENGTH) return
    if (buf.isNotEmpty()) buf.append('\n')
    buf.append(line.take((MAX_OUTPUT_LENGTH - buf.length).coerceAtLeast(0)))
}
