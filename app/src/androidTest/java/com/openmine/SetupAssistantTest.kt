package com.openmine

import android.net.Uri
import android.os.Build
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class SetupAssistantTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun probeReadsOnlyABoundedPrefixAndDiagnosesRealFiles() {
        val dir = File(context.cacheDir, "setup-assistant-test").apply { mkdirs() }
        try {
            val page = File(dir, "model.gguf").apply { writeText("<!DOCTYPE html><html>Hugging Face</html>") }
            val pageCandidate = SetupProbe.inspect(context, Uri.fromFile(page))
            assertNotNull(pageCandidate.header)
            assertEquals(ImportProblem.FORMAT, ModelImportDoctor.diagnose(pageCandidate, Long.MAX_VALUE, Long.MAX_VALUE).problem)

            val large = File(dir, "large.bin").apply { outputStream().use { out -> repeat(3) { out.write(ByteArray(1024 * 1024)) } } }
            assertEquals(ModelImportDoctor.PROBE_BYTES, SetupProbe.inspect(context, Uri.fromFile(large)).header?.size)

            val missing = SetupProbe.inspect(context, Uri.fromFile(File(dir, "absent.gguf")))
            assertNull(missing.header)
            assertEquals(ImportProblem.ACCESS, ModelImportDoctor.diagnose(missing, Long.MAX_VALUE, Long.MAX_VALUE).problem)
        } finally { dir.deleteRecursively() }
    }

    @Test fun setupAssistantShowsRealFindingsAndRecordedOperations() {
        assertTrue("Use a disposable emulator", Build.HARDWARE in setOf("ranchu", "goldfish") || Build.PRODUCT.contains("sdk"))
        val label = "Instrumentation operation ${UUID.randomUUID()}"
        val journal = com.openmine.sandbox.OpenMineRuntime.get(context).journal
        journal.finish(journal.begin("Linux shell", label, RetryAction.VERIFY_SHELL), OperationState.FAILED, "fixture failure detail")
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            SetupAssistantPanel(context, onOpenModels = {}, onOpenChat = {})
        } } }
        compose.onNodeWithText("SETUP ASSISTANT").assertExists()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTagPrefix("finding-")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("failed · $label", substring = true).assertExists()
        compose.onNodeWithText("fixture failure detail", substring = true).assertExists()
        compose.onNodeWithTag("doctor-pasted").performTextInput("/storage/emulated/0/Download/qwen.gguf")
        compose.onNodeWithText("The file appears to be in Downloads", substring = true).assertExists()
    }

    private fun hasTestTagPrefix(prefix: String) = SemanticsMatcher("tag starts with $prefix") {
        it.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { null }?.startsWith(prefix) == true
    }
}
