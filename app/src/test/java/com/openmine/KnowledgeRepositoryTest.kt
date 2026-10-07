package com.openmine

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class KnowledgeRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun record(title: String = "Engine notes", fact: String = "cobalt turbine") = OpenMineObjectFormat.template(
        "KNOWLEDGE", title, "Source grounded context", "engineering", "manual",
        "Maintenance", fact, "Inspect then replace", "NONE", "NONE", "engine", "NONE",
        "maintenance", "NONE", "NONE", "NONE", "NONE", "NONE", "engine service", "maintenance",
        "NONE", "DRAFT", "manual", "Review pending"
    )
    private fun source(root: File, record: StrictObject) = File(root, "open_mine_objects/${record.id}.omd")
    private fun index(root: File) = File(root, "open_mine_index.json")

    @Test fun indexedSearchSurvivesRestartAndDoesNotRebuildValidIndex() {
        val root = temporary.newFolder(); var changes = 0
        val repository = KnowledgeRepository(root) { changes++ }
        val objectValue = record()
        assertTrue(repository.import(objectValue.raw).valid)
        val bytes = index(root).readBytes(); val writes = changes
        assertEquals(listOf(objectValue.id), repository.search("COBALT").map { it.id })
        assertEquals(listOf(objectValue.id), KnowledgeRepository(root).search("turbine").map { it.id })
        assertArrayEquals(bytes, index(root).readBytes())
        assertEquals("A validated index must be reused", writes, changes)
        val payload = JSONObject(String(bytes)).getJSONObject("payload")
        val row = payload.getJSONArray("records").getJSONObject(0)
        assertTrue(row.getJSONArray("TERMS").toString().contains("cobalt"))
        assertTrue(repository.inspect().chunkCount > 0)
        assertTrue(repository.inspect().issues.isEmpty())
    }

    @Test fun staleIndexDetectsSameLengthSameTimestampSourceReplacement() {
        val root = temporary.newFolder(); val repository = KnowledgeRepository(root)
        val old = record(fact = "cobalt turbine"); assertTrue(repository.import(old.raw).valid)
        val file = source(root, old); val modified = file.lastModified(); val size = file.length()
        file.writeText(file.readText().replace("cobalt turbine", "silver turbine"))
        assertEquals(size, file.length()); assertTrue(file.setLastModified(modified))
        assertTrue(repository.search("cobalt").isEmpty())
        assertEquals(old.id, repository.search("silver").single().id)
        assertTrue(file.readText().contains("silver turbine"))
    }

    @Test fun corruptTruncatedAndLegacyIndexesRepairWithoutChangingSourceBytes() {
        val root = temporary.newFolder(); var changes = 0
        val repository = KnowledgeRepository(root) { changes++ }; val objectValue = record()
        assertTrue(repository.import(objectValue.raw).valid)
        val expected = source(root, objectValue).readBytes()
        val correctIndex = index(root).readBytes()
        for (bad in listOf("{", "{\"old.unversioned\":{\"TERMS\":[\"wrong\"]}}", "not json")) {
            index(root).writeText(bad)
            val before = changes
            assertEquals(objectValue.id, repository.search("cobalt").single().id)
            assertTrue(changes > before)
            assertArrayEquals(expected, source(root, objectValue).readBytes())
            assertArrayEquals("Rebuild is deterministic", correctIndex, index(root).readBytes())
        }
        val corrupted = JSONObject(index(root).readText())
        corrupted.getJSONObject("payload").getJSONArray("records").getJSONObject(0).put("TERMS", JSONArray().put("fabricated"))
        index(root).writeText(corrupted.toString())
        assertTrue(repository.search("fabricated").isEmpty())
        assertEquals(objectValue.id, repository.search("cobalt").single().id)
        assertArrayEquals(expected, source(root, objectValue).readBytes())
    }

    @Test fun malformedUtf8AndDuplicateSourcesAreReportedPreservedAndExcluded() {
        val root = temporary.newFolder(); val repository = KnowledgeRepository(root); val objectValue = record()
        assertTrue(repository.import(objectValue.raw).valid)
        val malformed = File(root, "open_mine_objects/invalid.omd")
        val badBytes = byteArrayOf(0xc3.toByte(), 0x28); malformed.writeBytes(badBytes)
        val snapshot = repository.inspect()
        assertEquals(listOf(objectValue.id), snapshot.records.map { it.id })
        assertTrue(snapshot.issues.any { it.contains("invalid.omd") })
        assertArrayEquals(badBytes, malformed.readBytes())
        val duplicate = File(root, "open_mine_objects/another-name.omd").apply { writeText(objectValue.raw) }
        val duplicateSnapshot = repository.inspect()
        assertTrue(duplicateSnapshot.issues.any { it.contains("Duplicate OBJECT_ID") })
        assertTrue(repository.search("cobalt").isEmpty())
        assertFalse(repository.import(objectValue.raw).valid)
        assertEquals(objectValue.raw, duplicate.readText())
        assertEquals(objectValue.raw, source(root, objectValue).readText())
    }

    @Test fun updatesDeletesAndDuplicateRejectionsKeepIndexAndSourcesConsistent() {
        val root = temporary.newFolder(); val repository = KnowledgeRepository(root); val objectValue = record()
        assertTrue(repository.import(objectValue.raw).valid)
        assertFalse(repository.import(objectValue.raw.replace("cobalt", "silver")).valid)
        assertEquals(objectValue.raw, source(root, objectValue).readText())
        assertFalse(repository.update(objectValue.id, objectValue.raw.replace(objectValue.id, "knowledge.other")).valid)
        assertTrue(repository.update(objectValue.id, objectValue.raw.replace("cobalt", "silver")).valid)
        assertTrue(repository.search("cobalt").isEmpty())
        assertEquals(objectValue.id, repository.search("silver").single().id)
        repository.delete(objectValue.id)
        assertFalse(source(root, objectValue).exists())
        assertTrue(KnowledgeRepository(root).search("silver").isEmpty())
    }

    @Test fun indexWriteFailureRollsBackImportAndUpdateWithoutLosingRecords() {
        val root = temporary.newFolder(); val repository = KnowledgeRepository(root); val objectValue = record()
        assertTrue(repository.import(objectValue.raw).valid)
        val before = source(root, objectValue).readBytes()
        assertTrue(index(root).delete()); assertTrue(index(root).mkdir())
        File(index(root), "prevent-directory-replacement").writeText("occupied")
        assertFalse(repository.update(objectValue.id, objectValue.raw.replace("cobalt", "silver")).valid)
        assertArrayEquals(before, source(root, objectValue).readBytes())
        val next = record("New source")
        assertFalse(repository.import(next.raw).valid)
        assertFalse(source(root, next).exists())
        assertArrayEquals(before, source(root, objectValue).readBytes())
    }

    @Test fun oldAtomicFileBackupRecoversOnceAndDoesNotResurrectDeletedObject() {
        val root = temporary.newFolder(); val objectValue = record(); val file = source(root, objectValue)
        assertTrue(file.parentFile!!.mkdirs())
        val backup = File(file.path + ".bak").apply { writeText(objectValue.raw) }
        val repository = KnowledgeRepository(root)
        assertEquals(objectValue.id, repository.inspect().records.single().id)
        assertEquals(objectValue.raw, file.readText())
        assertFalse(backup.exists())
        val retained = file.parentFile!!.listFiles()!!.single { it.name.contains(".bak.preserved-") }
        assertEquals(objectValue.raw, retained.readText())
        repository.delete(objectValue.id)
        assertTrue(KnowledgeRepository(root).inspect().records.isEmpty())
        assertEquals(objectValue.raw, retained.readText())
    }

    @Test fun indexMigrationPreservesExistingRecordNamesAndRejectsPathIds() {
        val root = temporary.newFolder(); val objectValue = record(); val dir = File(root, "open_mine_objects").apply { mkdirs() }
        val legacy = File(dir, "legacy-name.omd").apply { writeText(objectValue.raw) }
        index(root).writeText("{}")
        val repository = KnowledgeRepository(root)
        assertEquals(objectValue.id, repository.search("cobalt").single().id)
        assertTrue(repository.update(objectValue.id, objectValue.raw.replace("cobalt", "silver")).valid)
        assertTrue(legacy.readText().contains("silver")); assertFalse(source(root, objectValue).exists())
        assertFalse(repository.import(objectValue.raw.replace(objectValue.id, "../../escape")).valid)
        assertThrows(IllegalArgumentException::class.java) { repository.delete("../../escape") }
    }
}
