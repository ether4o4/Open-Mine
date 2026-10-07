package com.openmine

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class RevisionedCheckpointTest {
    @Test fun finalEditArrivingDuringAnOlderWriteIsSavedWithoutAnotherEdit(){
        val firstWriteStarted=CountDownLatch(1)
        val finishFirstWrite=CountDownLatch(1)
        val persisted=mutableListOf<String>()
        val failure=AtomicReference<Throwable?>(null)
        val writer=RevisionedCheckpoint("earlier draft"){snapshot->
            if(snapshot=="earlier draft"){
                firstWriteStarted.countDown()
                check(finishFirstWrite.await(3,TimeUnit.SECONDS))
            }
            persisted.add(snapshot)
        }
        val worker=Thread{try{writer.flush()}catch(e:Throwable){failure.set(e)}}.apply{start()}
        try{
            assertTrue(firstWriteStarted.await(3,TimeUnit.SECONDS))
            writer.update("the final edit")
            finishFirstWrite.countDown()
            worker.join(3000)
            assertFalse(worker.isAlive)
            assertNull(failure.get())
            assertEquals(listOf("earlier draft","the final edit"),persisted)
            assertFalse(writer.isDirty)
        }finally{finishFirstWrite.countDown();worker.join(3000)}
    }
    @Test fun failedWritesRemainDirtyAndRetryTheLatestSnapshot(){
        var fail=true
        var stored=""
        val writer=RevisionedCheckpoint("first"){value->if(fail)error("Storage unavailable")else stored=value}
        assertTrue(runCatching{writer.flush()}.isFailure)
        assertTrue(writer.isDirty)
        writer.update("latest")
        fail=false
        writer.flush()
        assertEquals("latest",stored)
        assertFalse(writer.isDirty)
        writer.update("edit after flush")
        assertTrue(writer.isDirty)
        writer.flush()
        assertEquals("edit after flush",stored)
    }
}
