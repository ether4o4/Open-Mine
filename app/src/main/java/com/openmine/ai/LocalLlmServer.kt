package com.openmine.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom

/**
 * Runs llama.cpp's OpenAI-compatible server inside the app process on loopback, so built-in models are
 * reached through the same client as every other endpoint. A random per-start API key keeps other
 * apps on the phone from using the port.
 */
class LocalLlmServer(val port: Int = PORT) {
    enum class State { STOPPED, STARTING, READY, FAILED }
    private val _state = MutableStateFlow(State.STOPPED)
    val state: StateFlow<State> = _state
    @Volatile var lastError: String = ""; private set
    @Volatile var apiKey: String = ""; private set
    @Volatile var model: File? = null; private set
    private var thread: Thread? = null
    val endpoint get() = "http://127.0.0.1:$port/v1"

    /** Starts the server and blocks until /health answers, the process exits, or [timeoutMs] elapses. */
    @Synchronized fun start(gguf: File, threads: Int, key: String, contextTokens: Int = 8192, timeoutMs: Long = 180_000): Boolean {
        if (_state.value == State.READY && model == gguf) return true
        if (!NativeAi.available) { fail("Native AI library unavailable: ${NativeAi.loadError}"); return false }
        stop()
        require(gguf.isFile) { "Model file is missing: ${gguf.name}" }
        require(key.length >= 32) { "Local server key is too short" }
        apiKey = key
        model = gguf; _state.value = State.STARTING; lastError = ""
        val args = arguments(gguf, threads, contextTokens, apiKey, port)
        var exit: Int? = null
        thread = Thread({ exit = NativeAi.serverRun(args) }, "openmine-llm").apply { isDaemon = true; start() }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (exit != null || thread?.isAlive == false) { fail("Model server exited (code ${exit ?: "unknown"}). The model may not fit in memory."); return false }
            if (healthy()) { _state.value = State.READY; return true }
            Thread.sleep(400)
        }
        stop(); fail("Model did not become ready within ${timeoutMs / 1000}s."); return false
    }

    @Synchronized fun stop() {
        val running = thread ?: return
        NativeAi.serverStop()
        running.join(15_000)
        thread = null
        if (_state.value != State.FAILED) _state.value = State.STOPPED
    }

    fun healthy(): Boolean = runCatching {
        val c = URL("http://127.0.0.1:$port/health").openConnection() as HttpURLConnection
        c.connectTimeout = 1000; c.readTimeout = 2000
        try { c.responseCode == 200 } finally { c.disconnect() }
    }.getOrDefault(false)

    private fun fail(message: String) { lastError = message; _state.value = State.FAILED }

    companion object {
        const val PORT = 8181
        /** A stable per-install loopback key, excluded from backups, so a saved chat connection keeps working. */
        fun installKey(noBackupDir: File): String {
            val file = File(noBackupDir, "local-llm-key")
            file.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.length >= 32 }?.let { return it }
            val key = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
            file.writeText(key)
            return key
        }
        /** Thinking is capped so a phone answers in seconds; LFM3 still reasons briefly before tools. */
        fun arguments(gguf: File, threads: Int, contextTokens: Int, key: String, port: Int) = arrayOf(
            "llama-server", "-m", gguf.absolutePath, "--host", "127.0.0.1", "--port", port.toString(),
            "--api-key", key, "--jinja", "--ctx-size", contextTokens.toString(), "--threads", threads.coerceIn(1, 8).toString(),
            "--parallel", "1", "--no-webui", "--reasoning-budget", "384", "--alias", "local")
        /** Big cores only: on 1+3+4 Snapdragon layouts this keeps work off the efficiency cores. */
        fun defaultThreads(): Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 6)
        @Volatile private var instance: LocalLlmServer? = null
        fun get() = instance ?: synchronized(this) { instance ?: LocalLlmServer().also { instance = it } }
    }
}
