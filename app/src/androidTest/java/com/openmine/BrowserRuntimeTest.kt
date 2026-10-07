package com.openmine

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Tests real Android WebView networking/rendering against a fixture on the device's loopback. */
class BrowserRuntimeTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var fixture: BrowserFixture
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs get() = context.getSharedPreferences("open_mine_browser", Context.MODE_PRIVATE)

    @Before fun start() {
        prefs.edit().clear().commit()
        fixture = BrowserFixture()
    }

    @After fun stop() { fixture.close() }

    @Test fun realHtmlNavigationAndSavedStateRestoreThePageHistory() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MaterialTheme { BrowserScreen() } }
        open("/first")
        awaitTitle("First page")
        assertTrue(javascript("document.body.textContent").contains(fixture.nonce))
        javascript("document.getElementById('next').click()")
        awaitTitle("Second page")
        assertHistory("/second", back = true, forward = false)
        compose.onNodeWithContentDescription("Previous page").assertIsEnabled().performClick()
        awaitTitle("First page")
        assertHistory("/first", back = false, forward = true)
        compose.onNodeWithContentDescription("Next page").assertIsEnabled().performClick()
        awaitTitle("Second page")

        // Destroys/recreates the composable and WebView using the actual saved-state Bundle.
        // This exercises saved-state recovery, not a claim of full OS process-death coverage.
        restoration.emulateSavedInstanceStateRestore()
        awaitTitle("Second page")
        assertHistory("/second", back = true, forward = false)
        compose.onNodeWithContentDescription("Previous page").assertIsEnabled().performClick()
        awaitTitle("First page")
        assertTrue(javascript("document.body.textContent").contains(fixture.nonce))
    }

    @Test fun failedNetworkLoadsRecoverStopAndPersistJavaScriptPreference() {
        val visible = mutableStateOf(true)
        compose.setContent { MaterialTheme { if (visible.value) BrowserScreen() } }
        fixture.failRecovery = true
        open("/recover")
        awaitText("This page could not load", substring = true)
        fixture.failRecovery = false
        compose.onNodeWithText("Retry").performClick()
        awaitTitle("Recovered page")
        assertTrue(javascript("document.body.textContent").contains(fixture.nonce))

        open("/slow")
        awaitTitle("Slow page", complete = false)
        compose.onNodeWithContentDescription("Stop loading").performClick()
        compose.onNodeWithText("Page loading stopped.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Reload page").assertIsDisplayed()
        fixture.releaseSlow.countDown()

        open("/scripts")
        awaitTitle("Scripts enabled")
        compose.onNodeWithContentDescription("Browser options").performClick()
        compose.onNodeWithText("Disable JavaScript").performClick()
        awaitTitle("Scripts disabled")
        assertFalse(prefs.getBoolean("javascript", true))
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        compose.runOnIdle { visible.value = true }
        awaitTitle("Scripts disabled")
        assertEquals(fixture.url("/scripts"), prefs.getString("last_url", null))
    }

    @Test fun unsafeNavigationIsBlockedAndConfirmedClearRemovesBrowserData() {
        compose.setContent { MaterialTheme { BrowserScreen() } }
        open("/first")
        awaitTitle("First page")
        var cookie: String? = null
        compose.runOnIdle { cookie = CookieManager.getInstance().getCookie(fixture.url("/")) }
        assertTrue(cookie.orEmpty().contains("openmine_fixture"))
        typeAddress("javascript:document.title='compromised'")
        awaitText("Only web pages using HTTPS or local HTTP can be opened.")
        awaitTitle("First page")
        typeAddress("http://example.com/")
        awaitText("Remote websites require HTTPS", substring = true)
        awaitTitle("First page")

        compose.onNodeWithContentDescription("Browser options").performClick()
        compose.onNodeWithText("Clear browsing data").performClick()
        compose.onNodeWithText("Cancel").performClick()
        awaitTitle("First page")
        compose.onNodeWithContentDescription("Browser options").performClick()
        compose.onNodeWithText("Clear browsing data").performClick()
        compose.onNodeWithText("Clear data").performClick()
        awaitText("Browsing data cleared.")
        assertFalse(prefs.contains("last_url"))
        compose.runOnIdle { cookie = CookieManager.getInstance().getCookie(fixture.url("/")) }
        assertFalse(cookie.orEmpty().contains("openmine_fixture"))
        // A cleared renderer must not repopulate the old URL during its disposal.
        compose.waitForIdle()
        assertFalse(prefs.contains("last_url"))
    }

    private fun open(path: String) = typeAddress(fixture.url(path))

    private fun typeAddress(address: String) {
        compose.onNodeWithText("Web address").performTextReplacement(address)
        compose.onNodeWithContentDescription("Go to web address").performClick()
    }

    private fun awaitText(text: String, substring: Boolean = false) {
        compose.waitUntil(15_000) { compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun awaitTitle(title: String, complete: Boolean = true) {
        try {
            compose.waitUntil(15_000) {
                var loaded = false
                compose.runOnIdle { webViewOrNull()?.let { loaded = it.title == title && (!complete || it.progress == 100) } }
                loaded
            }
        } catch (failure: Throwable) {
            val details = nativeHistory()
            File(context.filesDir, "browser-runtime-failure.txt").writeText("Waiting for: $title\n$details")
            throw AssertionError("Browser did not reach '$title': $details", failure)
        }
    }

    private fun assertHistory(path: String, back: Boolean, forward: Boolean) {
        var actualUrl: String? = null
        var actualBack = false
        var actualForward = false
        compose.runOnIdle {
            val view = checkNotNull(webViewOrNull())
            val history = view.copyBackForwardList()
            actualUrl = history.currentItem?.url
            actualBack = history.currentIndex > 0
            actualForward = history.currentIndex < history.size - 1
        }
        val details = nativeHistory()
        assertEquals(details, fixture.url(path), actualUrl)
        assertEquals(details, back, actualBack)
        assertEquals(details, forward, actualForward)
        if(back)compose.onNodeWithContentDescription("Previous page").assertIsEnabled() else compose.onNodeWithContentDescription("Previous page").assertIsNotEnabled()
        if(forward)compose.onNodeWithContentDescription("Next page").assertIsEnabled() else compose.onNodeWithContentDescription("Next page").assertIsNotEnabled()
    }

    private fun nativeHistory(): String {
        var details = "No attached WebView"
        compose.runOnIdle { webViewOrNull()?.let { view ->
            val history=view.copyBackForwardList()
            details="url=${view.url}; title=${view.title}; progress=${view.progress}; index=${history.currentIndex}; entries="+
                (0 until history.size).joinToString { index -> "$index:${history.getItemAtIndex(index).url}" }
        }}
        return details
    }

    private fun javascript(script: String): String {
        val result = AtomicReference<String>()
        val finished = CountDownLatch(1)
        compose.runOnIdle {
            checkNotNull(webViewOrNull()).evaluateJavascript(script) { value -> result.set(value); finished.countDown() }
        }
        assertTrue("WebView JavaScript callback timed out", finished.await(10, TimeUnit.SECONDS))
        return result.get()
    }

    /** Called only on the Android main thread. */
    private fun webViewOrNull(): WebView? {
        fun find(view: View): WebView? {
            if (view is WebView) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
            return null
        }
        return ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
            .firstNotNullOfOrNull { find(it.window.decorView) }
    }
}

private class BrowserFixture : Closeable {
    val nonce = UUID.randomUUID().toString()
    @Volatile var failRecovery = false
    val releaseSlow = CountDownLatch(1)
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val clients = Executors.newCachedThreadPool()
    private val acceptor = Thread({
        while (!server.isClosed) {
            val client = runCatching { server.accept() }.getOrNull() ?: break
            clients.execute { runCatching { respond(client) }; runCatching { client.close() } }
        }
    }, "browser-fixture").apply { isDaemon = true; start() }

    fun url(path: String) = "http://127.0.0.1:${server.localPort}$path"

    private fun respond(socket: Socket) {
        socket.soTimeout = 5_000
        val input = socket.getInputStream().bufferedReader()
        val path = input.readLine()?.split(' ')?.getOrNull(1)?.substringBefore('?') ?: return
        while (!input.readLine().isNullOrEmpty()) Unit
        if (path == "/recover" && failRecovery) return // Real transport failure: close without a response.
        val body = when (path) {
            "/first" -> "<title>First page</title><body>$nonce <a id='next' href='/second'>Next page</a></body>"
            "/second" -> "<title>Second page</title><body>$nonce second page</body>"
            "/recover" -> "<title>Recovered page</title><body>$nonce recovered after transport failure</body>"
            "/scripts" -> "<title>Scripts disabled</title><script>document.title='Scripts enabled'</script><body>$nonce</body>"
            "/slow" -> "<title>Slow page</title><body>$nonce loading" + " ".repeat(4_096)
            else -> "<title>Fixture</title><body>$nonce</body>"
        }.toByteArray(Charsets.UTF_8)
        val unfinished = if (path == "/slow") 65_536 else 0
        val headers = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size + unfinished}\r\nConnection: close\r\nCache-Control: no-store\r\nSet-Cookie: openmine_fixture=present; SameSite=Lax\r\n\r\n"
        val output = socket.getOutputStream()
        output.write(headers.toByteArray(Charsets.US_ASCII))
        output.write(body)
        output.flush()
        if (unfinished > 0) {
            releaseSlow.await(20, TimeUnit.SECONDS)
            output.write(ByteArray(unfinished) { 32 })
            output.flush()
        }
    }

    override fun close() {
        releaseSlow.countDown()
        server.close()
        clients.shutdownNow()
        acceptor.join(1_000)
    }
}
