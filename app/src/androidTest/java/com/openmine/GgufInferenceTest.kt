package com.openmine

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.openmine.sandbox.OpenMineRuntime
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Live Android engine/model verification. This class is excluded from ordinary fixture tests. */
class GgufInferenceTest {
    @Test(timeout = 2_700_000)
    fun productionRuntimeImportsServesStreamsCancelsAndStopsARealGguf() {
        val args = InstrumentationRegistry.getArguments()
        check(args.getString("openMineRealGguf") == "true") {
            "Run GgufInferenceTest only in its explicit native-inference CI phase, after NativeRuntimeTest."
        }
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(checkNotNull(target.getExternalFilesDir(null)), "verification").apply { mkdirs() }
        val evidenceFile = File(directory, "gguf-inference.json")
        val runtime = OpenMineRuntime.get(target)
        val runtimeHome = File(target.filesDir, "linux-sandbox/home/.morsvitaest/llm")
        val engine = File(runtimeHome, "bin/llama-server")
        val evidence = JSONObject()
            .put("status", "started").put("startedAtEpochMillis", System.currentTimeMillis())
            .put("testedCommit", args.getString("openMineTestedCommit") ?: JSONObject.NULL)
            .put("package", BuildConfig.APPLICATION_ID).put("versionName", BuildConfig.VERSION_NAME)
            .put("versionCode", BuildConfig.VERSION_CODE).put("androidApi", Build.VERSION.SDK_INT)
            .put("androidAbis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            .put("deviceModel", Build.MODEL).put("deviceFingerprint", Build.FINGERPRINT)
            .put("inferenceLocation", "Actual Android runtime: CPU llama-server in the app's PRoot/Alpine sandbox")
            .put("scope", "GGUF import, engine health, real streamed inference, cancellation and stop; emulator timings are not physical-phone benchmarks")
            .put("endpoint", ModelEndpoint.BUILT_IN)
        var serverMayBeRunning = false
        try {
            awaitRuntime(runtime, "initial shell verification", 30_000)
            check(runtime.ready) { "Native shell is not verified. Run NativeRuntimeTest setup first. ${runtime.status.value}" }

            val input = File(checkNotNull(args.getString("openMineGgufPath")) { "CI must provide a real, downloaded GGUF model path." }).canonicalFile
            val expectedHash = checkNotNull(args.getString("openMineGgufSha256")) { "CI must provide the GGUF SHA-256." }.lowercase()
            check(Regex("[a-f0-9]{64}").matches(expectedHash)) { "GGUF checksum argument is invalid." }
            val allowedRoots = listOfNotNull(target.filesDir, target.cacheDir, target.getExternalFilesDir(null)).map { it.canonicalPath + File.separator }
            check(allowedRoots.any { input.path.startsWith(it) }) { "The test model must be in Open Mine's private or app-specific files." }
            check(input.isFile && input.canRead() && input.length() in 33..2L * 1024 * 1024 * 1024) { "The real GGUF model is absent, unreadable, truncated or too large for this test." }
            val modelHash = sha256(input)
            assertEquals("The Android input must match the host's verified model", expectedHash, modelHash)
            evidence.put("modelInput", JSONObject().put("path", input.path).put("bytes", input.length())
                .put("sha256", modelHash).put("source", args.getString("openMineGgufSource") ?: JSONObject.NULL))
            checkpoint(evidenceFile, evidence)

            val buildStarted = SystemClock.elapsedRealtime()
            val reusedEngine = engine.isFile
            runtime.engine("provision")
            awaitRuntime(runtime, "engine provisioning", 2_400_000)
            check(engine.isFile && engine.canExecute()) { "Engine provisioning did not produce an executable llama-server. ${runtime.output.value}" }
            runtime.command("/root/.morsvitaest/llm/bin/llama-server --version")
            awaitRuntime(runtime, "engine version probe", 30_000)
            check(runtime.status.value == "Command finished") { "The actual Android engine version probe failed. ${runtime.output.value}" }
            evidence.put("engine", JSONObject().put("sha256", sha256(engine)).put("bytes", engine.length())
                .put("versionProbe", runtime.output.value.takeLast(8_000))
                .put("sourceRevision", sourceRevision(File(runtimeHome, "build/llama.cpp/.git")) ?: JSONObject.NULL)
                .put("existedBeforeProvisioning", reusedEngine)
                .put("provisionElapsedMillis", SystemClock.elapsedRealtime() - buildStarted))
            checkpoint(evidenceFile, evidence)

            val modelsBefore = runtime.models.value.toSet()
            runtime.importModel(Uri.fromFile(input))
            awaitRuntime(runtime, "real GGUF import", 180_000)
            val added = runtime.models.value.toSet() - modelsBefore
            assertEquals("Import must add exactly one actual model", 1, added.size)
            val importedName = added.single()
            val importedFile = File(runtimeHome, "models/$importedName")
            assertEquals("Production import must preserve every GGUF byte", modelHash, sha256(importedFile))
            evidence.put("import", JSONObject().put("status", "passed").put("storedName", importedName).put("sha256", sha256(importedFile)))
            checkpoint(evidenceFile, evidence)

            serverMayBeRunning = true
            val serveStarted = SystemClock.elapsedRealtime()
            runtime.engine("serve", importedName)
            awaitRuntime(runtime, "Android model startup and health check", 350_000)
            assertTrue("Runtime must report actual HTTP-verified health", runtime.modelHealthy.value)
            val health = getJson("http://127.0.0.1:8080/health")
            assertEquals("ok", health.getString("status"))
            val models = getJson("http://127.0.0.1:8080/v1/models")
            assertTrue("The actual server must expose the configured local alias",
                (0 until models.getJSONArray("data").length()).any { models.getJSONArray("data").getJSONObject(it).optString("id") == "local" })
            val serverMetadata = JSONObject(File(runtimeHome, "run/server.json").readText())
            assertEquals("The running engine must load the imported real model", importedName, serverMetadata.getString("model"))
            evidence.put("startup", JSONObject().put("status", "passed").put("health", health).put("models", models)
                .put("ownedServer", serverMetadata).put("elapsedMillis", SystemClock.elapsedRealtime() - serveStarted))
            checkpoint(evidenceFile, evidence)

            withIsolatedLibrary(target) { library -> verifyStreamingAndCancellation(library, evidence) }
            checkpoint(evidenceFile, evidence)

            runtime.engine("stop")
            awaitRuntime(runtime, "model stop", 30_000)
            assertFalse("The production runtime must clear model readiness after stop", runtime.modelHealthy.value)
            assertFalse("The stopped Android server must no longer answer HTTP requests", serverAnswersHttp())
            assertFalse("Owned server PID metadata must be removed on stop", File(runtimeHome, "run/server.pid").exists())
            serverMayBeRunning = false
            evidence.put("stop", JSONObject().put("status", "passed").put("healthEndpointStopped", true))
            evidence.put("status", "passed")
        } catch (failure: Throwable) {
            evidence.put("status", "failed").put("failure", "${failure.javaClass.name}: ${failure.message}")
            throw failure
        } finally {
            if (runtime.busy.value) {
                runtime.cancel()
                runCatching { awaitRuntime(runtime, "failure cleanup cancellation", 30_000, allowCancelled = true) }
                    .onFailure { evidence.put("cancellationCleanupError", it.message) }
            }
            if (serverMayBeRunning && !runtime.busy.value) runCatching {
                runtime.engine("stop")
                awaitRuntime(runtime, "failure cleanup stop", 30_000)
            }.onFailure { evidence.put("stopCleanupError", it.message) }
            evidence.put("finalRuntimeStatus", runtime.status.value).put("runtimeOutputTail", runtime.output.value.takeLast(20_000))
            File(runtimeHome, "logs/server.log").takeIf { it.isFile }?.let { evidence.put("serverLogTail", tail(it, 20_000)) }
            File(runtimeHome, "logs/cmake.log").takeIf { it.isFile }?.let { evidence.put("engineBuildLogTail", tail(it, 12_000)) }
            evidence.put("finishedAtEpochMillis", System.currentTimeMillis())
            checkpoint(evidenceFile, evidence)
        }
    }

    private fun verifyStreamingAndCancellation(context: Context, evidence: JSONObject) {
        val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "real-android-gguf-test").apply { isDaemon = true } }
        val completionControl = ModelRequestControl(180_000)
        val cancellationControl = ModelRequestControl(180_000)
        try {
            var sseUpdates = 0
            val partials = mutableListOf<String>()
            val started = SystemClock.elapsedRealtime()
            val reply = executor.submit<ModelClient.Reply> {
                ModelClient.ask(context, ModelEndpoint.BUILT_IN, "local", "",
                    "Reply with exactly this sentence: Open Mine runs a real local GGUF model on Android.",
                    control = completionControl, allowTools = false,
                    onProgress = { if (it.startsWith("Receiving model response")) sseUpdates++ },
                    onText = { if (it.isNotBlank() && partials.lastOrNull() != it) partials.add(it) })
            }.get(190, TimeUnit.SECONDS)
            assertTrue("A real Android GGUF engine must produce nonempty model text", reply.text.isNotBlank())
            assertTrue("Production Android SSE decoding must observe incremental tokens", sseUpdates >= 2 && partials.size >= 2)
            assertTrue("Streaming updates must match the actual completed answer", partials.all { reply.text.startsWith(it.trim()) })
            assertFalse("The concise verification answer must finish before its token cap", reply.tokenLimited)
            assertTrue("This inference check must not run tools", reply.toolResults.isEmpty())
            evidence.put("streaming", JSONObject().put("status", "passed").put("actualOutput", reply.text)
                .put("sseTextUpdates", sseUpdates).put("distinctPartialUpdates", partials.size)
                .put("elapsedMillis", SystemClock.elapsedRealtime() - started))

            val cancelledAt = AtomicLong(0)
            var cancelPartial = ""
            var actualSse = false
            val pending = executor.submit<ModelClient.Reply> {
                ModelClient.ask(context, ModelEndpoint.BUILT_IN, "local", "",
                    "Write 100 numbered engineering note-taking suggestions, explaining each suggestion in detail.",
                    control = cancellationControl, allowTools = false,
                    onProgress = { if (it.startsWith("Receiving model response")) actualSse = true },
                    onText = { text ->
                        if (text.isNotBlank() && cancelledAt.compareAndSet(0, SystemClock.elapsedRealtime())) {
                            cancelPartial = text
                            cancellationControl.cancelRequest("Cancelled by actual Android GGUF test after streamed model output")
                        }
                    })
            }
            var failure: Throwable? = null
            var completed = false
            try { pending.get(190, TimeUnit.SECONDS); completed = true }
            catch (failed: ExecutionException) { failure = failed.cause ?: failed }
            val finished = SystemClock.elapsedRealtime()
            assertFalse("Cancelled inference must not report a completed answer", completed)
            assertNotNull("Cancelled inference must terminate the production request", failure)
            assertTrue("Cancellation must follow real Android model SSE output", actualSse && cancelPartial.isNotBlank() && cancelledAt.get() > 0)
            assertTrue("The request must retain its actual cancellation reason", cancellationControl.reason?.startsWith("Cancelled by actual Android GGUF") == true)
            val latency = finished - cancelledAt.get()
            assertTrue("Cancelling an active Android model request took ${latency}ms", latency in 0..10_000)
            evidence.put("cancellation", JSONObject().put("status", "passed").put("partialBeforeCancellation", cancelPartial)
                .put("reason", cancellationControl.reason).put("termination", failure!!.javaClass.name).put("cancelLatencyMillis", latency))
        } finally {
            completionControl.cancelRequest("Android GGUF verification cleanup")
            cancellationControl.cancelRequest("Android GGUF verification cleanup")
            executor.shutdownNow()
        }
    }

    private fun awaitRuntime(runtime: OpenMineRuntime, label: String, timeoutMillis: Long, allowCancelled: Boolean = false) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (runtime.busy.value && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
        check(!runtime.busy.value) { "$label exceeded its ${timeoutMillis / 1000}-second budget. ${runtime.status.value}\n${runtime.output.value.takeLast(8_000)}" }
        check(!runtime.status.value.startsWith("Failed:") && (allowCancelled || runtime.status.value != "Cancelled")) {
            "$label failed: ${runtime.status.value}\n${runtime.output.value.takeLast(12_000)}"
        }
    }

    private fun getJson(address: String, timeout: Int = 15_000): JSONObject {
        val connection = URL(address).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = timeout; connection.readTimeout = timeout; connection.instanceFollowRedirects = false
            check(connection.responseCode == 200) { "Actual model endpoint returned HTTP ${connection.responseCode}." }
            return connection.inputStream.bufferedReader().use { reader ->
                val value = StringBuilder(); val buffer = CharArray(4_096)
                while (true) { val count = reader.read(buffer); if (count < 0) break; value.append(buffer, 0, count); check(value.length <= 1_048_576) { "Model metadata exceeds limit." } }
                JSONObject(value.toString())
            }
        } finally { connection.disconnect() }
    }

    private fun serverAnswersHttp(): Boolean {
        val connection = URL("http://127.0.0.1:8080/health").openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 2_000; connection.readTimeout = 2_000; connection.instanceFollowRedirects = false
            runCatching { connection.responseCode > 0 }.getOrDefault(false)
        } finally { connection.disconnect() }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val bytes = ByteArray(65_536); while (true) { val count = input.read(bytes); if (count < 0) break; digest.update(bytes, 0, count) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sourceRevision(git: File): String? = runCatching {
        val head = File(git, "HEAD").readText().trim()
        if (Regex("[a-f0-9]{40}").matches(head)) head
        else {
            val ref = head.removePrefix("ref: ")
            val loose = File(git, ref)
            if (loose.isFile) loose.readText().trim()
            else File(git, "packed-refs").readLines().firstOrNull { it.endsWith(" $ref") }?.substringBefore(' ')
        }
    }.getOrNull()

    private fun tail(file: File, limit: Int): String = java.io.RandomAccessFile(file, "r").use {
        val size = minOf(it.length(), limit.toLong()).toInt(); it.seek(it.length() - size)
        val bytes = ByteArray(size); it.readFully(bytes); String(bytes, Charsets.UTF_8)
    }

    private fun checkpoint(file: File, evidence: JSONObject) { file.writeText(evidence.toString(2) + "\n") }

    private fun withIsolatedLibrary(target: Context, action: (Context) -> Unit) {
        val prefix = "real-gguf-library-${UUID.randomUUID()}"
        val directory = File(target.cacheDir, prefix).apply { mkdirs() }
        val context = object : ContextWrapper(target) {
            override fun getFilesDir() = directory
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = target.getSharedPreferences("$prefix-$name", mode)
        }
        try { action(context) } finally { directory.deleteRecursively(); target.deleteSharedPreferences("$prefix-open_mine") }
    }
}
