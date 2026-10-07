package com.openmine.sandbox


import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors

private const val MAX_OUTPUT_LENGTH = 15_000
private val streamReaders = Executors.newCachedThreadPool { task -> Thread(task, "openmine-shell-output").apply { isDaemon = true } }
private const val DEFAULT_TIMEOUT_SECONDS = 120L
private const val MAX_TIMEOUT_SECONDS = 1800L

class ProotHandle internal constructor(
    private val process: Process,
    private val cancelled: AtomicBoolean,
    private val readerFutures: List<CompletableFuture<Void>>,
) {
    fun isCancelled(): Boolean = cancelled.get()

    fun cancel() {
        cancelled.set(true)
        // Destroy before closing pipes: closing a pipe being read can otherwise
        // block while the child is still alive, freezing Cancel on the UI thread.
        process.destroyForcibly()
        runCatching { process.outputStream.close() }
    }

    fun writeInput(line: String): Boolean {
        if (cancelled.get() || !process.isAlive) return false
        return runCatching {
            val bytes = (line + "\n").toByteArray()
            process.outputStream.write(bytes)
            process.outputStream.flush()
        }.isSuccess
    }

    fun awaitExit(timeoutMinutes: Long = 30): Int {
        val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(timeoutMinutes)
        // Poll so a cancel() from another thread can short-circuit the wait.
        // On Linux, close(fd) does NOT unblock a thread already inside read(fd),
        // so reader futures can sit waiting on a tracee pipe even after SIGKILL.
        while (!cancelled.get() && process.isAlive) {
            if (timeoutMinutes > 0 && System.nanoTime() >= deadline) { cancel(); return 124 }
            try { process.waitFor(200, TimeUnit.MILLISECONDS) }
            catch (e: InterruptedException) { cancel(); throw e }
        }
        if (cancelled.get()) return -1
        readerFutures.forEach { runCatching { it.get(500, TimeUnit.MILLISECONDS) } }
        return runCatching { process.exitValue() }.getOrDefault(-1)
    }
}

class ProotExecutor(
    private val prootPath: String,
    private val libDir: String,
    private val rootfsPath: String,
    private val homePath: String,
    private val tmpPath: String,
    internal val processStarter: (Array<String>, Array<String>, File?) -> Process = { args, env, directory ->
        Runtime.getRuntime().exec(args, env, directory)
    },
) {

    fun execute(
        command: String,
        timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
        workingDir: String = "/root",
        extraEnv: Map<String, String> = emptyMap(),
        checkCancelled: () -> Unit = {},
    ): Map<String, Any> {
        val effectiveTimeout = timeoutSeconds.coerceIn(1, MAX_TIMEOUT_SECONDS)

        var activeProcess: Process? = null
        return try {
            checkCancelled()
            val process = processStarter(
                buildProcessArgs(command, workingDir),
                buildEnvVars(extraEnv),
                File(rootfsPath).parentFile,
            )

            activeProcess = process
            // Drain stdout/stderr concurrently to avoid pipe buffer deadlock
            val stdoutFuture = CompletableFuture.supplyAsync({ readBounded(process.inputStream.bufferedReader()) }, streamReaders)
            val stderrFuture = CompletableFuture.supplyAsync({ readBounded(process.errorStream.bufferedReader()) }, streamReaders)

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(effectiveTimeout)
            while (process.isAlive && System.nanoTime() < deadline) {
                checkCancelled()
                process.waitFor(100, TimeUnit.MILLISECONDS)
            }
            checkCancelled()
            val completed = !process.isAlive

            if (!completed) {
                process.destroyForcibly()
                return mapOf(
                    "success" to false,
                    "stdout" to runCatching { stdoutFuture.get(1, TimeUnit.SECONDS) }.getOrDefault(""),
                    "stderr" to runCatching { stderrFuture.get(1, TimeUnit.SECONDS) }.getOrDefault(""),
                    "exit_code" to -1,
                    "timed_out" to true,
                )
            }

            mapOf(
                "success" to (process.exitValue() == 0),
                "stdout" to runCatching { stdoutFuture.get(2, TimeUnit.SECONDS) }.getOrDefault(""),
                "stderr" to runCatching { stderrFuture.get(2, TimeUnit.SECONDS) }.getOrDefault(""),
                "exit_code" to process.exitValue(),
                "timed_out" to false,
            )
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: Exception) {
            mapOf(
                "success" to false,
                "error" to (e.message ?: "Failed to execute command in sandbox"),
            )
        } finally {
            // Cancellation/interrupt must not abandon a live PRoot process.
            if (activeProcess?.isAlive == true) activeProcess.destroyForcibly()
        }
    }

    fun executeStreaming(
        command: String,
        workingDir: String = "/root",
        extraEnv: Map<String, String> = emptyMap(),
        onStdout: (String) -> Unit,
        onStderr: (String) -> Unit,
    ): ProotHandle {
        val process = processStarter(
            buildProcessArgs(command, workingDir),
            buildEnvVars(extraEnv),
            File(rootfsPath).parentFile,
        )
        val cancelled = AtomicBoolean(false)
        val stdoutFuture = CompletableFuture.runAsync({
            streamLines(process.inputStream.bufferedReader(), cancelled, onStdout)
        }, streamReaders)
        val stderrFuture = CompletableFuture.runAsync({
            streamLines(process.errorStream.bufferedReader(), cancelled, onStderr)
        }, streamReaders)
        return ProotHandle(process, cancelled, listOf(stdoutFuture, stderrFuture))
    }

    private fun buildProcessArgs(command: String, workingDir: String): Array<String> = arrayOf(
        prootPath,
        "--rootfs=$rootfsPath",
        "--bind=/dev",
        "--bind=/proc",
        "--bind=/sys",
        "--bind=$homePath:/root",
        "--bind=$tmpPath:/tmp",
        "-0",
        "-w", workingDir,
        "/bin/sh", "-c", command,
    )

    private fun buildEnvVars(extraEnv: Map<String, String>): Array<String> {
        val loaderPath = File(prootPath).parent.orEmpty() + "/libproot-loader.so"
        val baseEnv = arrayOf(
            "HOME=/root",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "LD_LIBRARY_PATH=$libDir",
            "PROOT_TMP_DIR=$tmpPath",
            "PROOT_LOADER=$loaderPath",
        )
        return baseEnv + extraEnv.map { (k, v) -> "$k=$v" }.toTypedArray()
    }

    private fun readBounded(reader: BufferedReader): String {
        val sb = StringBuilder()
        val buf = CharArray(8192)
        try {
            var read: Int
            while (reader.read(buf).also { read = it } != -1) {
                sb.append(buf, 0, read)
                if (sb.length >= MAX_OUTPUT_LENGTH) break
            }
            if (sb.length >= MAX_OUTPUT_LENGTH) {
                while (reader.read(buf) != -1) { /* discard */ }
            }
        } catch (_: IOException) {
            // Stream closed under us (typically destroyForcibly on timeout).
            // Return what we have so the timed_out path can surface a clean result.
        }
        return sb.toString().take(MAX_OUTPUT_LENGTH)
    }

    private fun streamLines(
        reader: BufferedReader,
        cancelled: AtomicBoolean,
        onLine: (String) -> Unit,
    ) {
        try {
            // readLine() can allocate an unbounded line (binary output, minified
            // JSON, `yes | tr -d '\n'`). Emit bounded chunks while still draining.
            val line = StringBuilder()
            while (!cancelled.get()) {
                val next = reader.read()
                if (next < 0) break
                if (next == '\n'.code) {
                    onLine(line.toString().removeSuffix("\r"))
                    line.setLength(0)
                } else {
                    line.append(next.toChar())
                    if (line.length >= MAX_OUTPUT_LENGTH) {
                        onLine(line.toString()); line.setLength(0)
                    }
                }
            }
            if (line.isNotEmpty()) onLine(line.toString())
        } catch (e: IOException) {
            if (!cancelled.get()) throw e
        } finally {
            runCatching { reader.close() }
        }
    }
}

