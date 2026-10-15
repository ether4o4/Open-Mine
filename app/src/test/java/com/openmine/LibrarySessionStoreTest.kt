package com.openmine

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class LibrarySessionStoreTest {
    @Test fun editorAndWorkspaceStateSurviveRestart() {
        val directory = Files.createTempDirectory("open-mine-library-draft").toFile()
        try {
            val file = directory.resolve("session.json")
            val expected = LibrarySession(query = "saved search", selectedId = "knowledge.example", editorActive = true,
                editId = "knowledge.example", rawDraft = "unsaved\nUnicode ✓\n\"quoted\"", creating = false,
                createType = "TOOL", createTitle = "Example", createSummary = "Summary", exportId = "knowledge.example", editBaseRaw = "original canonical record")
            LibrarySessionStore(file).save(expected)
            assertEquals(expected, LibrarySessionStore(file).load())
            assertFalse(directory.resolve("session.json.pending").exists())
        } finally { directory.deleteRecursively() }
    }

    @Test fun corruptSessionIsReportedAndPreserved() {
        val directory = Files.createTempDirectory("open-mine-library-corruption").toFile()
        try {
            val file = directory.resolve("session.json")
            file.writeText("broken existing draft")
            assertTrue(runCatching { LibrarySessionStore(file).load() }.isFailure)
            assertEquals("broken existing draft", file.readText())
        } finally { directory.deleteRecursively() }
    }

    @Test fun unsupportedSchemaDoesNotBecomeAnEmptyDraft() {
        val future = LibrarySessionStore.encode(LibrarySession()).replace("\"schema\":1", "\"schema\":2")
        assertTrue(runCatching { LibrarySessionStore.decode(future) }.isFailure)
    }

    @Test fun blankLibraryRestoresWithoutSeededRecordsOrClaims() {
        val directory = Files.createTempDirectory("open-mine-empty-library").toFile()
        try { assertEquals(LibrarySession(), LibrarySessionStore(directory.resolve("missing.json")).load()) }
        finally { directory.deleteRecursively() }
    }

    @Test fun completedFirstWriteRecoversAfterInterruptedRename() {
        val directory = Files.createTempDirectory("open-mine-interrupted-draft").toFile()
        try {
            val file = directory.resolve("session.json")
            val pending = directory.resolve("session.json.pending")
            val expected = LibrarySession(editorActive = true, rawDraft = "An uncommitted draft")
            pending.writeText(LibrarySessionStore.encode(expected))
            assertEquals(expected, LibrarySessionStore(file).load())
            assertTrue(file.isFile)
            assertFalse(pending.exists())
        } finally { directory.deleteRecursively() }
    }
}
