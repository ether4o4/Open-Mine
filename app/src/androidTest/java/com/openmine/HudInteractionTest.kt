package com.openmine

import android.graphics.Bitmap
import android.os.Environment
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class HudInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Before fun resetPreferences() {
        compose.activity.getSharedPreferences("open_mine", 0).edit().clear().putBoolean("animations", false).commit()
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
    }

    @Test fun primaryScreensRenderAndKeepDockVisible() {
        val titles = listOf("Qwen 3.5 2B", "Open Mine", "GitHub MCP", "Engineering Vault")
        titles.forEachIndexed { screen, title ->
            compose.onNodeWithTag("nav-$screen").performClick()
            compose.onNodeWithTag("detail-title").assertTextEquals(title)
            compose.onNodeWithTag("dock-3").assertIsDisplayed()
            capture("hud-${listOf("models", "projects", "connectors", "knowledge")[screen]}")
        }
    }

    @Test fun selectionAndContextSurviveNavigationAndRecreation() {
        compose.onNodeWithTag("orbit-Gemma 4 2B").performClick()
        compose.onNodeWithTag("detail-title").assertTextEquals("Gemma 4 2B")
        compose.onNodeWithTag("primary-action").performClick()
        compose.onNodeWithTag("primary-action").assertTextContains("REMOVE FROM CONTEXT")
        compose.onNodeWithTag("dock-2").performClick()
        compose.onNodeWithTag("dock-0").performClick()
        compose.onNodeWithTag("detail-title").assertTextEquals("Gemma 4 2B")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("detail-title").assertTextEquals("Gemma 4 2B")
        compose.onNodeWithTag("primary-action").assertTextContains("REMOVE FROM CONTEXT")
        compose.onNodeWithTag("primary-action").performClick()
        compose.onNodeWithTag("primary-action").assertTextContains("ADD TO CONTEXT")
    }

    @Test fun vaultAndSettingsRemainReachable() {
        compose.onNodeWithTag("nav-3").performClick()
        compose.onNodeWithTag("tab-DOCUMENTS").performClick()
        compose.onNodeWithTag("open-vault").performClick()
        compose.onNodeWithText("+ CREATE").assertIsDisplayed()
        compose.onNodeWithText("IMPORT .OMD").assertIsDisplayed()
        compose.onNodeWithText("BACK TO HUD").performClick()
        compose.onNodeWithTag("nav-10").performClick()
        compose.onNodeWithText("STRICT FORMAT").assertIsDisplayed()
        compose.onNodeWithTag("nav-11").performClick()
        compose.onNodeWithText("Animations").assertIsDisplayed()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val directory = compose.activity.getExternalFilesDir(Environment.DIRECTORY_PICTURES)!!
        directory.mkdirs()
        val bitmap = compose.onNodeWithTag("hud-root").captureToImage().asAndroidBitmap()
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(directory, "$name.jpg").outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
    }
}
