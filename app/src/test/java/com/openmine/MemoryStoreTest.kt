package com.openmine

import org.junit.Test
import org.junit.Assert.*
import java.nio.file.Files

class MemoryStoreTest {
    @Test fun explicitMemoryScopesEditAndForgetDoNotCrossConversations() {
        val root=Files.createTempDirectory("memory-test").toFile()
        try {
            val store=MemoryStore(root)
            val mine=store.save(null,"Preference","Use Kotlin","conversation:mine")
            store.save(null,"Shared","Reusable data","shared")
            store.save(null,"Project","Only project A","project:project.a")
            assertEquals(listOf(mine.id),store.sources(RetrievalScope("mine")).map{it.id})
            assertTrue(store.sources(RetrievalScope("other")).isEmpty())
            assertEquals(1,store.sources(RetrievalScope("other",sharedMemory=true)).size)
            assertEquals(1,store.sources(RetrievalScope("other","project.a")).size)
            store.save(mine.id,"Preference","Use Java","conversation:mine")
            assertTrue(store.sources(RetrievalScope("mine")).single().raw.contains("Use Java"))
            store.forget(mine.id)
            assertTrue(MemoryStore(root).sources(RetrievalScope("mine")).isEmpty())
            assertTrue(runCatching{store.forget("../outside")}.isFailure)
        }finally{root.deleteRecursively()}
    }
}
