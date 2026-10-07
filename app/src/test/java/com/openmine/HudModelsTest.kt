package com.openmine

import org.junit.Assert.*
import org.junit.Test

class HudModelsTest {
    @Test fun projectionUsesSavedIdentityProvenanceAndContents() {
        val record = StrictObject(mapOf("OBJECT_ID" to "model.saved", "OBJECT_TYPE" to "MODEL", "OBJECT_TITLE" to "Saved model description",
            "OBJECT_STATUS" to "DRAFT", "OBJECT_VERSION" to "1", "OBJECT_SUMMARY" to "A saved description, not a running model.",
            "OBJECT_TAGS" to "local, draft", "OBJECT_SOURCE" to "Personal notes", "OBJECT_UPDATED" to "2026-10-07T01:02:03Z"),
            mapOf("CONTENT" to mapOf("CONTENT_FACTS" to "Stored facts", "CONTENT_PROCEDURE" to "Stored procedure")), "")
        val projected = hudRecordContent(record)
        assertEquals(record.id, projected.id)
        assertEquals(record.title, projected.title)
        assertEquals("DRAFT", projected.metrics.toMap()["Record state"])
        assertEquals("Personal notes", projected.metrics.toMap()["Source"])
        assertEquals("2026-10-07", projected.metrics.toMap()["Updated"])
        assertEquals("Stored facts", projected.tabs["CONTENT"])
        assertEquals("Stored procedure", projected.tabs["PROCEDURE"])
        assertEquals(listOf("local", "draft"), projected.tags)
        assertFalse(projected.metrics.any { it.first == "Status" || it.second in listOf("Ready", "Connected", "Available") })
    }

    @Test fun missingFieldsRemainExplicitlyEmpty() {
        val record = StrictObject(mapOf("OBJECT_ID" to "knowledge.empty", "OBJECT_TYPE" to "KNOWLEDGE", "OBJECT_TITLE" to "Empty description", "OBJECT_STATUS" to "DRAFT", "OBJECT_TAGS" to "NONE"), emptyMap(), "")
        val projected = hudRecordContent(record)
        assertTrue(projected.tags.isEmpty())
        assertEquals("Not recorded", projected.metrics.toMap()["Source"])
        assertEquals("No relationships recorded.", projected.tabs["RELATED"])
        assertEquals("No procedure recorded. Saved procedures do not run automatically.", projected.tabs["PROCEDURE"])
    }
}
