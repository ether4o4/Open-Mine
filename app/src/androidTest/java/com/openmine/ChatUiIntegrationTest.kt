package com.openmine

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Actual Chat screen, discovery, HTTP streaming and storage. Fixture text is not model inference. */
class ChatUiIntegrationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun discoverSelectSendStreamAndRestoreThroughTheProductionChatUi() {
        assertTrue("Chat UI integration requires an isolated emulator", Build.HARDWARE in setOf("ranchu", "goldfish") || Build.PRODUCT.contains("sdk"))
        runBlocking { AssistantSessionController.pauseForRecovery() }
        val uiPreferences = context.getSharedPreferences("open_mine", Context.MODE_PRIVATE)
        val legacyPreferences = context.getSharedPreferences("assistant", Context.MODE_PRIVATE)
        val originalUi = uiPreferences.all.toMap()
        val originalLegacy = legacyPreferences.all.toMap()
        val assistantDirectory = File(context.filesDir, "assistant")
        val preservedDirectory = File(context.filesDir, "assistant.chat-ui-preserved-${UUID.randomUUID()}")
        val hadAssistant = assistantDirectory.exists()
        if (hadAssistant) check(assistantDirectory.renameTo(preservedDirectory)) { "Could not preserve existing emulator assistant sessions" }
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            uiPreferences.edit().clear().putInt("screen", 12).putBoolean("animations", false).commit()
            legacyPreferences.edit().clear().commit()
            ChatFixtureServer().use { server ->
                scenario = ActivityScenario.launch(MainActivity::class.java)
                awaitTag("chat_screen")
                scrollChatTo(hasText("API base URL"))
                setFieldText(hasText("API base URL"), server.endpoint)
                assertEquals("The visible URL field must update the actual chat session", server.endpoint,
                    AssistantSessionController.get(context).state.value.session.endpoint)
                hideKeyboard(scenario!!)
                compose.waitUntil(15_000) {
                    val state = AssistantSessionController.get(context).state.value
                    state.session.endpoint == server.endpoint && !state.credentialLoading
                }
                scrollChatTo(hasText("Test connection / list models"))
                compose.waitUntil(15_000) {
                    runCatching { compose.onNodeWithText("Test connection / list models").assertIsEnabled() }.isSuccess
                }
                compose.onNodeWithText("Test connection / list models").assertIsEnabled().performClick()
                compose.waitUntil(15_000) {
                    compose.onAllNodesWithText(ChatFixtureServer.MODEL).fetchSemanticsNodes().isNotEmpty()
                }
                assertEquals("Discovery must issue a real GET /v1/models", 1, server.modelRequests.get())
                scrollChatTo(hasText(ChatFixtureServer.MODEL))
                compose.onNodeWithText(ChatFixtureServer.MODEL).performClick()
                assertEquals(ChatFixtureServer.MODEL, AssistantSessionController.get(context).state.value.session.model)
                scrollChatTo(hasTestTag("chat_settings"))
                compose.onNodeWithTag("chat_settings").performClick()
                compose.onAllNodesWithText("Hide connection settings").assertCountEquals(0)
                scrollChatTo(hasTestTag("chat_composer"))
                setFieldText(hasTestTag("chat_composer"), QUESTION)
                assertEquals("The visible composer must update the actual chat draft", QUESTION,
                    AssistantSessionController.get(context).state.value.session.current.draft)
                hideKeyboard(scenario!!)
                scrollChatTo(hasTestTag("chat_send"))
                compose.onNodeWithTag("chat_send").assertIsEnabled().performClick()
                compose.waitUntil(15_000) { server.request.get() != null }
                val payload = server.request.get()!!
                assertEquals(1, server.chatRequests.get())
                assertEquals(ChatFixtureServer.MODEL, payload.getString("model"))
                assertTrue(payload.getBoolean("stream"))
                assertFalse("Tools stay opt-in on a fresh session", payload.has("tools"))
                val messages = payload.getJSONArray("messages")
                assertTrue((0 until messages.length()).any {
                    messages.getJSONObject(it).let { message -> message.getString("role") == "user" && message.getString("content") == QUESTION }
                })
                scrollChatTo(hasText(ChatFixtureServer.PARTIAL))
                compose.onNodeWithText(ChatFixtureServer.PARTIAL).assertIsDisplayed()
                assertEquals(AssistantTurnState.STREAMING, AssistantSessionController.get(context).state.value.session.current.turns.single().state)
                scrollChatTo(hasText("Cancel request"))
                compose.onNodeWithText("Cancel request").assertIsDisplayed().assertIsEnabled().assertHasClickAction()
                compose.onNodeWithTag("chat_send").assertIsNotEnabled().assertTextContains("Request in progress")
                scrollChatTo(hasText(ChatFixtureServer.PARTIAL))
                capture("chat-real-http-streaming")

                server.release.countDown()
                compose.waitUntil(15_000) {
                    AssistantSessionController.get(context).state.value.session.current.turns.single().state == AssistantTurnState.COMPLETED
                }
                scrollChatTo(hasText(ChatFixtureServer.COMPLETE))
                compose.onNodeWithText(ChatFixtureServer.COMPLETE).assertIsDisplayed()
                val store = AssistantSessionStore(File(assistantDirectory, "sessions-v1.json"))
                compose.waitUntil(15_000) { runCatching { store.load()?.current?.turns?.singleOrNull()?.state == AssistantTurnState.COMPLETED }.getOrDefault(false) }
                val saved = store.load()!!
                assertEquals(server.endpoint, saved.endpoint)
                assertEquals(ChatFixtureServer.MODEL, saved.model)
                assertEquals(QUESTION, saved.current.turns.single().question)
                assertEquals(ChatFixtureServer.COMPLETE, saved.current.turns.single().answer)
                assertEquals("", saved.current.draft)
                assertFalse(saved.toolsEnabled)
                capture("chat-real-http-completed")
                scenario!!.recreate()
                awaitTag("chat_screen")
                scrollChatTo(hasText(ChatFixtureServer.COMPLETE))
                compose.onNodeWithText(ChatFixtureServer.COMPLETE).assertIsDisplayed()
                assertEquals("Recreation must not resend a completed request", 1, server.chatRequests.get())
                server.failure.get()?.let { throw AssertionError("HTTP fixture failed", it) }
                val evidence = JSONObject().put("scope", "Production Chat UI and local HTTP protocol fixture; not LLM inference")
                    .put("discovery_requests", server.modelRequests.get()).put("generation_requests", server.chatRequests.get())
                    .put("streamed_partial_rendered", true).put("completed_turn_persisted", true).put("recreation_preserved_turn", true)
                File(evidenceDirectory(), "chat-ui-protocol.json").writeText(evidence.toString(2))
            }
        } finally {
            scenario?.close()
            val controller = AssistantSessionController.get(context)
            controller.cancel("Cancelled by isolated chat UI fixture cleanup")
            val stopDeadline = SystemClock.elapsedRealtime() + 10_000
            while (controller.state.value.busy && SystemClock.elapsedRealtime() < stopDeadline) SystemClock.sleep(20)
            check(!controller.state.value.busy) { "Chat did not stop; preserved sessions remain at $preservedDirectory" }
            runBlocking { AssistantSessionController.pauseForRecovery() }
            assistantDirectory.deleteRecursively()
            if (hadAssistant) check(preservedDirectory.renameTo(assistantDirectory)) { "Preserved emulator assistant sessions remain at $preservedDirectory" }
            restorePreferences(uiPreferences, originalUi)
            restorePreferences(legacyPreferences, originalLegacy)
        }
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun scrollChatTo(matcher: SemanticsMatcher) {
        awaitTag("chat_screen")
        compose.waitUntil(15_000) {
            runCatching {
                compose.onNodeWithTag("chat_screen").performScrollToNode(matcher)
                compose.onNode(matcher).assertIsDisplayed()
            }.isSuccess
        }
        compose.onNode(matcher).assertIsDisplayed()
    }

    private fun setFieldText(matcher: SemanticsMatcher, text: String) {
        // The standard focus-based helper did not update the restored panel's fields.
        // Drive the actual TextField callback; this does not verify the physical IME.
        compose.onNode(matcher).performSemanticsAction(SemanticsActions.SetText) { action ->
            assertTrue("The visible text field must accept the edit", action(AnnotatedString(text)))
        }
        compose.waitUntil(10_000) { runCatching { compose.onNode(matcher).assertTextContains(text) }.isSuccess }
        compose.onNode(matcher).assertTextContains(text)
    }

    private fun hideKeyboard(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { activity ->
            (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
            activity.currentFocus?.clearFocus()
        }
        compose.waitForIdle()
    }

    private fun evidenceDirectory() = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "hud-verification").apply { mkdirs() }
    private fun capture(name: String) {
        val image = compose.onNodeWithTag("hud-root").captureToImage().asAndroidBitmap()
        File(evidenceDirectory(), "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun restorePreferences(preferences: SharedPreferences, values: Map<String, *>) {
        val edit = preferences.edit().clear()
        values.forEach { (key, value) -> when (value) {
            is String -> edit.putString(key, value)
            is Boolean -> edit.putBoolean(key, value)
            is Int -> edit.putInt(key, value)
            is Long -> edit.putLong(key, value)
            is Float -> edit.putFloat(key, value)
            is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
        } }
        check(edit.commit())
    }

    private class ChatFixtureServer : Closeable {
        private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val endpoint = "http://127.0.0.1:${server.localPort}/v1"
        val modelRequests = AtomicInteger()
        val chatRequests = AtomicInteger()
        val request = AtomicReference<JSONObject?>(null)
        val failure = AtomicReference<Throwable?>(null)
        val release = CountDownLatch(1)
        private val worker = Thread {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.use { client -> try {
                    client.soTimeout = 10_000
                    val input = client.getInputStream()
                    val requestLine = line(input)
                    var length = 0
                    while (true) {
                        val header = line(input)
                        if (header.isEmpty()) break
                        if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toInt()
                    }
                    val out = client.getOutputStream()
                    when (requestLine) {
                        "GET /v1/models HTTP/1.1" -> {
                            modelRequests.incrementAndGet()
                            // Two choices require an actual model-selection tap rather than
                            // accidentally passing through the single-model auto-selection.
                            val body = JSONObject().put("data", JSONArray().put(JSONObject().put("id", MODEL))
                                .put(JSONObject().put("id", "another-ui-fixture"))).toString().toByteArray()
                            out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            out.write(body); out.flush()
                        }
                        "POST /v1/chat/completions HTTP/1.1" -> {
                            require(length in 1..1024 * 1024)
                            val body = ByteArray(length)
                            var offset = 0
                            while (offset < length) { val count = input.read(body, offset, length - offset); check(count > 0); offset += count }
                            chatRequests.incrementAndGet()
                            request.set(JSONObject(String(body, Charsets.UTF_8)))
                            out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray())
                            out.write(event(PARTIAL).toByteArray()); out.flush()
                            check(release.await(90, TimeUnit.SECONDS)) { "UI never acknowledged the streamed partial" }
                            out.write(event(SUFFIX, "stop").toByteArray())
                            out.write("data: [DONE]\n\n".toByteArray()); out.flush()
                        }
                        else -> error("Unexpected request: $requestLine")
                    }
                } catch (error: Throwable) { failure.compareAndSet(null, error) } }
            }
        }.apply { isDaemon = true; start() }

        private fun event(text: String, finish: String? = null) = "data: " + JSONObject().put("choices", JSONArray().put(
            JSONObject().put("delta", JSONObject().put("content", text)).put("finish_reason", finish ?: JSONObject.NULL))) + "\n\n"
        private fun line(input: InputStream): String {
            val value = StringBuilder()
            while (true) { val next = input.read(); check(next >= 0); if (next == 10) break; value.append(next.toChar()); check(value.length < 16384) }
            return value.toString().removeSuffix("\r")
        }
        override fun close() { release.countDown(); server.close(); worker.join(1000) }
        companion object {
            const val MODEL = "ui-protocol-fixture"
            const val PARTIAL = "Fixture stream reached the real chat UI"
            const val SUFFIX = " and completed."
            const val COMPLETE = PARTIAL + SUFFIX
        }
    }
    companion object { const val QUESTION = "Check this UI request: café → λ" }
}
