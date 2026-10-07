package com.openmine

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.regex.Pattern

/** Real DocumentsUI + DocumentsProvider + URI permission grants. No intent interception. */
class SafWorkflowTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val device get() = UiDevice.getInstance(instrumentation)
    private val nonce = UUID.randomUUID().toString().take(8)
    private val projectId = "project.saf-$nonce"
    private val workspace get() = ProjectStore(context.filesDir, projectId)
    private var pickerPackage = ""

    @Before fun prepareOnlyOwnedFixtures() {
        assertTrue("SAF integration tests run only on isolated emulators", Build.HARDWARE in setOf("ranchu", "goldfish") || Build.PRODUCT.contains("sdk"))
        OpenMineObjectStore.all(context).firstOrNull { it.id == TestDocumentsProvider.RECORD_ID }?.let {
            assertEquals("Refusing to replace a non-fixture record", TestDocumentsProvider.SOURCE, it.fields["OBJECT_SOURCE"])
            OpenMineObjectStore.delete(context, it)
        }
        context.contentResolver.persistedUriPermissions.filter { it.uri.authority == TestDocumentsProvider.AUTHORITY }.forEach {
            val flags = (if (it.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or (if (it.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
            context.contentResolver.releasePersistableUriPermission(it.uri, flags)
        }
    }

    @After fun removeOnlyThisProjectsFixtures() {
        File(context.filesDir, "open_mine_projects/$projectId").deleteRecursively()
        context.getSharedPreferences("open_mine_projects", android.content.Context.MODE_PRIVATE).edit().remove("$projectId.tab").remove("$projectId.draftError").commit()
    }

    @Test fun libraryImportsAndExportsCanonicalBytesThroughRealPickerGrants() = verify("library_import_export") {
        val uri = TestDocumentsProvider.uri(TestDocumentsProvider.RECORD_NAME)
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION))
        openLibrary()
        compose.onNodeWithText("IMPORT .OMD").performClick()
        openPickerDocument(TestDocumentsProvider.RECORD_NAME, "saf-library-import")
        awaitText("Imported and indexed ${TestDocumentsProvider.RECORD_TITLE}.")
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION))
        assertTrue("Import should retain its granted read permission", context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission })
        val source = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        val canonical = OpenMineObjectFormat.validate(source.toString(Charsets.UTF_8)).normalized ?: error("Fixture failed strict validation")
        val imported = OpenMineObjectStore.all(context).single { it.id == canonical.id }
        assertEquals(canonical.raw, imported.raw)
        assertEquals(imported.id, OpenMineObjectStore.search(context, "safpermissionfixture").single { it.id == imported.id }.id)
        compose.onNodeWithTag("library-list").performScrollToNode(hasText("EXPORT .OMD"))
        compose.onNodeWithText("EXPORT .OMD").performClick()
        val exportName = "canonical-$nonce.omd"
        createPickerDocument(exportName, "saf-library-export")
        awaitText("Canonical .omd record exported.")
        val destination = TestDocumentsProvider.uri(exportName)
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkUriPermission(destination, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION))
        val exported = context.contentResolver.openInputStream(destination)!!.use { it.readBytes() }
        assertArrayEquals(imported.raw.toByteArray(Charsets.UTF_8), exported)
        JSONObject().put("record_id", imported.id).put("export_sha256", sha(exported))
            .put("permission_before_picker", "denied").put("permission_after_picker", "granted and persisted")
    }

    @Test fun projectFileRoundTripUsesThePickerAndCancellingImportCreatesNothing() = verify("project_import_export_cancel") {
        val record = StrictObject(mapOf("OBJECT_ID" to projectId, "OBJECT_TYPE" to "PROJECT", "OBJECT_TITLE" to "SAF project fixture"), emptyMap(), "")
        compose.setContent { MaterialTheme { ProjectWorkspace(record) {} } }
        awaitText("Files (0)")
        compose.onNodeWithText("Files (0)").performClick()
        awaitEnabled("Import")
        compose.onNodeWithText("Import").performClick()
        awaitPicker()
        device.pressBack()
        awaitEnabled("Import")
        assertTrue("Cancelled picker must not create a project file", workspace.listFiles().isEmpty())
        compose.onAllNodesWithText("saved to this project", substring = true).assertCountEquals(0)

        val uri = TestDocumentsProvider.uri(TestDocumentsProvider.PROJECT_NAME)
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION))
        compose.onNodeWithText("Import").performClick()
        openPickerDocument(TestDocumentsProvider.PROJECT_NAME, "saf-project-import")
        awaitEnabled("Filename")
        compose.onNodeWithText("Filename").performTextReplacement("picked-notes.txt")
        val importButtons = compose.onAllNodesWithText("Import")
        importButtons[importButtons.fetchSemanticsNodes().lastIndex].performClick()
        awaitText("picked-notes.txt saved to this project")
        assertEquals(TestDocumentsProvider.PROJECT_TEXT, workspace.readText("picked-notes.txt"))
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION))
        awaitEnabled("Export")
        compose.onNodeWithText("Export").performClick()
        val exportName = "project-export-$nonce.txt"
        createPickerDocument(exportName, "saf-project-export")
        awaitText("Exported picked-notes.txt")
        val exported = context.contentResolver.openInputStream(TestDocumentsProvider.uri(exportName))!!.use { it.readBytes() }
        assertArrayEquals(TestDocumentsProvider.PROJECT_TEXT.toByteArray(Charsets.UTF_8), exported)
        JSONObject().put("project_id", projectId).put("export_sha256", sha(exported)).put("cancelled_import_created_files", 0)
            .put("permission_before_picker", "denied").put("permission_after_picker", "granted")
    }

    @Test fun malformedLibraryImportAndPickerCancellationNeverReportSuccess() = verify("library_invalid_import_cancel") {
        openLibrary()
        val original = OpenMineObjectStore.all(context).associate { it.id to it.raw }
        compose.onNodeWithText("IMPORT .OMD").performClick()
        openPickerDocument(TestDocumentsProvider.INVALID_NAME, "saf-invalid-import")
        awaitText("Missing [OPEN_MINE_OBJECT] section.", substring = true)
        assertEquals(original, OpenMineObjectStore.all(context).associate { it.id to it.raw })
        compose.onAllNodesWithText("Imported and indexed", substring = true).assertCountEquals(0)
        compose.onNodeWithText("IMPORT .OMD").performClick()
        awaitPicker()
        device.pressBack()
        awaitText("Import cancelled.")
        assertEquals(original, OpenMineObjectStore.all(context).associate { it.id to it.raw })
        compose.onAllNodesWithText("Imported and indexed", substring = true).assertCountEquals(0)
        JSONObject().put("unchanged_source_records", original.size).put("malformed_input_rejected", true).put("picker_cancelled", true)
    }

    private fun openLibrary() {
        compose.setContent { MaterialTheme { OperationalLibraryScreen(category = "KNOWLEDGE", contextIds = emptySet(), onToggleContext = {}, onOpenRecord = {}, onBack = {}) } }
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("library-list").fetchSemanticsNodes().isNotEmpty() || compose.onAllNodesWithTag("library-editor-list").fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodesWithTag("library-editor-list").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithContentDescription("Back to workspace").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("library-list").fetchSemanticsNodes().isNotEmpty() }
        }
        if (compose.onAllNodesWithText("New labeled record").fetchSemanticsNodes().isNotEmpty()) device.pressBack()
        compose.onNodeWithTag("library-list").performScrollToNode(hasTestTag("library-search"))
        compose.onNodeWithTag("library-search").performTextReplacement("")
        compose.onNodeWithTag("library-list").performScrollToIndex(0)
        awaitEnabled("IMPORT .OMD")
    }

    private fun awaitPicker() {
        val picker = device.wait(Until.findObject(By.pkg(Pattern.compile("com\\.(android|google)\\.documentsui"))), 15_000)
        assertNotNull("Android DocumentsUI did not open", picker)
        pickerPackage = picker!!.applicationPackage
        device.waitForIdle()
    }

    private fun chooseTestRoot() {
        awaitPicker()
        // A subsequent save picker usually remembers this provider. Its title in the toolbar
        // and visible fixture entries are enough to establish that the correct root is open.
        if (device.hasObject(By.text(TestDocumentsProvider.ROOT_TITLE)) &&
            (device.hasObject(By.text(TestDocumentsProvider.INVALID_NAME)) || device.hasObject(By.text(TestDocumentsProvider.PROJECT_NAME)))) return
        val roots = device.findObject(By.desc(Pattern.compile("(?i)(show roots|show navigation drawer|navigate up)")))
            ?: device.findObject(By.res(pickerPackage, "toolbar").hasDescendant(By.clazz("android.widget.ImageButton")))?.findObject(By.clazz("android.widget.ImageButton"))
            ?: error("DocumentsUI root selector was not found")
        roots.click()
        val root = device.wait(Until.findObject(By.text(TestDocumentsProvider.ROOT_TITLE)), 10_000)
            ?: error("The test DocumentsProvider is not available in Android DocumentsUI")
        root.click()
        device.wait(Until.gone(By.res(pickerPackage, "roots_list")), 5_000)
        device.waitForIdle()
    }

    private fun openPickerDocument(name: String, screenshot: String) {
        chooseTestRoot()
        var document = device.wait(Until.findObject(By.text(name)), 5_000)
        if (document == null) {
            val list = device.findObject(By.res(pickerPackage, "dir_list")) ?: device.findObject(By.scrollable(true))
            repeat(4) {
                if (document == null && list != null) {
                    list.scroll(androidx.test.uiautomator.Direction.DOWN, 0.7f)
                    document = device.findObject(By.text(name))
                }
            }
        }
        assertNotNull("Picker document $name was not visible", document)
        assertTrue("Could not capture Android DocumentsUI", device.takeScreenshot(File(evidenceDirectory(), "$screenshot.png")))
        document!!.click()
        assertTrue("Picker did not return to the application", device.wait(Until.gone(By.pkg(pickerPackage)), 10_000))
    }

    private fun createPickerDocument(name: String, screenshot: String) {
        chooseTestRoot()
        val title = device.findObject(By.res(pickerPackage, "title").clazz("android.widget.EditText"))
            ?: device.findObject(By.clazz("android.widget.EditText")) ?: error("DocumentsUI filename field was not found")
        title.text = name
        val save = device.wait(Until.findObject(By.text(Pattern.compile("(?i)save")).enabled(true)), 10_000)
            ?: error("DocumentsUI Save action was not enabled")
        assertTrue("Could not capture Android DocumentsUI", device.takeScreenshot(File(evidenceDirectory(), "$screenshot.png")))
        save.click()
        assertTrue("Save picker did not return to the application", device.wait(Until.gone(By.pkg(pickerPackage)), 10_000))
    }

    private fun awaitText(value: String, substring: Boolean = false) = compose.waitUntil(15_000) {
        compose.onAllNodesWithText(value, substring = substring).fetchSemanticsNodes().isNotEmpty()
    }
    private fun awaitEnabled(value: String) = compose.waitUntil(15_000) {
        runCatching { compose.onNodeWithText(value).assertIsEnabled(); true }.getOrDefault(false)
    }
    private fun evidenceDirectory() = File(context.getExternalFilesDir(null) ?: error("External evidence directory unavailable"), "verification").apply { check(isDirectory || mkdirs()) }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun verify(name: String, action: () -> JSONObject) {
        val result = JSONObject().put("test", name).put("api", Build.VERSION.SDK_INT).put("device", Build.MODEL)
            .put("provider", TestDocumentsProvider.AUTHORITY).put("production_intents_intercepted", false)
        try { result.put("checks", action()).put("result", "passed") }
        catch (failure: Throwable) {
            result.put("result", "failed").put("failure", failure.toString())
            runCatching { device.dumpWindowHierarchy(File(evidenceDirectory(), "$name-failure-window.xml")) }
            runCatching { device.takeScreenshot(File(evidenceDirectory(), "$name-failure.png")) }
            throw failure
        } finally {
            result.put("documents_ui_package", pickerPackage)
            val file = File(evidenceDirectory(), "saf-workflows.json")
            val entries = runCatching { JSONArray(file.readText()) }.getOrElse { JSONArray() }
            entries.put(result)
            file.writeText(entries.toString(2))
            println("OPEN_MINE_SAF_EVIDENCE $result")
        }
    }
}
