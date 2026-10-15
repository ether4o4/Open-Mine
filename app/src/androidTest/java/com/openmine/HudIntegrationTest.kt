package com.openmine

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Actual production Activity/root, with source records created only in this isolated test APK. */
class HudIntegrationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val preferences get() = context.getSharedPreferences("open_mine", Context.MODE_PRIVATE)
    private var scenario: ActivityScenario<MainActivity>? = null
    private var originalFontScale = 1f
    private var fixturePrepared = false

    @Before fun prepareIsolatedWorkspace() {
        assertTrue("HUD integration tests require an isolated emulator", Build.HARDWARE in setOf("ranchu", "goldfish") || Build.PRODUCT.contains("sdk"))
        originalFontScale = Settings.System.getFloat(context.contentResolver, Settings.System.FONT_SCALE, 1f)
        fixturePrepared = true
        // Test-runner app data is isolated from user devices. This suite must not run against a
        // personal installation; these are real store mutations, not fake production fixtures.
        runBlocking { AssistantSessionController.pauseForRecovery() }
        File(context.filesDir, "assistant").deleteRecursively()
        context.getSharedPreferences("assistant", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "open_mine_objects").deleteRecursively()
        File(context.filesDir, "open_mine_index.json").delete()
        preferences.edit().clear().putInt("screen", 3).putBoolean("animations", false).commit()
        OpenMineObjectStore.rebuildIndex(context)
    }

    @After fun closeActivityAndRestoreAccessibilityPreference() {
        if (!fixturePrepared) return
        scenario?.close()
        runBlocking { AssistantSessionController.pauseForRecovery() }
        if (Settings.System.getFloat(context.contentResolver, Settings.System.FONT_SCALE, 1f) != originalFontScale)
            shell("settings put system font_scale $originalFontScale")
    }

    @Test fun originalHudOpensKnowledgeAndExistingMenuCanOpenAndRestoreChatDraft() {
        preferences.edit().remove("screen").commit()
        launch()
        awaitTag("empty-manage")
        assertEquals(3, preferences.getInt("screen", 3))
        assertVisibleDockWithinViewport()
        compose.onNodeWithText("No knowledge records yet").assertIsDisplayed()
        compose.onNodeWithTag("nav-3").assertIsDisplayed().assertHasClickAction()
        capture("hud-restored-default-knowledge")
        navigate(12)
        awaitTag("chat_screen")
        compose.onNodeWithTag("chat_screen").performScrollToNode(hasTestTag("chat_composer"))
        val draft = "Keep this unsent draft: café → λ"
        // The standard focus-based input helper did not update this restored panel.
        // Exercise the real field's SetText callback and verify controller/render state.
        compose.onNodeWithTag("chat_composer").assertIsDisplayed().performSemanticsAction(SemanticsActions.SetText) { action ->
            assertTrue("The visible composer must accept the edit", action(AnnotatedString(draft)))
        }
        compose.waitUntil(10_000) { AssistantSessionController.get(context).state.value.session.current.draft == draft }
        compose.onNodeWithTag("chat_composer").assertTextContains(draft)
        scenario!!.onActivity { activity ->
            val manager = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            manager.hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
            activity.currentFocus?.clearFocus()
        }
        compose.waitForIdle()
        capture("chat-restored-original-layout")
        navigate(3)
        awaitTag("empty-manage")
        navigate(12)
        awaitTag("chat_screen")
        compose.onNodeWithTag("chat_screen").performScrollToNode(hasTestTag("chat_composer"))
        compose.onNodeWithTag("chat_composer").assertTextContains(draft)
        scenario!!.recreate()
        awaitTag("chat_screen")
        compose.onNodeWithTag("chat_screen").performScrollToNode(hasTestTag("chat_composer"))
        compose.onNodeWithTag("chat_composer").assertTextContains(draft)
        assertEquals(12, preferences.getInt("screen", -1))
    }

    @Test fun emptyWorkspaceHasNoSeededCatalogueAndAllFourDocksRemainVisible() {
        launch()
        for ((index, name) in listOf("models", "projects", "connectors", "knowledge").withIndex()) {
            navigate(index)
            awaitTag("empty-manage")
            compose.onNodeWithTag("empty-manage").assertIsDisplayed().assertHasClickAction()
            compose.onNodeWithText("No ${listOf("model", "project", "connector", "knowledge")[index]} records yet").assertIsDisplayed()
            assertTrue(OpenMineObjectStore.inspect(context).records.isEmpty())
            assertVisibleDockWithinViewport()
            capture("hud-empty-$name")
        }
        compose.onAllNodesWithText("Qwen 3.5 2B").assertCountEquals(0)
        compose.onAllNodesWithText("GitHub MCP").assertCountEquals(0)
        compose.onNodeWithTag("workspace-actions").assertHeightIsAtLeast(androidx.compose.ui.unit.Dp(48f))
    }

    @Test fun uiCreatedRecordReachesHudAndContextSurvivesActivityRecreation() {
        launch()
        awaitTag("empty-manage")
        compose.onNodeWithTag("empty-manage").performClick()
        awaitTag("operational-library")
        compose.onNodeWithTag("library-create").performClick()
        compose.onNodeWithTag("library-create-title").performTextInput("UI continuity note")
        compose.onNodeWithText("REVIEW DOCUMENT").performClick()
        awaitTag("library-editor-list")
        compose.onNodeWithTag("library-editor-list").performScrollToNode(hasTestTag("library-save-record"))
        compose.onNodeWithTag("library-save-record").performClick()
        awaitTag("library-list")
        compose.onNodeWithContentDescription("Back to workspace").performClick()
        awaitDetail("UI continuity note")
        val record = OpenMineObjectStore.inspect(context).records.single { it.title == "UI continuity note" }
        assertEquals("DRAFT", record.status)
        compose.onNodeWithTag("primary-action").performClick()
        compose.waitUntil(10_000) { record.id in preferences.getStringSet("context", emptySet()).orEmpty() }
        compose.onNodeWithText("REMOVE FROM CONTEXT").assertIsDisplayed()
        scenario!!.recreate()
        awaitDetail(record.title)
        compose.onNodeWithText("REMOVE FROM CONTEXT").assertIsDisplayed()
        assertEquals(record.id, OpenMineObjectStore.inspect(context).records.single().id)
        capture("hud-created-record-restored")
        compose.onNodeWithTag("primary-action").performClick()
        compose.waitUntil(10_000) { record.id !in preferences.getStringSet("context", emptySet()).orEmpty() }
    }

    @Test fun realStoreImportsPopulateAllHudCategoriesAndPageBeyondNineRecords() {
        // Exercises the actual validator/store/index and UI integration. SAF document-provider
        // interaction is a separate workflow and is not claimed by this storage-import check.
        val records = mutableListOf<StrictObject>()
        listOf("MODEL", "PROJECT", "CONNECTOR", "KNOWLEDGE").forEach { type ->
            repeat(10) { number ->
                val title = "Test ${type.lowercase().replaceFirstChar(Char::uppercase)} %02d".format(number + 1)
                val saved = OpenMineObjectStore.import(context, fixture(type, title).raw)
                assertTrue(saved.errors.joinToString(), saved.valid)
                records += saved.normalized!!
            }
        }
        launch()
        for ((index, name) in listOf("models", "projects", "connectors", "knowledge").withIndex()) {
            navigate(index)
            val type = listOf("MODEL", "PROJECT", "CONNECTOR", "KNOWLEDGE")[index]
            val first = records.first { it.type == type }
            awaitDetail(first.title)
            compose.onNodeWithTag("orbit-${first.id}").assertIsDisplayed().performClick()
            val approvedTab=listOf("PARAMETERS","FILES","PERMISSIONS","DOCUMENTS")[index]
            compose.onNodeWithTag("tab-$approvedTab").performClick()
            compose.onNodeWithTag("tab-$approvedTab").assertIsSelected()
            compose.onNodeWithTag("tab-OVERVIEW").performClick()
            assertVisibleDockWithinViewport()
            capture("hud-imported-$name")
            repeat(9) { compose.onNodeWithContentDescription("Next objects").performClick() }
            compose.onNodeWithTag("carousel-${records.last { it.type == type }.id}").performClick()
            awaitDetail(records.last { it.type == type }.title)
            compose.onNodeWithTag("orbit-${records.last { it.type == type }.id}").assertIsDisplayed()
        }
        assertEquals(40, OpenMineObjectStore.inspect(context).records.size)
    }

    @Test fun navigationAndReducedMotionPreferencePersistAndHubStopsAnimating() {
        val result = OpenMineObjectStore.import(context, fixture("MODEL", "Motion test model").raw)
        assertTrue(result.valid)
        preferences.edit().putInt("screen", 0).putBoolean("animations", true).commit()
        launch()
        navigate(11)
        awaitTag("setting-animations")
        compose.onNodeWithTag("setting-animations").performClick()
        compose.waitUntil(10_000) { !preferences.getBoolean("animations", true) }
        navigate(0)
        awaitDetail("Motion test model")
        val first = centralHubPixels()
        compose.mainClock.advanceTimeBy(2_500)
        compose.waitForIdle()
        assertArrayEquals("The hub should stay still with reduced motion enabled", first, centralHubPixels())
        scenario!!.recreate()
        awaitDetail("Motion test model")
        assertFalse(preferences.getBoolean("animations", true))
        navigate(11)
        awaitTag("setting-animations")
        compose.onNodeWithTag("setting-animations").assertIsOff()
        navigate(10)
        capture("hud-diagnostics-native")
        navigate(3)
        awaitTag("empty-manage")
    }

    @Test fun largeFontUsesNativeAccessibleControlsAndCanReturnToWorkspace() {
        shell("settings put system font_scale 1.5")
        launch()
        awaitTag("accessible-workspace")
        compose.onNodeWithTag("workspace-actions").assertHeightIsAtLeast(androidx.compose.ui.unit.Dp(48f))
        compose.onNodeWithTag("accessible-workspace").performScrollToNode(hasTestTag("empty-manage"))
        compose.onNodeWithTag("empty-manage").assertIsDisplayed().assertHeightIsAtLeast(androidx.compose.ui.unit.Dp(48f)).performClick()
        awaitTag("operational-library")
        compose.onNodeWithContentDescription("Back to workspace").performClick()
        awaitTag("accessible-workspace")
        capture("hud-large-font")
    }

    @Test fun corruptLibrarySessionCanExitWithoutOverwritingTheRecoveryFile() {
        val file = File(context.filesDir, "library-sessions/connector.json")
        file.parentFile!!.mkdirs()
        val damaged = "test-created interrupted session bytes"
        file.writeText(damaged)
        preferences.edit().putInt("screen", 2).putString("route", "library:2").commit()
        try {
            scenario = ActivityScenario.launch(MainActivity::class.java)
            compose.waitUntil(15_000) { compose.onAllNodesWithText("Cannot restore library session", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Back to workspace").performClick()
            awaitTag("hud-root")
            assertEquals(damaged, file.readText())
        } finally { file.delete() } // Remove only this test-created corrupt fixture.
    }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        awaitTag("hud-root")
    }

    private fun navigate(index: Int) {
        if (compose.onAllNodesWithTag("nav-$index").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("nav-$index").performClick()
        } else {
            compose.onNodeWithTag("workspace-actions").performClick()
            val title = listOf("AI MODELS", "PROJECTS", "CONNECTORS", "KNOWLEDGE", "SKILLS", "MISSIONS", "TOOLS", "FILES", "BROWSER", "TERMINAL", "DIAGNOSTICS", "SETTINGS", "AI chat")[index]
            compose.onNode(hasText(title) and hasAnyAncestor(isDialog())).performScrollTo().performClick()
        }
        compose.waitForIdle()
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun awaitDetail(title: String) {
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasTestTag("detail-title") and hasText(title)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun assertVisibleDockWithinViewport() {
        val root = compose.onNodeWithTag("hud-root").fetchSemanticsNode().boundsInRoot
        val expectedHeight = 76f * minOf(root.width / 700f, root.height / 1280f)
        for (index in 0..5) {
            val dock = compose.onNodeWithTag("dock-$index").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("Dock $index extends beyond the HUD viewport: $dock / $root",
                dock.left >= root.left - 1 && dock.top >= root.top - 1 && dock.right <= root.right + 1 && dock.bottom <= root.bottom + 1)
            assertTrue("Dock $index was clipped vertically", dock.height >= expectedHeight - 2f)
        }
    }

    private fun centralHubPixels(): IntArray {
        val bitmap = compose.onNodeWithTag("hud-root").captureToImage().asAndroidBitmap()
        val x = (bitmap.width * .48f).toInt(); val y = (bitmap.height * .33f).toInt()
        val width = (bitmap.width * .18f).toInt(); val height = (bitmap.height * .13f).toInt()
        return IntArray(width * height).also { bitmap.getPixels(it, 0, width, x, y, width, height) }
    }

    private fun capture(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val directory = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "hud-verification").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(directory, "$name.jpg").outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        val evidence = JSONObject().put("screen", name).put("package", context.packageName)
            .put("api", Build.VERSION.SDK_INT).put("abis", Build.SUPPORTED_ABIS.joinToString()).put("device", Build.DEVICE)
            .put("fingerprint", Build.FINGERPRINT).put("width_px", bitmap.width).put("height_px", bitmap.height)
            .put("density", context.resources.displayMetrics.density).put("font_scale", context.resources.configuration.fontScale)
            .put("animations_preference", preferences.getBoolean("animations", true))
            .put("system_animator_duration_scale", Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f))
            .put("fixture_notice", "Only test-created saved records; these captures do not prove model or connector operation")
        File(directory, "$name.json").writeText(evidence.toString(2))
    }

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
    }

    private fun fixture(type: String, title: String) = OpenMineObjectFormat.template(
        type, title, "Instrumentation fixture saved through the real object validator and index.", "test, local", "Android test fixture",
        "Validate HUD integration", "Fixture facts", "NONE", "No operational capability is asserted", "NONE", "test, integration", "NONE", "NONE",
        "NONE", "NONE", "NONE", "NONE", "NONE", "test integration", "During instrumentation", "NONE", "DRAFT", "Android test", "UI evidence only",
    )
}
