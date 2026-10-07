package com.openmine

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Production controller, HTTP transport, persistence and actual Activity lifecycle; fixture text is not inference. */
class AssistantControllerRuntimeTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()

    @Test fun aFailedQuestionRetriesWithoutDestroyingAnUnsentDraft(){
        isolated { context,controller->ControllerServer().use{server->
            server.fail=true
            controller.configure(server.endpoint,"fixture-model");controller.setTools(false)
            controller.draft("Retry this question")
            controller.submit("")
            await("failed request retained"){!controller.state.value.busy && controller.state.value.session.current.turns.lastOrNull()?.state==AssistantTurnState.FAILED}
            val failed=controller.state.value.session.current.turns.last()
            controller.draft("A different, unsent follow-up")
            server.fail=false
            controller.retry(failed,"")
            await("retry completed"){!controller.state.value.busy && controller.state.value.session.current.turns.lastOrNull()?.state==AssistantTurnState.COMPLETED}
            val current=controller.state.value.session.current
            assertEquals("A different, unsent follow-up",current.draft)
            assertEquals("Retry this question",current.turns.last().question)
            assertEquals(AssistantTurnState.FAILED,current.turns.first().state)
            assertEquals(2,server.requests.get())
            awaitSaved(context){it.current.draft==current.draft && it.current.turns.last().state==AssistantTurnState.COMPLETED}
        }}
    }

    @Test fun userCancellationInterruptsAStalledReadAndPersistsItsPartialText(){
        isolated { context,controller->ControllerServer(hold=true).use{server->
            start(controller,server)
            await("first streamed text"){controller.state.value.session.current.turns.lastOrNull()?.answer==ControllerServer.PARTIAL}
            // The server deliberately sends no further bytes: cancellation occurs outside onText.
            SystemClock.sleep(250)
            val started=SystemClock.elapsedRealtime()
            controller.cancel()
            await("stalled socket cancellation",5_000){!controller.state.value.busy}
            val turn=controller.state.value.session.current.turns.last()
            assertEquals(AssistantTurnState.CANCELLED,turn.state)
            assertEquals(ControllerServer.PARTIAL,turn.answer)
            assertTrue(SystemClock.elapsedRealtime()-started<5_000)
            awaitSaved(context){it.current.turns.last().state==AssistantTurnState.CANCELLED && it.current.turns.last().answer==ControllerServer.PARTIAL}
        }}
    }

    @Test fun backgroundingTheActualActivityCancelsWithoutLosingThePartialResponse(){
        isolated { context,controller->ControllerServer(hold=true).use{server->
            compose.setContent{AssistantRequestLifecycle(compose.activity,controller);Text("Assistant lifecycle verification")}
            start(controller,server)
            await("first streamed text"){controller.state.value.session.current.turns.lastOrNull()?.answer==ControllerServer.PARTIAL}
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            try{
                await("background cancellation",5_000){!controller.state.value.busy}
                val turn=controller.state.value.session.current.turns.last()
                assertEquals(AssistantTurnState.CANCELLED,turn.state)
                assertTrue(turn.detail.contains("background"))
                assertEquals(ControllerServer.PARTIAL,turn.answer)
                awaitSaved(context){it.current.turns.last().state==AssistantTurnState.CANCELLED}
            }finally{compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)}
        }}
    }

    @Test fun activityRecreationKeepsTheInFlightRequestAndMemoryOnlyKey(){
        isolated { context,controller->ControllerServer(hold=true).use{server->
            compose.setContent{AssistantRequestLifecycle(compose.activity,controller);Text("Assistant lifecycle verification")}
            controller.setKey("test-memory-only-key")
            start(controller,server)
            await("first streamed text"){controller.state.value.session.current.turns.lastOrNull()?.answer==ControllerServer.PARTIAL}
            compose.activityRule.scenario.recreate()
            assertTrue("Configuration recreation must preserve the request",controller.state.value.busy)
            assertEquals("test-memory-only-key",controller.state.value.apiKey)
            server.release.countDown()
            await("continued request completion"){!controller.state.value.busy && controller.state.value.session.current.turns.lastOrNull()?.state==AssistantTurnState.COMPLETED}
            assertEquals(1,server.requests.get())
            awaitSaved(context){it.current.turns.last().state==AssistantTurnState.COMPLETED}
            assertFalse(File(context.filesDir,"assistant/sessions-v1.json").readText().contains("test-memory-only-key"))
        }}
    }

    private fun start(controller:AssistantSessionController,server:ControllerServer){
        controller.configure(server.endpoint,"fixture-model");controller.setTools(false)
        controller.draft("A real protocol request")
        controller.submit(controller.state.value.apiKey)
    }
    private fun await(label:String,millis:Long=10_000,predicate:()->Boolean){
        val deadline=SystemClock.elapsedRealtime()+millis
        while(SystemClock.elapsedRealtime()<deadline){if(predicate())return;SystemClock.sleep(20)}
        assertTrue("Timed out: $label",predicate())
    }
    private fun awaitSaved(context:Context,predicate:(AssistantSession)->Boolean){
        val store=AssistantSessionStore(File(context.filesDir,"assistant/sessions-v1.json"))
        await("final persisted assistant state"){runCatching{store.load()?.let(predicate)==true}.getOrDefault(false)}
    }
    private fun isolated(block:(Context,AssistantSessionController)->Unit){
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        val prefix="assistant-controller-${UUID.randomUUID()}"
        val directory=File(target.cacheDir,prefix).apply{mkdirs()}
        val context=object:ContextWrapper(target){
            override fun getFilesDir()=directory
            override fun getApplicationContext():Context=this
            override fun getSharedPreferences(name:String,mode:Int):SharedPreferences=target.getSharedPreferences("$prefix-$name",mode)
        }
        val controller=AssistantSessionController(context)
        try{block(context,controller)}finally{
            controller.cancel("Cancelled by fixture cleanup")
            directory.deleteRecursively()
            target.deleteSharedPreferences("$prefix-assistant");target.deleteSharedPreferences("$prefix-open_mine")
        }
    }
    private class ControllerServer(private val hold:Boolean=false):Closeable {
        private val socket=ServerSocket(0,4,InetAddress.getByName("127.0.0.1"))
        val endpoint="http://127.0.0.1:${socket.localPort}/v1"
        val requests=AtomicInteger()
        val release=CountDownLatch(1)
        @Volatile var fail=false
        private val worker=Thread{
            while(!socket.isClosed){
                val client=runCatching{socket.accept()}.getOrNull() ?: break
                client.use{
                    runCatching{
                        client.soTimeout=5_000
                        val input=client.getInputStream()
                        check(line(input)=="POST /v1/chat/completions HTTP/1.1")
                        var length=0
                        while(true){val header=line(input);if(header.isEmpty())break
                            if(header.startsWith("Content-Length:",true))length=header.substringAfter(':').trim().toInt()
                        }
                        check(length in 1..1024*1024)
                        var consumed=0;val buffer=ByteArray(4096)
                        while(consumed<length){val count=input.read(buffer,0,minOf(buffer.size,length-consumed));check(count>0);consumed+=count}
                        requests.incrementAndGet()
                        val out=client.getOutputStream()
                        if(fail){out.write("HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray());out.flush()}
                        else{
                            out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray())
                            out.write(event(PARTIAL).toByteArray());out.flush()
                            if(hold)check(release.await(20,TimeUnit.SECONDS))
                            out.write(event(" and finished","stop").toByteArray());out.write("data: [DONE]\n\n".toByteArray());out.flush()
                        }
                    }
                }
            }
        }.apply{isDaemon=true;start()}
        private fun event(text:String,finish:String?=null)="data: "+JSONObject().put("choices",JSONArray().put(JSONObject().put("delta",JSONObject().put("content",text)).put("finish_reason",finish ?: JSONObject.NULL)))+"\n\n"
        private fun line(input:InputStream):String{
            val result=StringBuilder()
            while(true){val next=input.read();check(next>=0);if(next==10)break;result.append(next.toChar());check(result.length<16384)}
            return result.toString().removeSuffix("\r")
        }
        override fun close(){release.countDown();socket.close();worker.join(1000)}
        companion object{const val PARTIAL="Retained fixture text"}
    }
}
