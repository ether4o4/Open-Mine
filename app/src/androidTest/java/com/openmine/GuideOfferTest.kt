package com.openmine

import android.content.Context
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class GuideOfferTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun freshInstallKeepsTheHudAndOffersTheGuideOnce() {
        assertTrue("Use a disposable emulator", Build.HARDWARE in setOf("ranchu", "goldfish") || Build.PRODUCT.contains("sdk"))
        val prefs = InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("open_mine", Context.MODE_PRIVATE)
        val saved = prefs.all.toMap()
        prefs.edit().clear().putBoolean("animations", false).commit()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.waitUntil(15_000) { compose.onAllNodesWithTag("hud-root").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("guide-offer").assertExists()
                compose.onNodeWithTag("guide-offer-open").performClick()
                compose.waitUntil(15_000) { compose.onAllNodesWithText("OPEN MINE GUIDE").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("guide-steps").assertExists()
                compose.onNodeWithText("Back").performClick()
                compose.waitUntil(15_000) { compose.onAllNodesWithTag("hud-root").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("guide-offer").assertDoesNotExist()
                scenario.recreate()
                compose.waitUntil(15_000) { compose.onAllNodesWithTag("hud-root").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("guide-offer").assertDoesNotExist()
            }
        } finally {
            val edit = prefs.edit().clear()
            saved.forEach { (k, v) -> when (v) { is String -> edit.putString(k, v); is Boolean -> edit.putBoolean(k, v); is Int -> edit.putInt(k, v)
                is Long -> edit.putLong(k, v); is Float -> edit.putFloat(k, v); is Set<*> -> edit.putStringSet(k, v.filterIsInstance<String>().toSet()) } }
            edit.commit()
        }
    }
}
