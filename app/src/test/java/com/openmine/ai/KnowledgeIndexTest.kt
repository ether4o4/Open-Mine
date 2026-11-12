package com.openmine.ai

import org.junit.Assert.*
import org.junit.Test

class KnowledgeIndexTest {
    private val docs = listOf(
        KnowledgeDoc("doc.solar", "Off-grid system", "project:cabin", "Battery bank maintenance every month.\n\nInverter wiring: connect the DC input from the solar panels to terminals 1 and 2, then the AC output to the breaker."),
        KnowledgeDoc("doc.groceries", "Groceries", "memory", "eggs, milk, bread, inverted sugar syrup"),
        KnowledgeDoc("knowledge.git", "Git notes", "library:knowledge", "Use git rebase carefully. " + "Long reference text. ".repeat(400)),
    )
    @Test fun bm25RanksTheRelevantPassageFirstWithStemming() {
        val hits = KnowledgeIndex(docs).search("how do I wire the solar inverters")
        assertEquals("doc.solar", hits.first().docId)
        assertTrue(hits.first().preview.contains("Inverter wiring"))
    }
    @Test fun longDocumentsAreSplitIntoBoundedPassages() {
        val index = KnowledgeIndex(docs)
        assertTrue(index.passages.count { it.doc.id == "knowledge.git" } >= 3)
        assertTrue(index.passages.all { it.text.length <= KnowledgeIndex.PASSAGE_CHARS })
    }
    @Test fun readSliceIsBoundedAndUnknownIdsReturnNull() {
        val index = KnowledgeIndex(docs)
        val id = index.search("git rebase").first().id
        assertTrue(index.read(id)!!.length <= KnowledgeIndex.MAX_SLICE + 200)
        assertNull(index.read("doc.missing#0"))
    }
    @Test fun summaryIsTinyAndNeverContainsContent() {
        val summary = KnowledgeIndex(docs).summary()
        assertFalse(summary.contains("terminals")); assertTrue(summary.length < 200)
        assertTrue(summary.contains("memory 1 item"))
        assertEquals("Knowledge: none imported yet.", KnowledgeIndex(emptyList()).summary())
    }
    @Test fun noMatchesAndEmptyQueriesReturnNothing() {
        assertTrue(KnowledgeIndex(docs).search("quantum chromodynamics").isEmpty())
        assertTrue(KnowledgeIndex(docs).search("the and of").isEmpty())
    }
}
