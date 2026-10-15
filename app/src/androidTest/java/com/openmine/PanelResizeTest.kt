package com.openmine

import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Build
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PanelResizeTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun browserAndShellResizeOnlyVerticallyAndRememberIndependentHeights() {
        assertTrue("Use a disposable emulator", Build.HARDWARE in setOf("ranchu", "goldfish") || Build.PRODUCT.contains("sdk"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val navigation = context.getSharedPreferences("open_mine", Context.MODE_PRIVATE)
        val heights = context.getSharedPreferences("open_mine_panel_sizes", Context.MODE_PRIVATE)
        val browser = context.getSharedPreferences("open_mine_browser", Context.MODE_PRIVATE)
        val restoreNavigation = rememberPreferenceKeys(navigation, "screen", "route", "animations")
        val restoreHeights = rememberPreferenceKeys(heights, "browser_height", "shell_height")
        val restoreBrowser = rememberPreferenceKeys(browser, "last_url")
        navigation.edit().putInt("screen", 8).putString("route", "").putBoolean("animations", false).commit()
        heights.edit().remove("browser_height").remove("shell_height").commit()
        // A previous browser test may have saved an address. Resizing needs no network load.
        browser.edit().remove("last_url").commit()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var originalOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                scenario.onActivity {
                    originalOrientation = it.requestedOrientation
                    it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }
                try {
                fun awaitPanel(screen: Int) = compose.waitUntil(10_000) {
                    compose.onAllNodesWithTag("utility-panel-$screen").fetchSemanticsNodes().isNotEmpty()
                }
                fun bounds(screen: Int) = compose.onNodeWithTag("utility-panel-$screen").fetchSemanticsNode().boundsInRoot
                fun navigate(screen: Int) {
                    compose.onNodeWithTag("nav-$screen").performClick()
                    awaitPanel(screen)
                }
                // This case covers the original scaled portrait HUD. Other suites cover
                // the native large-font layout; do not change global font-scale settings.
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithTag("hud-artboard").fetchSemanticsNodes().isNotEmpty()
                }
                awaitPanel(8)
                val original = bounds(8)
                val draft = "https://example.com/unsubmitted-resize-draft"
                val address = compose.onNode(hasSetTextAction() and hasText("Web address"))
                address.assertIsDisplayed().performSemanticsAction(SemanticsActions.SetText) {
                    assertTrue("Browser address must accept the draft", it(AnnotatedString(draft)))
                }
                compose.onNodeWithTag("utility-resize-8").performTouchInput {
                    swipe(center, center + Offset(0f, 60f), 500)
                }
                compose.waitForIdle()
                val smaller = bounds(8)
                assertTrue("Vertical drag did not reduce height", smaller.height < original.height - 5)
                assertEquals(original.width, smaller.width, 1f)
                assertEquals(original.left, smaller.left, 1f)
                assertEquals(original.bottom, smaller.bottom, 1f)
                address.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(draft)))
                compose.onNodeWithTag("utility-resize-8").performTouchInput {
                    swipe(centerLeft, centerRight, 500)
                }
                compose.waitForIdle()
                assertEquals(smaller.width, bounds(8).width, 1f)
                assertEquals(smaller.height, bounds(8).height, 1f)
                assertEquals(smaller.left, bounds(8).left, 1f)
                assertEquals(smaller.bottom, bounds(8).bottom, 1f)
                navigate(9)
                assertEquals("Browser height leaked into shell", original.height, bounds(9).height, 1f)
                compose.onNodeWithTag("utility-resize-9").performTouchInput {
                    swipe(center, center + Offset(0f, 35f), 500)
                }
                compose.waitForIdle()
                val shell = bounds(9)
                assertTrue(shell.height < original.height)
                assertEquals(original.width, shell.width, 1f)
                scenario.recreate()
                awaitPanel(9)
                assertEquals(shell.height, bounds(9).height, 1f)
                navigate(8)
                assertEquals(smaller.height, bounds(8).height, 1f)
                compose.onNodeWithTag("utility-resize-8").performClick()
                compose.onNodeWithText("Reset height").performClick()
                compose.waitForIdle()
                assertEquals(original.height, bounds(8).height, 1f)
                navigate(9)
                assertEquals("Browser reset affected shell", shell.height, bounds(9).height, 1f)
                } finally {
                    scenario.onActivity { it.requestedOrientation = originalOrientation }
                }
            }
        } finally {
            restoreNavigation()
            restoreHeights()
            restoreBrowser()
        }
    }
}

/** Restore only touched fixture keys, including whether a key was originally absent. */
internal fun rememberPreferenceKeys(preferences: android.content.SharedPreferences, vararg keys: String): () -> Unit {
    val original = preferences.all.filterKeys { it in keys }
    return {
        preferences.edit().apply {
            keys.forEach { key ->
                when (val value = original[key]) {
                    is String -> putString(key, value)
                    is Int -> putInt(key, value)
                    is Float -> putFloat(key, value)
                    is Boolean -> putBoolean(key, value)
                    else -> remove(key)
                }
            }
        }.commit()
        Unit
    }
}
