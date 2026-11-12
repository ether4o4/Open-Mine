package com.openmine.ai

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NoteTaggerTest {
    @get:Rule val folder = TemporaryFolder()
    private val docs = listOf(KnowledgeDoc("doc.a", "Inverter install notes", "project:cabin", "DC input lands on terminals 1 and 2 through a 150 A fuse."),
        KnowledgeDoc("doc.b", "Dog care", "memory", "Max gets kibble twice a day."))

    @Test fun tagsAreGeneratedOncePersistedAndRegeneratedOnlyWhenContentChanges() {
        val cache = File(folder.root, "tags.json"); var calls = 0
        val tagger = NoteTagger(cache) { _, user -> calls++; if (user.contains("Inverter")) "Summary: power converter wiring. Keywords: overcurrent protection, solar" else "pet feeding" }
        assertEquals(2, tagger.tagMissing(docs)); assertEquals(2, calls)
        assertEquals(0, NoteTagger(cache) { _, _ -> calls++; "" }.tagMissing(docs)); assertEquals(2, calls)
        val changed = docs.map { if (it.id == "doc.b") it.copy(text = "Max gets kibble three times a day.") else it }
        assertEquals(1, NoteTagger(cache) { _, _ -> calls++; "pet" }.missing(changed).size)
    }
    @Test fun tagsImproveRankingButNeverAppearInReadPassages() {
        val tagger = NoteTagger(File(folder.root, "t.json")) { _, user -> if (user.contains("Inverter")) "overcurrent protection power converter solar charger" else "pet" }
        tagger.tagMissing(docs)
        assertTrue(KnowledgeIndex(docs).search("overcurrent protection power converter").isEmpty())
        val index = KnowledgeIndex(tagger.apply(docs))
        val hit = index.search("overcurrent protection power converter").first()
        assertEquals("doc.a", hit.docId)
        assertFalse(index.read(hit.id)!!.contains("overcurrent"))
    }
    @Test fun failedGenerationLeavesTheDocumentUntagged() {
        val tagger = NoteTagger(File(folder.root, "f.json")) { _, _ -> error("helper crashed") }
        assertEquals(0, tagger.tagMissing(docs)); assertEquals(2, tagger.missing(docs).size)
    }
    @Test fun sanitizeRemovesNumberingRepetitionAndBoundsLength() {
        val s = NoteTagger.sanitize("Summary: x\n1. Inverter specs\n2. Inverter, inverter, inverter fuse\n" + "word".repeat(400))
        assertFalse(s.contains("\n")); assertFalse(s.contains("1. ")); assertTrue(s.length <= 600)
        assertEquals(1, s.split(" ").count { it.trim(',').equals("inverter", true) })
    }
}
