package com.openmine

import org.junit.Assert.*
import org.junit.Test

class ObjectFormatTest {
    private fun record() = OpenMineObjectFormat.template(
        "KNOWLEDGE", "Engine notes", "Source grounded context", "engine, Engine", "manual",
        "Maintenance", "Check oil", "Inspect\nthen replace", "NONE", "NONE", "engine", "NONE",
        "maintenance", "NONE", "NONE", "NONE", "NONE", "NONE", "engine service", "maintenance",
        "NONE", "DRAFT", "manual", "Review pending"
    )
    @Test fun validTemplateRoundTripsWithoutLosingContent() {
        val original = record()
        val result = OpenMineObjectFormat.validate(original.raw)
        assertTrue(result.errors.toString(), result.valid)
        assertEquals("Inspect then replace", result.normalized!!.sections["CONTENT"]!!["CONTENT_PROCEDURE"])
        assertEquals("engine", result.normalized!!.fields["OBJECT_TAGS"])
        assertEquals(original.raw, result.normalized!!.raw)
    }
    @Test fun rejectsDuplicateLabelsInsteadOfSilentlyOverwriting() {
        val raw = record().raw.replace("OBJECT_TITLE: Engine notes", "OBJECT_TITLE: Engine notes\nOBJECT_TITLE: Tampered")
        assertFalse(OpenMineObjectFormat.validate(raw).valid)
    }
    @Test fun rejectsUnlabeledContentAndUnknownSections() {
        assertFalse(OpenMineObjectFormat.validate(record().raw.replace("CONTENT_FACTS: Check oil", "CONTENT_FACTS: Check oil\nunlabeled fact")).valid)
        assertFalse(OpenMineObjectFormat.validate(record().raw.replace("[END_OBJECT]", "[UNKNOWN]\nX: y\n[END_OBJECT]")).valid)
    }
    @Test fun rejectsTrailingContentAndInvalidPriority() {
        assertFalse(OpenMineObjectFormat.validate(record().raw + "OBJECT_TITLE: second").valid)
        assertFalse(OpenMineObjectFormat.validate(record().raw.replace("INDEX_PRIORITY: 50", "INDEX_PRIORITY: 999")).valid)
    }
    @Test fun rejectsUnsafeIdAndInvalidTimestamp() {
        assertFalse(OpenMineObjectFormat.validate(record().raw.replace("knowledge.engine-notes", "../../outside")).valid)
        assertFalse(OpenMineObjectFormat.validate(record().raw.replace(Regex("OBJECT_CREATED: [^\\n]+"), "OBJECT_CREATED: yesterday")).valid)
    }
}
