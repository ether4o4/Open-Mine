package com.openmine

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LibraryCommitRecoveryTest {
    @Test fun failedWorkspaceSaveAfterImportRetriesWithoutImportingAgainOrResettingNewSelection() = runBlocking {
        val directory = Files.createTempDirectory("open-mine-commit-recovery").toFile()
        try {
            val repository = KnowledgeRepository(directory)
            val record = libraryTemplate("KNOWLEDGE", "Committed import", "Saved source")
            assertTrue(repository.import(record.raw).valid)
            var workspace = LibrarySession()
            var workspaceUpdates = 0
            val recovery = LibraryCommitRecovery()
            recovery.markCommitted("Imported and indexed Committed import.") {
                workspaceUpdates += 1
                workspace = workspace.copy(selectedId = record.id)
            }
            val blockedParent = directory.resolve("not-a-directory").apply { writeText("existing bytes") }
            val blockedSession = LibrarySessionStore(blockedParent.resolve("session.json"))
            assertTrue(runCatching { recovery.finish { blockedSession.save(workspace) } }.isFailure)
            assertEquals(record.id, repository.inspect().records.single().id)
            workspace = workspace.copy(query = "new query after the disk failure")
            val workingSession = LibrarySessionStore(directory.resolve("session.json"))
            assertEquals("Imported and indexed Committed import.", recovery.finish { workingSession.save(workspace) })
            assertEquals(1, workspaceUpdates)
            assertEquals("new query after the disk failure", workingSession.load().query)
            assertEquals(record.id, repository.inspect().records.single().id)
            assertEquals("existing bytes", blockedParent.readText())
        } finally { directory.deleteRecursively() }
    }

    @Test fun failedWorkspaceSaveAfterDeleteDoesNotDeleteAgainOrRestoreSource() = runBlocking {
        val directory = Files.createTempDirectory("open-mine-delete-recovery").toFile()
        try {
            val repository = KnowledgeRepository(directory)
            val record = libraryTemplate("KNOWLEDGE", "Deleted record", "Saved source")
            assertTrue(repository.import(record.raw).valid)
            repository.delete(record.id)
            var cleared = 0
            val recovery = LibraryCommitRecovery()
            recovery.markCommitted("Deleted record; retrieval index updated.") { cleared += 1 }
            assertTrue(runCatching { recovery.finish { throw java.io.IOException("Draft volume unavailable") } }.isFailure)
            val sessionStore = LibrarySessionStore(directory.resolve("session.json"))
            recovery.finish { sessionStore.save(LibrarySession()) }
            assertEquals(1, cleared)
            assertTrue(repository.inspect().records.isEmpty())
            assertFalse(directory.resolve("open_mine_objects/${record.id}.omd").exists())
        } finally { directory.deleteRecursively() }
    }
}
