package com.openmine

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

class ProjectStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun store(id: String = "project.existing-id") = ProjectStore(temporary.root, id)

    @Test fun tasksSurviveRestartAndKeepProjectIdentity() {
        val original = store().saveTask(null, "Ship the APK", "Test on Android", ProjectTaskStatus.TODO)
        val restarted = store()
        assertEquals(original, restarted.tasks().single())
        val updated = restarted.saveTask(original.id, "Verify the APK", "Verify checksum", ProjectTaskStatus.IN_PROGRESS)
        assertEquals(original.id, updated.id)
        assertEquals(ProjectTaskStatus.IN_PROGRESS, store().tasks().single().status)
        restarted.deleteTask(original.id)
        assertTrue(store().tasks().isEmpty())
        assertTrue(File(temporary.root, "open_mine_projects/project.existing-id/tasks.json").isFile)
    }

    @Test fun filesAndDraftsAreIsolatedPerProjectAndSurviveRestart() {
        val workspace = store()
        val file = workspace.createText("notes.txt", "Saved text")
        workspace.saveDraft(ProjectDraft(file.name, "Unsaved text", file.modified))
        assertEquals("Unsaved text", store().readDraft("notes.txt")!!.text)
        assertEquals("Saved text", store().readText("notes.txt"))
        assertNull(store("project.other").readDraft("notes.txt"))
        assertTrue(store("project.other").listFiles().isEmpty())
        store().saveText(file.name, "Unsaved text", file.modified)
        assertEquals("Unsaved text", store().readText("notes.txt"))
        assertNull(store().readDraft("notes.txt"))
        // A delayed lifecycle flush must not recreate an already-saved draft.
        workspace.saveDraft(ProjectDraft(file.name, "Unsaved text", file.modified))
        assertNull(workspace.readDraft(file.name))
        assertTrue(runCatching { workspace.createText("notes.txt", "overwrite") }.isFailure)
        assertEquals("Unsaved text", workspace.readText("notes.txt"))
    }

    @Test fun rejectsTraversalInvalidNamesAndSymbolicLinks() {
        val workspace = store()
        for (name in listOf("../escape", "/absolute", "a/b", "a\\b", ".", "..", ".hidden", "a\u0000b", " space ", "a:b")) {
            assertTrue(name, runCatching { workspace.createText(name) }.isFailure)
        }
        assertTrue(runCatching { store("../../escape") }.isFailure)
        workspace.createText("normal.txt", "safe")
        val external = File(temporary.root, "outside.txt").apply { writeText("preserve") }
        Files.createSymbolicLink(File(temporary.root, "open_mine_projects/project.existing-id/files/link.txt").toPath(), external.toPath())
        assertTrue(runCatching { workspace.readText("link.txt") }.isFailure)
        assertTrue(runCatching { workspace.saveText("link.txt", "bad", external.lastModified()) }.isFailure)
        assertEquals("preserve", external.readText())
    }

    @Test fun interruptedOrOversizedImportsLeaveNoPartialFile() {
        val workspace = store()
        val oversized = object : java.io.InputStream() {
            var remaining = ProjectStore.MAX_FILE_BYTES + 1
            override fun read(): Int = if (remaining-- > 0) 65 else -1
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (remaining <= 0) return -1
                val count = minOf(length.toLong(), remaining).toInt()
                buffer.fill(65, offset, offset + count); remaining -= count
                return count
            }
        }
        assertTrue(runCatching { workspace.importFile("huge.bin", oversized) }.isFailure)
        assertTrue(workspace.listFiles().isEmpty())
        val failing = object : java.io.InputStream() { override fun read(): Int = throw java.io.IOException("provider disappeared") }
        assertTrue(runCatching { workspace.importFile("failed.txt", failing) }.isFailure)
        assertTrue(workspace.listFiles().isEmpty())
        assertTrue(File(temporary.root, "open_mine_projects/project.existing-id/files").listFiles().orEmpty().isEmpty())
    }

    @Test fun binaryImportExportIsLosslessAndCannotBeEditedAsText() {
        val bytes = byteArrayOf(0, 1, 2, -1, -2)
        val workspace = store()
        workspace.importFile("sample.bin", ByteArrayInputStream(bytes))
        val exported = ByteArrayOutputStream()
        workspace.exportFile("sample.bin", exported)
        assertArrayEquals(bytes, exported.toByteArray())
        assertTrue(runCatching { workspace.readText("sample.bin") }.isFailure)
        assertEquals("sample (2).bin", workspace.uniqueName("sample.bin"))
        workspace.deleteFile("sample.bin")
        assertTrue(workspace.listFiles().isEmpty())
    }

    @Test fun refusesConflictingSavesAndPreservesDraft() {
        val workspace = store()
        val original = workspace.createText("notes.txt", "one")
        workspace.saveDraft(ProjectDraft(original.name, "draft", original.modified))
        val actual = File(temporary.root, "open_mine_projects/project.existing-id/files/notes.txt")
        actual.writeText("another edit")
        actual.setLastModified(original.modified + 2000)
        assertTrue(runCatching { workspace.saveText(original.name, "draft", original.modified) }.isFailure)
        assertEquals("another edit", workspace.readText(original.name))
        assertEquals("draft", workspace.readDraft(original.name)!!.text)
    }

    @Test fun unsupportedTaskVersionsAreNotOverwritten() {
        val workspace = store()
        workspace.saveTask(null, "Keep", "", ProjectTaskStatus.TODO)
        val taskFile = File(temporary.root, "open_mine_projects/project.existing-id/tasks.json")
        val newer = taskFile.readText().replace("\"version\":1", "\"version\":2")
        taskFile.writeText(newer)
        assertTrue(runCatching { workspace.saveTask(null, "New", "", ProjectTaskStatus.TODO) }.isFailure)
        assertEquals(newer, taskFile.readText())
    }
}
