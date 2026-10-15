package com.openmine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.util.UUID

/** Exercises Android app-owned filesystem publication, not GGUF validation or inference. */
@RunWith(AndroidJUnit4::class)
class ModelImportPublicationTest {
    @Test fun modelPublicationPreservesBytesAndDuplicateImportsInsideAppStorage() {
        verifyPublication("linux-sandbox/home/.morsvitaest/llm/models")
    }

    @Test fun modelPublicationSupportsDestinationDirectoriesWithSpacesAndUnicode() {
        verifyPublication("linux-sandbox/home/.morsvitaest/llm/models/模型 storage")
    }

    private fun verifyPublication(relativeDirectory: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Keep fixtures under the real target app's filesDir and its UID/SELinux domain.
        val ownedRoot = Files.createTempDirectory(context.filesDir.toPath(), "model-publication-").toFile()
        try {
            val directory = File(ownedRoot, relativeDirectory)
            assertTrue("Create this test's destination directory", directory.mkdirs())
            val displayName = "Kiwi App Qwen2.5-Coder-3B-Instruct-abliterated-Q4_K_M.gguf"
            val firstPayload = ByteArray(4097) { (it * 31).toByte() }
            val firstPartial = File(directory, "${UUID.randomUUID()}.part").apply {
                writeBytes(firstPayload)
            }

            val firstPublished = ModelImportName.publish(firstPartial, directory, displayName)

            assertEquals(displayName, firstPublished.name)
            assertEquals(directory.canonicalFile, firstPublished.parentFile.canonicalFile)
            assertArrayEquals(firstPayload, firstPublished.readBytes())
            assertFalse("Successful publication removes its partial file", firstPartial.exists())

            val secondPayload = ByteArray(8193) { (255 - it * 17).toByte() }
            val secondPartial = File(directory, "${UUID.randomUUID()}.part").apply {
                writeBytes(secondPayload)
            }

            val secondPublished = ModelImportName.publish(secondPartial, directory, displayName)

            assertNotEquals("A duplicate receives a different destination", firstPublished.name, secondPublished.name)
            assertTrue(secondPublished.name.startsWith(displayName.removeSuffix(".gguf") + "-"))
            assertTrue(secondPublished.name.endsWith(".gguf"))
            assertEquals(directory.canonicalFile, secondPublished.parentFile.canonicalFile)
            assertArrayEquals("An existing model remains unchanged", firstPayload, firstPublished.readBytes())
            assertArrayEquals(secondPayload, secondPublished.readBytes())
            assertFalse("Duplicate publication removes its partial file", secondPartial.exists())
            assertEquals(
                "Only the two completed models remain; no temporary files or directories",
                setOf(firstPublished.name, secondPublished.name),
                directory.listFiles()?.map { it.name }?.toSet()
            )
        } finally {
            // Never clear actual model files, app data, or another test's directory.
            ownedRoot.deleteRecursively()
        }
    }
}
