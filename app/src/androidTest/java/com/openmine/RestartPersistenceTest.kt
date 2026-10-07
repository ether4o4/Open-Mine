package com.openmine

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Process
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * Run these methods in TWO distinct instrumentation invocations, with an external
 * `adb shell am force-stop com.openmine` between them. This is process-death recovery,
 * not Activity recreation. The argument + emulator guard prevents personal-device use.
 * Fixtures use production storage APIs and never represent an inference result.
 */
class RestartPersistenceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val marker get() = File(context.noBackupFilesDir, "restart-persistence-test.json")
    private val preferences get() = context.getSharedPreferences("open_mine", Context.MODE_PRIVATE)

    @Test fun seedState() {
        requireIsolatedEmulator()
        val evidence = evidence("seedState")
        try {
            val nonce = UUID.randomUUID().toString().replace("-", "")
            val keyword = "restart$nonce"
            val project = fixture("PROJECT", "Restart project ${nonce.take(12)}", keyword)
            val knowledge = fixture("KNOWLEDGE", "Restart knowledge ${nonce.take(12)}", keyword)
            for (record in listOf(project, knowledge)) {
                val result = OpenMineObjectStore.import(context, record.raw)
                assertTrue(result.errors.joinToString(), result.valid)
                assertEquals(record.id, result.normalized!!.id)
            }
            val workspace = ProjectStore(context.filesDir, project.id)
            val task = workspace.saveTask(null, "Persisted task ${nonce.take(12)}", "Offline task notes $nonce", ProjectTaskStatus.IN_PROGRESS)
            val text = "Saved project contents $nonce"
            val draft = "Unsaved project draft $nonce"
            val file = workspace.createText("restart-note.txt", text)
            workspace.saveDraft(ProjectDraft(file.name, draft, file.modified))
            val binary = byteArrayOf(0, 1, 2, 127, -1) + nonce.toByteArray(Charsets.UTF_8)
            binary.inputStream().use { workspace.importFile("restart-binary.dat", it) }

            val assistantStore = AssistantSessionStore(File(context.filesDir, "assistant/sessions-v1.json"))
            val existing = assistantStore.load() ?: AssistantSession()
            val turn = AssistantTurn(
                question = "Persistence test fixture only; no inference executed $nonce",
                answer = "Partial fixture text retained across process death $nonce",
                state = AssistantTurnState.STREAMING,
                sourceIds = listOf(knowledge.id),
            )
            val conversation = AssistantConversation(title = "Restart persistence fixture", draft = "Unsent fixture message $nonce", turns = listOf(turn))
            assistantStore.save(existing.copy(conversations = existing.conversations + conversation, selectedId = conversation.id, toolsEnabled = false))
            val selectedContext = preferences.getStringSet("context", emptySet()).orEmpty().toSet() + setOf(project.id, knowledge.id)
            assertTrue(preferences.edit().putStringSet("context", selectedContext)
                .putInt("screen", 1).putString("route", "project").putString("opened_record", project.id)
                .putString("active", project.id).putString("active_1", project.id)
                .putBoolean("animations", false).putBoolean("haptics", false).commit())
            assertTrue(context.getSharedPreferences("open_mine_projects", Context.MODE_PRIVATE).edit().putInt("${project.id}.tab", 0).commit())

            val snapshot = OpenMineObjectStore.inspect(context)
            assertTrue(snapshot.indexReady)
            assertTrue(OpenMineObjectStore.search(context, keyword).map { it.id }.containsAll(listOf(project.id, knowledge.id)))
            val expected = JSONObject().put("seedPid", Process.myPid()).put("nonce", nonce).put("keyword", keyword)
                .put("projectId", project.id).put("projectTitle", project.title).put("knowledgeId", knowledge.id)
                .put("projectSourceSha256", sha(File(context.filesDir, "open_mine_objects/${project.id}.omd").readBytes()))
                .put("knowledgeSourceSha256", sha(File(context.filesDir, "open_mine_objects/${knowledge.id}.omd").readBytes()))
                .put("taskId", task.id).put("taskTitle", task.title).put("taskNotes", task.notes)
                .put("savedText", text).put("draftText", draft).put("draftBaseModified", file.modified)
                .put("binarySha256", sha(binary)).put("conversationId", conversation.id).put("turnId", turn.id)
                .put("partialAnswer", turn.answer).put("assistantDraft", conversation.draft)
            writeSynced(marker, expected.toString(2))
            evidence.put("result", "seeded; awaiting external force-stop and verification")
                .put("seed_pid", Process.myPid()).put("project_id", project.id)
                .put("verified_after_process_restart", false)
            saveEvidence(evidence)
        } catch (failure: Throwable) {
            evidence.put("result", "failed").put("failure", failure.toString())
            runCatching { saveEvidence(evidence) }
            throw failure
        }
    }

    @Test fun verifyRestoredState() {
        requireIsolatedEmulator()
        val evidence = evidence("verifyRestoredState")
        val checks = JSONArray()
        evidence.put("passed_checks", checks)
        try {
            assertTrue("Run seedState first; do not clear package data between phases", marker.isFile)
            val expected = JSONObject(marker.readText())
            val seedPid = expected.getInt("seedPid")
            evidence.put("seed_pid", seedPid).put("verification_pid", Process.myPid())
            assertNotEquals("Verification must run in a new Android application process", seedPid, Process.myPid())
            checks.put("Different Android application PID after external force-stop")

            val projectId = expected.getString("projectId")
            val knowledgeId = expected.getString("knowledgeId")
            val snapshot = OpenMineObjectStore.inspect(context)
            assertTrue(snapshot.indexReady)
            assertTrue(snapshot.chunkCount > 0)
            assertEquals("PROJECT", snapshot.records.single { it.id == projectId }.type)
            assertEquals("KNOWLEDGE", snapshot.records.single { it.id == knowledgeId }.type)
            assertEquals(expected.getString("projectSourceSha256"), sha(File(context.filesDir, "open_mine_objects/$projectId.omd").readBytes()))
            assertEquals(expected.getString("knowledgeSourceSha256"), sha(File(context.filesDir, "open_mine_objects/$knowledgeId.omd").readBytes()))
            assertTrue(OpenMineObjectStore.search(context, expected.getString("keyword")).map { it.id }.containsAll(listOf(projectId, knowledgeId)))
            checks.put("Strict project/knowledge source IDs and byte hashes retained; indexed retrieval works")

            val workspace = ProjectStore(context.filesDir, projectId)
            val task = workspace.tasks().single()
            assertEquals(expected.getString("taskId"), task.id)
            assertEquals(expected.getString("taskTitle"), task.title)
            assertEquals(expected.getString("taskNotes"), task.notes)
            assertEquals(ProjectTaskStatus.IN_PROGRESS, task.status)
            assertEquals(expected.getString("savedText"), workspace.readText("restart-note.txt"))
            val draft = workspace.readDraft("restart-note.txt") ?: error("Saved draft was not restored")
            assertEquals(expected.getString("draftText"), draft.text)
            assertEquals(expected.getLong("draftBaseModified"), draft.originalModified)
            val exported = ByteArrayOutputStream()
            workspace.exportFile("restart-binary.dat", exported)
            assertEquals(expected.getString("binarySha256"), sha(exported.toByteArray()))
            checks.put("Offline task identity/status, saved text, unsaved draft and binary file retained")

            assertTrue(preferences.getStringSet("context", emptySet()).orEmpty().containsAll(listOf(projectId, knowledgeId)))
            assertEquals(1, preferences.getInt("screen", -1))
            assertEquals("project", preferences.getString("route", null))
            assertEquals(projectId, preferences.getString("opened_record", null))
            assertEquals(projectId, preferences.getString("active_1", null))
            assertFalse(preferences.getBoolean("animations", true))
            assertFalse(preferences.getBoolean("haptics", true))
            checks.put("Selected context, project route, active record and motion/haptic preferences retained")

            val session = AssistantSessionStore(File(context.filesDir, "assistant/sessions-v1.json")).load()
                ?: error("Assistant session was not restored")
            assertEquals(expected.getString("conversationId"), session.selectedId)
            assertEquals(expected.getString("assistantDraft"), session.current.draft)
            assertFalse(session.toolsEnabled)
            val turn = session.current.turns.single { it.id == expected.getString("turnId") }
            assertEquals(expected.getString("partialAnswer"), turn.answer)
            assertEquals(AssistantTurnState.INTERRUPTED, turn.state)
            assertEquals(listOf(knowledgeId), turn.sourceIds)
            assertTrue(turn.detail.contains("Partial text is retained"))
            checks.put("Assistant draft/partial text retained and STREAMING recovered as INTERRUPTED; no inference asserted")

            ActivityScenario.launch(MainActivity::class.java).use {
                compose.waitUntil(15_000) {
                    compose.onAllNodesWithText(expected.getString("projectTitle")).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText(expected.getString("projectTitle")).assertIsDisplayed()
                compose.waitUntil(15_000) {
                    compose.onAllNodesWithText(expected.getString("taskTitle")).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText(expected.getString("taskTitle")).assertIsDisplayed()
                val screenshot = File(evidenceDirectory(), "restart-project.png")
                screenshot.outputStream().use { output ->
                    assertTrue(compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, output))
                }
            }
            checks.put("Production MainActivity reopened the persisted project and displayed its persisted task")
            evidence.put("result", "passed").put("verified_after_process_restart", true)
                .put("project_id", projectId).put("screenshot", "restart-project.png")
            saveEvidence(evidence)
        } catch (failure: Throwable) {
            evidence.put("result", "failed").put("verified_after_process_restart", false).put("failure", failure.toString())
            runCatching { saveEvidence(evidence) }
            throw failure
        }
    }

    private fun requireIsolatedEmulator() {
        assumeTrue("Separate isolated-emulator phase only", InstrumentationRegistry.getArguments().getString("isolatedEmulator") == "true")
        assertTrue("Refusing test mutations outside an Android emulator", Build.HARDWARE in setOf("ranchu", "goldfish") || Build.PRODUCT.contains("sdk"))
    }

    private fun evidence(phase: String) = JSONObject().put("phase", phase).put("package", context.packageName)
        .put("version_name", BuildConfig.VERSION_NAME).put("version_code", BuildConfig.VERSION_CODE)
        .put("api", Build.VERSION.SDK_INT).put("device", Build.MODEL).put("fingerprint", Build.FINGERPRINT)
        .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList())).put("timestamp_utc", OpenMineObjectFormat.now())
        .put("test_conditions", "Two independent Android instrumentation invocations; external adb force-stop between phases; offline production stores; test-only partial answer fixture")

    private fun evidenceDirectory() = File(context.getExternalFilesDir(null) ?: error("External evidence directory unavailable"), "verification").apply { check(isDirectory || mkdirs()) }
    private fun saveEvidence(value: JSONObject) {
        writeSynced(File(evidenceDirectory(), "restart-persistence.json"), value.toString(2))
        println("OPEN_MINE_RESTART_EVIDENCE $value")
    }
    private fun writeSynced(file: File, text: String) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync() }
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun fixture(type: String, title: String, keyword: String) = OpenMineObjectFormat.template(
        type = type, title = title, summary = "Offline process-restart fixture", tags = "test, persistence", source = "instrumentation",
        purpose = "Verify production storage across process death", facts = keyword, procedure = "NONE", constraints = "Test fixture only",
        examples = "NONE", keywords = keyword, aliases = "NONE", triggers = "NONE", project = "NONE", model = "NONE", skill = "NONE",
        tool = "NONE", mission = "NONE", query = keyword, whenText = "Restart persistence test", exclude = "NONE", verification = "DRAFT",
        verificationSource = "Android instrumentation", notes = "Test-only fixture; no model response or tool execution is represented",
    )
}
