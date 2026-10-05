package com.openmine

import org.junit.Assert.*
import org.junit.Test
import java.io.StringReader

class ChatStreamTest {
    private fun chunk(delta:String,finish:String="null")="data: {\"choices\":[{\"delta\":$delta,\"finish_reason\":$finish}]}\n\n"
    @Test fun publishesTextBeforeStreamFinishes() {
        val partials=mutableListOf<String>()
        val stream=chunk("{\"content\":\"Hello\"}")+chunk("{\"content\":\" world\"}","\"stop\"")+"data: [DONE]\n\n"
        val answer=ChatStream.read(StringReader(stream),onText={partials.add(it)})
        assertEquals(listOf("Hello","Hello world"),partials)
        assertEquals("Hello world",answer.getString("content"))
    }
    @Test fun handlesCrLfEventsAndFinishWithoutDoneMarker() {
        val stream=(chunk("{\"content\":\"works\"}")+chunk("{}","\"stop\"")).replace("\n","\r\n")
        assertEquals("works",ChatStream.read(StringReader(stream)).getString("content"))
    }
    @Test fun reassemblesFragmentedToolNameAndArguments() {
        val stream=chunk("{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"function\":{\"name\":\"search_\",\"arguments\":\"{\\\"query\\\":\"}}]}")+
            chunk("{\"tool_calls\":[{\"index\":0,\"function\":{\"name\":\"library\",\"arguments\":\"\\\"engine\\\"}\"}}]}","\"tool_calls\"")+"data: [DONE]\n\n"
        val call=ChatStream.read(StringReader(stream)).getJSONArray("tool_calls").getJSONObject(0)
        assertEquals("call_1",call.getString("id"))
        assertEquals("search_library",call.getJSONObject("function").getString("name"))
        assertEquals("{\"query\":\"engine\"}",call.getJSONObject("function").getString("arguments"))
    }
    @Test fun rejectsIncompleteOversizedAndTooManyToolCalls() {
        for(stream in listOf(chunk("{\"content\":\"partial\"}"),"data: "+"x".repeat(65537),chunk("{\"tool_calls\":[{\"index\":3}]}"))) {
            assertTrue(runCatching{ChatStream.read(StringReader(stream))}.isFailure)
        }
    }
    @Test fun cancelledReadDoesNotBecomeSuccessfulAnswer() {
        val control=ModelRequestControl();control.cancelRequest()
        try{assertTrue(runCatching{ChatStream.read(StringReader(chunk("{\"content\":\"ignored\"}")),{control.checkActive()})}.isFailure)}finally{control.close()}
    }
    @Test fun totalRequestBudgetExpiresWithoutWaitingForThreeSocketTimeouts() {
        val control=ModelRequestControl(20)
        try{
            val until=System.nanoTime()+2_000_000_000
            while(control.reason==null && System.nanoTime()<until)Thread.sleep(5)
            assertNotNull(control.reason)
            assertTrue(runCatching{control.checkActive()}.isFailure)
        }finally{control.close()}
    }
}
