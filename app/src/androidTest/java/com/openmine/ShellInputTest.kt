package com.openmine

import android.content.Context
import android.os.Build
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.openmine.sandbox.OpenMineRuntime
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** UI recall/persistence checks require no Linux download and execute no history entry. */
class ShellInputTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun arrowsRestoreTheDraftAndRecallPersistedCommandsAfterActivityRecreation() {
        assertTrue("Use a disposable emulator", Build.HARDWARE in setOf("ranchu", "goldfish") || Build.PRODUCT.contains("sdk"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val navigation = context.getSharedPreferences("open_mine", Context.MODE_PRIVATE)
        val preferences = context.getSharedPreferences("open_mine_runtime_ui", Context.MODE_PRIVATE)
        val restoreNavigation = rememberPreferenceKeys(navigation, "screen", "route", "animations")
        val restoreInput = rememberPreferenceKeys(preferences, "command_history", "command_draft")
        val commands = listOf("printf 'first history fixture'", "printf 'second history fixture'")
        val savedHistory = JSONArray(commands).toString()
        val draft = "printf 'unfinished café → λ'"
        navigation.edit().putInt("screen", 9).putString("route", "").putBoolean("animations", false).commit()
        preferences.edit().putString("command_history", savedHistory).putString("command_draft", draft).commit()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                fun awaitShell() {
                    compose.waitUntil(10_000) {
                        compose.onAllNodesWithTag("utility-panel-9").fetchSemanticsNodes().isNotEmpty() &&
                            !OpenMineRuntime.get(context).busy.value
                    }
                }
                fun scrollTo(tag: String): SemanticsNodeInteraction {
                    compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag(tag))
                    return compose.onNodeWithTag(tag).assertIsDisplayed()
                }
                fun assertCommand(expected: String) {
                    scrollTo("shell-command").assert(SemanticsMatcher.expectValue(
                        SemanticsProperties.EditableText, AnnotatedString(expected)))
                    assertEquals(expected, preferences.getString("command_draft", null))
                }
                fun recall(previous: Boolean) = scrollTo(if (previous) "shell-history-up" else "shell-history-down")
                    .assertIsEnabled().performClick()

                awaitShell()
                assertCommand(draft)
                scrollTo("shell-history-down").assertIsNotEnabled()
                recall(true)
                assertCommand(commands[1])
                recall(true)
                assertCommand(commands[0])
                scrollTo("shell-history-up").assertIsNotEnabled()
                recall(false)
                assertCommand(commands[1])
                recall(false)
                assertCommand(draft)
                scrollTo("shell-history-down").assertIsNotEnabled()

                scenario.recreate()
                awaitShell()
                assertCommand(draft)
                recall(true)
                assertCommand(commands[1])
                recall(false)
                assertCommand(draft)

                // Rejected submissions must not erase the draft or append to history.
                // This exceeds the command limit whether Linux has been set up or not.
                val rejected = "x".repeat(8193)
                scrollTo("shell-command").performSemanticsAction(SemanticsActions.SetText) {
                    assertTrue(it(AnnotatedString(rejected)))
                }
                scrollTo("shell-command").performImeAction()
                assertCommand(rejected)
                assertEquals(savedHistory, preferences.getString("command_history", null))
            }
        } finally {
            restoreNavigation()
            restoreInput()
        }
    }
}
