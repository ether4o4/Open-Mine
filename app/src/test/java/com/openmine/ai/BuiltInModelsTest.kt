package com.openmine.ai

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BuiltInModelsTest {
    @get:Rule val folder = TemporaryFolder()
    @Test fun modelsArePinnedToImmutableRevisionsAndDigests() {
        for (m in listOf(BuiltInModels.LFM3)) {
            assertTrue(m.url, Regex("/resolve/[0-9a-f]{40}/").containsMatchIn(m.url))
            assertTrue(Regex("[0-9a-f]{64}").matches(m.sha256)); assertTrue(m.sizeBytes > 0)
        }
        assertEquals("b22df65673b54ed326f18f74826d76f3b65dadc237ffd6306cd52be04544827d", BuiltInModels.LFM3.sha256)
    }
    @Test fun installedRequiresReceiptAndExactSize() {
        val model = BuiltInModels.LFM3
        val file = BuiltInModels.file(folder.root, model); file.writeText("partial")
        assertFalse(BuiltInModels.installed(folder.root, model))
        File(file.parentFile, file.name + ".verified").writeText(model.sha256)
        assertFalse("size mismatch must not count as installed", BuiltInModels.installed(folder.root, model))
    }
    @Test fun serverArgumentsBindLoopbackWithKeyAndCapThinking() {
        val args = LocalLlmServer.arguments(File("/m.gguf"), 4, 8192, "k".repeat(48), 8181).toList()
        assertEquals("127.0.0.1", args[args.indexOf("--host") + 1]); assertEquals("k".repeat(48), args[args.indexOf("--api-key") + 1])
        assertTrue("--jinja" in args); assertEquals("384", args[args.indexOf("--reasoning-budget") + 1])
    }
    @Test fun installKeyIsStableAndLongEnough() {
        val a = LocalLlmServer.installKey(folder.root); val b = LocalLlmServer.installKey(folder.root)
        assertEquals(a, b); assertTrue(a.length >= 32)
    }
}
