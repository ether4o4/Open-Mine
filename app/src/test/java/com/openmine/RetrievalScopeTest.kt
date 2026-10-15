package com.openmine

import org.junit.Assert.*
import org.junit.Test

class RetrievalScopeTest {
    @Test fun archivedOrRemovedProjectRevokesConversationRetrieval() {
        val scope=RetrievalScope("chat","project.a")
        val active=StrictObject(mapOf("OBJECT_ID" to "project.a","OBJECT_TYPE" to "PROJECT","OBJECT_STATUS" to "VERIFIED"),emptyMap(),"")
        RetrievalPolicy.requireActiveProject(listOf(active),scope)
        assertTrue(runCatching{RetrievalPolicy.requireActiveProject(emptyList(),scope)}.isFailure)
        assertTrue(runCatching{RetrievalPolicy.requireActiveProject(listOf(active.copy(fields=active.fields+("OBJECT_STATUS" to "ARCHIVED"))),scope)}.isFailure)
    }
    private fun record(id:String="knowledge.a",scope:String="workspace",project:String="NONE",status:String="VERIFIED",exclude:String="NONE") = StrictObject(
        mapOf("OBJECT_ID" to id,"OBJECT_STATUS" to status),mapOf("CONTEXT_INDEX" to mapOf("INDEX_SCOPE" to scope),
            "RELATIONSHIPS" to mapOf("REL_PROJECTS" to project),"RETRIEVAL" to mapOf("RETRIEVAL_EXCLUDE" to exclude)),"")
    @Test fun globalSearchAndStaleSelectionCannotCrossProjects() {
        val scope=RetrievalScope("chat.a","project.a",setOf("knowledge.b"))
        assertFalse(RetrievalPolicy.allowed(record("knowledge.b",project="project.b"),scope))
        assertTrue(RetrievalPolicy.allowed(record(project="project.a"),scope))
        assertFalse(RetrievalPolicy.allowed(record(),scope))
    }
    @Test fun conversationAndSharedScopesAreExplicit() {
        assertFalse(RetrievalPolicy.allowed(record(scope="conversation:other"),RetrievalScope("mine",selectedIds=setOf("knowledge.a"))))
        assertTrue(RetrievalPolicy.allowed(record(scope="conversation:mine"),RetrievalScope("mine")))
        assertFalse(RetrievalPolicy.allowed(record(scope="shared"),RetrievalScope("mine")))
        assertTrue(RetrievalPolicy.allowed(record(scope="shared"),RetrievalScope("mine",sharedMemory=true)))
    }
    @Test fun archiveAndExclusionsOverrideSelection() {
        val scope=RetrievalScope("mine",selectedIds=setOf("knowledge.a"))
        assertFalse(RetrievalPolicy.allowed(record(status="ARCHIVED"),scope))
        assertFalse(RetrievalPolicy.allowed(record(exclude="private"),scope))
        assertFalse(RetrievalPolicy.allowed(record(),scope.copy(excludedIds=setOf("knowledge.a"))))
        assertTrue(RetrievalPolicy.allowed(record(),scope))
    }
    @Test fun scopingSurvivesStorageAndLegacyDoesNotGrantGlobalAccess() {
        val conversation=AssistantConversation(projectId="project.a",sourceIds=setOf("knowledge.a"),sharedMemory=true)
        val session=AssistantSession(conversations=listOf(conversation))
        assertEquals(session,AssistantSessionStore.decode(AssistantSessionStore.encode(session)))
        assertTrue(AssistantSessionStore.migrateLegacy("","","").retrievalScope().selectedIds.isEmpty())
    }
}
