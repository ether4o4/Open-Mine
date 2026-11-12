package com.openmine

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import com.openmine.ai.LocalLlmServer
import com.openmine.ai.NativeAi
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The in-process llama.cpp server (used for LFM3) on a real Android device or emulator, with the real GGUF
 * the native-inference CI phase pushes. Excluded from ordinary fixture runs.
 */
class BuiltInServerTest {
    @Test(timeout = 1_200_000)
    fun embeddedServerServesToolCallsRejectsWrongKeysStopsAndRestarts() {
        val args = InstrumentationRegistry.getArguments()
        check(args.getString("openMineRealGguf") == "true") { "Run BuiltInServerTest only in the native-inference CI phase." }
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val model = File(checkNotNull(args.getString("openMineGgufPath")))
        assertTrue("CI must push the GGUF first: $model", model.isFile)
        assertNull("Native library must load: ${NativeAi.loadError}", NativeAi.loadError)
        val evidence = JSONObject().put("abis", JSONArray(Build.SUPPORTED_ABIS.toList())).put("api", Build.VERSION.SDK_INT)
            .put("model", model.name)
        val server = LocalLlmServer(port = 18183)
        val key = LocalLlmServer.installKey(target.noBackupFilesDir)
        try {
            repeat(2) { round ->
                val started = System.currentTimeMillis()
                assertTrue("Round $round: ${server.lastError}", server.start(model, 2, key, contextTokens = 2048, timeoutMs = 600_000))
                evidence.put("startMs$round", System.currentTimeMillis() - started)
                val body = JSONObject().put("model", "local").put("max_tokens", 48).put("temperature", 0)
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Reply with the word ready.")))
                val (code, reply) = post(server.endpoint + "/chat/completions", body.toString(), key)
                assertEquals(reply, 200, code)
                val message = JSONObject(reply).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
                assertTrue("Model returned no output: $reply", message.optString("content").isNotBlank() || message.optString("reasoning_content").isNotBlank())
                evidence.put("reply$round", message.optString("content").take(200))
                assertEquals("Wrong key must be rejected", 401, post(server.endpoint + "/chat/completions", body.toString(), "x".repeat(48)).first)
                server.stop()
                assertFalse(NativeAi.serverRunning()); assertFalse(server.healthy())
            }
            evidence.put("status", "passed")
        } catch (e: Throwable) { evidence.put("status", "failed").put("error", e.toString()); throw e }
        finally {
            server.stop()
            File(checkNotNull(target.getExternalFilesDir(null)), "verification").apply { mkdirs() }.resolve("builtin-server.json").writeText(evidence.toString(2))
        }
    }

    private fun post(url: String, body: String, key: String): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 5000; c.readTimeout = 600_000
            c.setRequestProperty("Content-Type", "application/json"); c.setRequestProperty("Authorization", "Bearer $key")
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            code to ((if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: "")
        } finally { c.disconnect() }
    }
}
