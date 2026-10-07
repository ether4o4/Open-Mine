package com.openmine

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Explicit live integration phase only. No fixtures or inference mocks are used here.
 * CI runs host Ollama and forwards it with adb reverse; inference is NOT on the Android CPU.
 * Exclude this class from the ordinary suite, then invoke with openMineRealOllama=true.
 */
class OllamaRuntimeTest {
    @Test(timeout = 420_000)
    fun androidClientStreamsFromRealOllamaAndCancelsAnActiveGeneration() {
        val arguments = InstrumentationRegistry.getArguments()
        check(arguments.getString("openMineRealOllama") == "true") {
            "Live Ollama verification requires its explicit CI phase. Exclude OllamaRuntimeTest from fixture tests."
        }
        val model = arguments.getString("openMineOllamaModel") ?: "qwen2.5:0.5b"
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(checkNotNull(target.getExternalFilesDir(null)), "verification").apply { mkdirs() }
        val evidenceFile = File(directory, "ollama-runtime.json")
        val evidence = JSONObject()
            .put("status", "started")
            .put("startedAtEpochMillis", System.currentTimeMillis())
            .put("testedCommit", arguments.getString("openMineTestedCommit") ?: JSONObject.NULL)
            .put("package", BuildConfig.APPLICATION_ID)
            .put("versionName", BuildConfig.VERSION_NAME)
            .put("versionCode", BuildConfig.VERSION_CODE)
            .put("androidApi", Build.VERSION.SDK_INT)
            .put("androidAbis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            .put("clientLocation", "Android emulator: production ModelClient and ChatStream")
            .put("inferenceLocation", "CI host Ollama via adb reverse tcp:11434; not phone GGUF inference")
            .put("endpoint", ModelEndpoint.OLLAMA)
            .put("requestedModel", model)
        try {
            val version = getJson("http://127.0.0.1:11434/api/version")
            val tags = getJson("http://127.0.0.1:11434/api/tags").getJSONArray("models")
            val installed = (0 until tags.length()).map { tags.getJSONObject(it) }
                .singleOrNull { it.optString("name") == model || it.optString("model") == model }
            assertNotNull("The requested model must actually be installed in host Ollama", installed)
            val digest = installed!!.getString("digest")
            assertTrue("Ollama must report a model digest", digest.isNotBlank())
            assertTrue("Ollama must report its actual server version", version.getString("version").isNotBlank())
            arguments.getString("openMineOllamaDigest")?.let { expected ->
                check(expected == digest) { "Android observed a different Ollama model digest from the host evidence." }
            }
            evidence.put("ollamaVersion", version.getString("version"))
                .put("modelDigest", digest).put("modelMetadata", installed)

            withIsolatedLibrary(target) { context ->
                val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "live-ollama-test").apply { isDaemon = true } }
                val completionControl = ModelRequestControl(180_000)
                val cancelControl = ModelRequestControl(180_000)
                try {
                    val partials = mutableListOf<String>()
                    var observedSse = false
                    var sseUpdates = 0
                    val completionStarted = SystemClock.elapsedRealtime()
                    val completion = executor.submit<ModelClient.Reply> {
                        ModelClient.ask(context, ModelEndpoint.OLLAMA, model, "",
                            "Write two sentences explaining why a local workspace should save work before closing. Use at least 30 words.",
                            control = completionControl,
                            onProgress = { if (it.startsWith("Receiving model response")) { observedSse = true; sseUpdates++ } },
                            onText = { if (it.isNotBlank() && partials.lastOrNull() != it) partials.add(it) },
                            allowTools = false)
                    }.get(190, TimeUnit.SECONDS)
                    assertTrue("A live model answer must contain text", completion.text.isNotBlank())
                    assertTrue("The Android client must decode an actual SSE response", observedSse)
                    assertTrue("The answer must arrive in multiple SSE text updates", sseUpdates >= 2 && partials.size >= 2)
                    assertTrue("Every streamed update must be a prefix of the model answer", partials.all { completion.text.startsWith(it.trim()) })
                    assertTrue("Tools were disabled for this live inference check", completion.toolResults.isEmpty())
                    evidence.put("streaming", JSONObject()
                        .put("status", "passed").put("actualOutput", completion.text)
                        .put("distinctPartialUpdates", partials.size)
                        .put("sseTextUpdates", sseUpdates)
                        .put("elapsedMillis", SystemClock.elapsedRealtime() - completionStarted)
                        .put("tokenLimited", completion.tokenLimited))

                    val cancelledAt = AtomicLong(0)
                    var cancellationPartial = ""
                    var cancellationSawSse = false
                    val cancellationStarted = SystemClock.elapsedRealtime()
                    val pending = executor.submit<ModelClient.Reply> {
                        ModelClient.ask(context, ModelEndpoint.OLLAMA, model, "",
                            "Write a detailed numbered list of 100 practical ways to organize engineering project notes, with an explanation for each item.",
                            control = cancelControl,
                            onProgress = { if (it.startsWith("Receiving model response")) cancellationSawSse = true },
                            onText = { partial ->
                                if (partial.isNotBlank() && cancelledAt.compareAndSet(0, SystemClock.elapsedRealtime())) {
                                    cancellationPartial = partial
                                    cancelControl.cancelRequest("Cancelled by live Android integration test after first streamed token")
                                }
                            }, allowTools = false)
                    }
                    var failure: Throwable? = null
                    var successfulReply = false
                    try { pending.get(190, TimeUnit.SECONDS); successfulReply = true }
                    catch (failed: ExecutionException) { failure = failed.cause ?: failed }
                    val finishedAt = SystemClock.elapsedRealtime()
                    assertFalse("A cancelled generation must not return a successful Reply", successfulReply)
                    assertNotNull("Cancellation must terminate the production request", failure)
                    assertTrue("Cancellation must happen after genuine SSE model output", cancellationSawSse && cancellationPartial.isNotBlank() && cancelledAt.get() > 0)
                    assertTrue("The production cancellation control must retain the reason", cancelControl.reason?.startsWith("Cancelled by live Android") == true)
                    val cancelLatency = finishedAt - cancelledAt.get()
                    assertTrue("Active request cancellation took ${cancelLatency}ms", cancelLatency in 0..10_000)
                    evidence.put("cancellation", JSONObject()
                        .put("status", "passed").put("partialBeforeCancellation", cancellationPartial)
                        .put("reason", cancelControl.reason).put("termination", failure!!.javaClass.name)
                        .put("cancelLatencyMillis", cancelLatency)
                        .put("elapsedMillis", finishedAt - cancellationStarted))
                } finally {
                    completionControl.cancelRequest("Live integration cleanup")
                    cancelControl.cancelRequest("Live integration cleanup")
                    executor.shutdownNow()
                }
            }
            evidence.put("status", "passed")
        } catch (failure: Throwable) {
            evidence.put("status", "failed").put("failure", "${failure.javaClass.name}: ${failure.message}")
            throw failure
        } finally {
            evidence.put("finishedAtEpochMillis", System.currentTimeMillis())
            evidenceFile.writeText(evidence.toString(2) + "\n")
        }
    }

    private fun getJson(address: String): JSONObject {
        val connection = URL(address).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false
            check(connection.responseCode == 200) { "Live Ollama metadata endpoint returned HTTP ${connection.responseCode}." }
            val body = connection.inputStream.bufferedReader().use { reader ->
                val output = StringBuilder()
                val buffer = CharArray(4_096)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    output.append(buffer, 0, count)
                    check(output.length <= 1_048_576) { "Ollama metadata exceeded the test's size limit." }
                }
                output.toString()
            }
            return JSONObject(body)
        } finally { connection.disconnect() }
    }

    private fun withIsolatedLibrary(target: Context, action: (Context) -> Unit) {
        val prefix = "real-ollama-test-${UUID.randomUUID()}"
        val directory = File(target.cacheDir, prefix).apply { mkdirs() }
        val context = object : ContextWrapper(target) {
            override fun getFilesDir() = directory
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                target.getSharedPreferences("$prefix-$name", mode)
        }
        try { action(context) }
        finally { directory.deleteRecursively(); target.deleteSharedPreferences("$prefix-open_mine") }
    }
}
