package com.openmine

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OperationJournalTest {
    @get:Rule val folder=TemporaryFolder()
    private fun file()=File(folder.root,"operations/journal.json")

    @Test fun operationsPersistAcrossReload(){
        val journal=OperationJournal(file()){1000}
        val id=journal.begin("Model engine","Engine: serve",RetryAction.SERVE_MODEL,"a.gguf")
        journal.finish(id,OperationState.FAILED,"out of memory")
        val reloaded=OperationJournal(file()).records.value.single()
        assertEquals(OperationState.FAILED,reloaded.state);assertEquals("out of memory",reloaded.detail)
        assertEquals(RetryAction.SERVE_MODEL,reloaded.retry);assertEquals("a.gguf",reloaded.argument)
    }
    @Test fun runningOperationsBecomeInterruptedAfterProcessDeath(){
        OperationJournal(file()).begin("Linux shell","Setting up Linux shell",RetryAction.SETUP_SHELL)
        val recovered=OperationJournal(file())
        assertEquals(OperationState.INTERRUPTED,recovered.records.value.single().state)
        assertEquals(OperationState.INTERRUPTED,recovered.latestFailure()?.state)
        assertEquals(OperationState.INTERRUPTED,OperationJournal(file()).records.value.single().state)
    }
    @Test fun finishOnlyAppliesOnceAndNeverToRunning(){
        val journal=OperationJournal(file())
        val id=journal.begin("Runtime","x")
        journal.finish(id,OperationState.SUCCEEDED,"ok")
        journal.finish(id,OperationState.FAILED,"late")
        assertEquals(OperationState.SUCCEEDED,journal.records.value.single().state)
        assertTrue(runCatching{journal.finish(id,OperationState.RUNNING)}.isFailure)
    }
    @Test fun journalIsBoundedAndClearKeepsRunning(){
        val journal=OperationJournal(file())
        repeat(OperationJournal.MAX_RECORDS+20){journal.finish(journal.begin("Runtime","op $it"),OperationState.SUCCEEDED)}
        assertEquals(OperationJournal.MAX_RECORDS,journal.records.value.size)
        val running=journal.begin("Runtime","active")
        journal.clearFinished()
        assertEquals(listOf(running),journal.records.value.map{it.id})
    }
    @Test fun corruptJournalIsPreservedNotDiscarded(){
        file().parentFile!!.mkdirs();file().writeText("{not json")
        val journal=OperationJournal(file()){42}
        assertTrue(journal.records.value.isEmpty())
        assertEquals("{not json",File(file().parentFile,"journal.json.corrupt-42").readText())
    }
}
