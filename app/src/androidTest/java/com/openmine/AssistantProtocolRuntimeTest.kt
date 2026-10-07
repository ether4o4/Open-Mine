package com.openmine

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Real Android HTTP/storage against test fixtures. These tests do not claim real model inference. */
class AssistantProtocolRuntimeTest {
    @Test fun approvedSearchReturnsActualIndexedRecordsAndPersistsTheCompletedTranscript(){
        isolated { context->
            val record=OpenMineObjectFormat.template("KNOWLEDGE","Engine notes","Source grounded context","engine","manual",
                "Maintenance","Check oil","Inspect then replace","NONE","NONE","engine","NONE","maintenance","NONE","NONE","NONE","NONE","NONE","engine service","maintenance","NONE","DRAFT","manual","Review pending")
            assertTrue(OpenMineObjectStore.create(context,record).valid)
            ProtocolFixture("search_library","{\"query\":\"engine\"}").use { server->
                var reviews=0
                val partials=mutableListOf<String>()
                val reply=ModelClient.ask(context,server.endpoint,"fixture-model","","Explain the engine",onText={partials.add(it)},reviewTool={proposal->
                    reviews++;assertEquals("engine",JSONObject(proposal.arguments).getString("query"));true
                })
                server.assertFinished()
                assertEquals(1,reviews)
                assertEquals("Fixture protocol completed",reply.text)
                assertTrue(partials.contains("Fixture"))
                assertTrue(server.toolResult.get().contains("Check oil"))
                assertTrue(reply.sources.any{it.id==record.id})
                val turn=AssistantTurn(question="Explain the engine",answer=reply.text,state=AssistantTurnState.COMPLETED,sourceIds=reply.sources.map{it.id},toolAudit=reply.toolResults)
                val session=AssistantSession(server.endpoint,"fixture-model",listOf(AssistantConversation(draft="Next question",turns=listOf(turn))))
                val file=File(context.filesDir,"test-session.json")
                AssistantSessionStore(file).save(session)
                assertEquals(session,AssistantSessionStore(file).load())
            }
        }
    }
    @Test fun decliningSystemInformationSendsDenialWithoutInvokingAnUninstalledShell(){
        isolated { context->ProtocolFixture("linux_system_info","{}").use { server->
            var reviews=0
            val reply=ModelClient.ask(context,server.endpoint,"fixture-model","","What system is this?",reviewTool={reviews++;false})
            server.assertFinished()
            assertEquals(1,reviews)
            assertTrue(server.toolResult.get().contains("No tool was executed"))
            assertTrue(reply.toolResults.single().startsWith("Declined linux_system_info"))
        }}
    }
    @Test fun aFailedPostToolRequestDoesNotEraseTextAlreadyReceived(){
        isolated { context->ProtocolFixture("search_library","{\"query\":\"engine\"}","Partial explanation before the tool",true).use { server->
            var retained=""
            val result=runCatching{ModelClient.ask(context,server.endpoint,"fixture-model","","Explain the engine",onText={retained=it},reviewTool={false})}
            server.assertFinished()
            assertTrue("HTTP failure must not become a successful reply",result.isFailure)
            assertEquals("Partial explanation before the tool",retained)
        }}
    }
    private fun isolated(block:(Context)->Unit){
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        val prefix="assistant-test-${UUID.randomUUID()}"
        val directory=File(target.cacheDir,prefix).apply{mkdirs()}
        val context=object:ContextWrapper(target){
            override fun getFilesDir()=directory
            override fun getApplicationContext():Context=this
            override fun getSharedPreferences(name:String,mode:Int):SharedPreferences=target.getSharedPreferences("$prefix-$name",mode)
        }
        try{block(context)}finally{directory.deleteRecursively();target.deleteSharedPreferences("$prefix-open_mine")}
    }
    private class ProtocolFixture(private val tool:String,private val arguments:String,private val prelude:String="",private val failFollowup:Boolean=false):Closeable {
        private val server=ServerSocket(0,4,InetAddress.getByName("127.0.0.1"))
        val endpoint="http://127.0.0.1:${server.localPort}/v1"
        val toolResult=AtomicReference("")
        private val failure=AtomicReference<Throwable?>(null)
        private val worker=Thread{
            try{repeat(2){round->server.accept().use{socket->
                socket.soTimeout=5000
                val input=socket.getInputStream()
                check(line(input)=="POST /v1/chat/completions HTTP/1.1")
                var length=0
                while(true){val header=line(input);if(header.isEmpty())break
                    if(header.startsWith("Content-Length:",true))length=header.substringAfter(':').trim().toInt()
                }
                check(length in 1..1024*1024)
                val bytes=ByteArray(length);var received=0
                while(received<length){val count=input.read(bytes,received,length-received);check(count>0);received+=count}
                val request=JSONObject(String(bytes,Charsets.UTF_8))
                val out=socket.getOutputStream()
                if(round==1 && failFollowup){
                    out.write("HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray());out.flush()
                    return@use
                }
                out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray())
                if(round==0){
                    val call=JSONObject().put("index",0).put("id","review-1").put("function",JSONObject().put("name",tool).put("arguments",arguments))
                    out.write(event(JSONObject().put("tool_calls",JSONArray().put(call)).put("content",prelude),"tool_calls").toByteArray())
                }else{
                    val messages=request.getJSONArray("messages")
                    val result=(0 until messages.length()).map{messages.getJSONObject(it)}.last{it.optString("role")=="tool"}
                    check(result.getString("tool_call_id")=="review-1")
                    toolResult.set(result.getString("content"))
                    out.write(event(JSONObject().put("content","Fixture")).toByteArray());out.flush()
                    out.write(event(JSONObject().put("content"," protocol completed"),"stop").toByteArray())
                }
                out.write("data: [DONE]\n\n".toByteArray());out.flush()
            }}}catch(e:Throwable){failure.set(e)}
        }.apply{isDaemon=true;start()}
        private fun event(delta:JSONObject,finish:String?=null)="data: "+JSONObject().put("choices",JSONArray().put(JSONObject().put("delta",delta).put("finish_reason",finish ?: JSONObject.NULL)))+"\n\n"
        private fun line(input:InputStream):String{
            val value=StringBuilder()
            while(true){val next=input.read();check(next>=0);if(next==10)break;value.append(next.toChar());check(value.length<16384)}
            return value.toString().removeSuffix("\r")
        }
        fun assertFinished(){worker.join(5000);assertFalse("Fixture did not finish",worker.isAlive);failure.get()?.let{throw AssertionError("Protocol fixture failed",it)}}
        override fun close(){server.close();worker.join(1000)}
    }
}
