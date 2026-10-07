package com.openmine

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.AdaptiveIconDrawable
import android.view.WindowManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Real Android storage + Compose checks; no network, provider mocks or inference claims. */
class ProjectRuntimeTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val id = "project.test-${UUID.randomUUID()}"
    private val store get() = ProjectStore(context.filesDir, id)
    private val record = StrictObject(mapOf("OBJECT_ID" to id, "OBJECT_TYPE" to "PROJECT", "OBJECT_TITLE" to "Offline project"), emptyMap(), "")

    @After fun cleanOwnedFixtures() {
        // Only this test's unique project directory is removed; installed user records remain intact.
        File(context.filesDir, "open_mine_projects/$id").deleteRecursively()
        context.getSharedPreferences("open_mine_projects", Context.MODE_PRIVATE).edit()
            .remove("$id.tab").remove("$id.draftError").commit()
    }

    @Test fun projectTasksPersistAcrossUiRecreationAndDeleteRequiresConfirmation() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MaterialTheme { ProjectWorkspace(record) {} } }
        awaitText("Workspace stored on this device", substring = true)
        compose.onNodeWithText("Add task").performClick()
        compose.onNodeWithText("Title").performTextReplacement("Verify an offline project")
        compose.onNodeWithText("Notes").performTextReplacement("Preserve the same task ID when it changes")
        compose.onNodeWithText("Save task").performClick()
        awaitTaskCount(1)
        val initialId = store.tasks().single().id
        awaitEnabled("Complete")
        compose.onNodeWithText("Complete").performClick()
        compose.waitUntil(10_000) { store.tasks().singleOrNull()?.status == ProjectTaskStatus.DONE }

        restoration.emulateSavedInstanceStateRestore()
        awaitText("Verify an offline project")
        compose.onNodeWithText("Done").assertIsDisplayed()
        assertEquals(initialId, store.tasks().single().id)
        compose.onNodeWithText("Edit").performClick()
        compose.onNodeWithText("Title").performTextReplacement("Verified and persisted")
        compose.onNodeWithText("Save task").performClick()
        awaitText("Verified and persisted")
        assertEquals(initialId, store.tasks().single().id)

        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(1, store.tasks().size)
        compose.onNodeWithText("Delete").performClick()
        awaitText("Confirm deletion")
        val deletes = compose.onAllNodesWithText("Delete")
        deletes[deletes.fetchSemanticsNodes().lastIndex].performClick()
        awaitTaskCount(0)
        awaitText("No tasks yet", substring = true)
    }

    @Test fun textDraftRecoversAndAConflictingSavePreservesBothVersions() {
        store.createText("notes.txt", "Original contents")
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MaterialTheme { ProjectWorkspace(record) {} } }
        awaitText("Files (1)")
        compose.onNodeWithText("Files (1)").performClick()
        compose.onNodeWithText("View / edit text").performClick()
        awaitEnabled("File contents")
        compose.onNodeWithText("File contents").performTextReplacement("Recovered local draft")
        compose.waitUntil(10_000) { store.readDraft("notes.txt")?.text == "Recovered local draft" }
        restoration.emulateSavedInstanceStateRestore()
        awaitText("Recovered the unsaved local draft")
        compose.onNodeWithText("Recovered local draft").assertIsDisplayed()
        awaitEnabled("Save")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10_000) { store.readText("notes.txt") == "Recovered local draft" }
        assertNull(store.readDraft("notes.txt"))
        awaitEnabled("File contents")

        val file = File(context.filesDir, "open_mine_projects/$id/files/notes.txt")
        val priorTime = file.lastModified()
        file.writeText("External change preserved")
        assertTrue(file.setLastModified(priorTime + 10_000))
        compose.onNodeWithText("File contents").performTextReplacement("My conflicting draft")
        compose.onNodeWithText("Save").performClick()
        awaitText("File changed since it was opened", substring = true)
        assertEquals("External change preserved", store.readText("notes.txt"))
        assertEquals("My conflicting draft", store.readDraft("notes.txt")?.text)
        compose.onNodeWithText("Save a copy").performClick()
        compose.onNodeWithText("Filename").performTextReplacement("recovered-copy.txt")
        compose.onNodeWithText("Create").performClick()
        compose.waitUntil(10_000) { store.listFiles().any { it.name == "recovered-copy.txt" } }
        assertEquals("My conflicting draft", store.readText("recovered-copy.txt"))
        assertEquals("External change preserved", store.readText("notes.txt"))
    }

    @Test fun androidFileIoRejectsTraversalAndRecoversFromAnInterruptedSource() {
        store.createText("keep.txt", "Existing data")
        val failing = object : java.io.InputStream() {
            private var reads = 0
            override fun read(): Int { if (++reads > 5000) throw java.io.IOException("Source disappeared"); return 65 }
        }
        assertTrue(runCatching { store.importFile("partial.bin", failing) }.isFailure)
        assertEquals(listOf("keep.txt"), store.listFiles().map { it.name })
        assertTrue(runCatching { store.createText("../escape.txt", "bad") }.isFailure)
        val bytes = byteArrayOf(0, 1, 2, -1)
        store.importFile("retry.bin", ByteArrayInputStream(bytes))
        val destination = ByteArrayOutputStream()
        ProjectStore(context.filesDir, id).exportFile("retry.bin", destination)
        assertArrayEquals(bytes, destination.toByteArray())
        assertEquals("Existing data", store.readText("keep.txt"))
    }

    @Test fun launcherAndKeyboardConfigurationArePresentInInstalledPackage() {
        val info = context.packageManager.getApplicationInfo(context.packageName, 0)
        assertTrue("Launcher icon must be adaptive on API 26+", context.packageManager.getApplicationIcon(info) is AdaptiveIconDrawable)
        val activity = context.packageManager.getActivityInfo(android.content.ComponentName(context.packageName, "com.openmine.MainActivity"), PackageManager.GET_META_DATA)
        assertEquals(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE, activity.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST)
        assertEquals("com.openmine", context.packageName)
    }

    private fun awaitTaskCount(count: Int) = compose.waitUntil(10_000) { store.tasks().size == count }
    private fun awaitEnabled(text: String) = compose.waitUntil(10_000) {
        runCatching { compose.onNodeWithText(text).assertIsEnabled(); true }.getOrDefault(false)
    }
    private fun awaitText(text: String, substring: Boolean = false) = compose.waitUntil(10_000) {
        compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
    }
}
