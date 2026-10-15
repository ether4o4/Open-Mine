package com.openmine

import com.openmine.sandbox.BundledEngine
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CancellationException

class BundledEngineTest {
    @Test fun verifiesActualPackagedEngineAndPreservesExistingFileOnFailure() {
        val root = kotlin.io.path.createTempDirectory("bundled-engine-test").toFile()
        try {
            val asset = listOf(File("src/main/assets/sandbox/llama-server-aarch64-musl"), File("app/src/main/assets/sandbox/llama-server-aarch64-musl")).first { it.isFile }
            val target = File(root, "llama-server")
            target.writeText("old")
            assertThrows(IllegalStateException::class.java) { BundledEngine.install("corrupt".byteInputStream(), target) }
            assertEquals("old", target.readText())
            assertThrows(CancellationException::class.java) { BundledEngine.install(asset.inputStream(), target) { throw CancellationException() } }
            assertEquals("old", target.readText())
            BundledEngine.install(asset.inputStream(), target)
            assertEquals(BundledEngine.ARM64_BYTES, target.length())
            assertTrue(target.canExecute())
            assertEquals(listOf("llama-server"), root.listFiles()!!.map { it.name })
        } finally { root.deleteRecursively() }
    }
}
