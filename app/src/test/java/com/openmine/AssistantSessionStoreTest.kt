package com.openmine

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class AssistantSessionStoreTest {
    @Test fun restartingPreservesDraftHistoryEndpointAndActualCompletionStates(){
        val directory=Files.createTempDirectory("openmine-assistant").toFile()
        try{
            val first=AssistantConversation(title="Project question",draft="Unsubmitted follow-up",turns=listOf(
                AssistantTurn(question="What changed?",answer="Saved answer",state=AssistantTurnState.COMPLETED,sourceIds=listOf("doc-7"),toolAudit=listOf("User approved search_library")),
                AssistantTurn(question="Continue",answer="Partial text",state=AssistantTurnState.STREAMING)))
            val second=AssistantConversation(title="Another workspace",draft="Preserved too")
            val session=AssistantSession(ModelEndpoint.OLLAMA,"real-model:tag",listOf(first,second),first.id)
            val file=directory.resolve("sessions.json")
            AssistantSessionStore(file).save(session)
            val restored=AssistantSessionStore(file).load()!!
            assertEquals("Unsubmitted follow-up",restored.current.draft)
            assertEquals(ModelEndpoint.OLLAMA,restored.endpoint)
            assertEquals("real-model:tag",restored.model)
            assertEquals(first.turns[0],restored.current.turns[0])
            assertEquals(AssistantTurnState.INTERRUPTED,restored.current.turns[1].state)
            assertEquals("Partial text",restored.current.turns[1].answer)
            assertEquals("Preserved too",restored.conversations[1].draft)
            assertFalse(directory.resolve("sessions.json.pending").exists())
        }finally{directory.deleteRecursively()}
    }
    @Test fun corruptAndFutureVersionDataAreNeverSilentlyReplacedOnLoad(){
        val directory=Files.createTempDirectory("openmine-assistant-corrupt").toFile()
        try{
            val file=directory.resolve("sessions.json")
            for(original in listOf("{broken", "{\"schema\":99}")){
                file.writeText(original)
                assertTrue(runCatching{AssistantSessionStore(file).load()}.isFailure)
                assertEquals(original,file.readText())
            }
        }finally{directory.deleteRecursively()}
    }
    @Test fun migrationPreservesLegacyAnswerWithoutInventingCompletedState(){
        val session=AssistantSessionStore.migrateLegacy("https://example.test/v1","server-model","Previous answer")
        assertEquals("Previous answer",session.current.turns.single().answer)
        assertEquals(AssistantTurnState.INTERRUPTED,session.current.turns.single().state)
        assertTrue(session.completeHistory().isEmpty())
        assertEquals(session,AssistantSessionStore.decode(AssistantSessionStore.encode(session)))
    }
    @Test fun incompleteAndFailedTextDoesNotBecomeModelConversationHistory(){
        val turns=AssistantTurnState.entries.map{AssistantTurn(question=it.name,answer="Answer",state=it)}
        val session=AssistantSession(conversations=listOf(AssistantConversation(turns=turns)))
        assertEquals(listOf("COMPLETED","LIMITED"),session.completeHistory().map{it.first})
    }
    @Test fun newConversationsAndDraftEditsDoNotModifyEarlierConversations(){
        val older=AssistantConversation(draft="Old draft",turns=listOf(AssistantTurn(question="Old question",answer="Old answer",state=AssistantTurnState.COMPLETED)))
        val newer=AssistantConversation()
        val session=AssistantSession(conversations=listOf(older,newer),selectedId=newer.id).updateCurrent{it.copy(draft="New draft")}
        assertEquals(older,session.conversations.first())
        assertEquals("New draft",session.current.draft)
        assertTrue(session.completeHistory().isEmpty())
    }
}
