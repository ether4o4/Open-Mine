package com.openmine

import org.junit.Assert.*
import org.junit.Test

class RecordActionsTest {
    private fun record(type:String, command:String, id:String="record.test") = StrictObject(
        mapOf("OBJECT_TYPE" to type,"OBJECT_ID" to id),
        mapOf("CONTENT" to mapOf("CONTENT_PROCEDURE" to command)), "")

    @Test fun onlyExplicitToolProceduresAreRunnable() {
        assertEquals("pwd; ls -la", RecordActions.toolCommand(record("TOOL","pwd; ls -la")))
        for(r in listOf(record("SKILL","pwd"),record("TOOL","NONE"),record("TOOL",""),record("TOOL","a".repeat(8193)),record("TOOL","pwd\u0000"))) {
            assertTrue(runCatching{RecordActions.toolCommand(r)}.isFailure)
        }
    }
    @Test fun activationRequiresSelectedSkillAndHasBoundedCount() {
        val records=(1..12).map{record("SKILL","Explain step by step","skill.$it")}+record("TOOL","pwd","tool.test")
        assertTrue(RecordActions.activatedSkills(records,emptySet()).isEmpty())
        val active=RecordActions.activatedSkills(records,records.map{it.id}.toSet())
        assertEquals(8,active.size)
        assertTrue(active.all{it.type=="SKILL"})
    }
}
