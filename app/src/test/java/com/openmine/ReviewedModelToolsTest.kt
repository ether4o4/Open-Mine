package com.openmine

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReviewedModelToolsTest {
    private fun call(name:String,args:String)=JSONObject().put("id","call-1").put("function",JSONObject().put("name",name).put("arguments",args))
    @Test fun declinedSearchAndSystemInformationNeverExecute(){
        for(proposal in listOf(ModelToolProposal.from(call("search_library","{\"query\":\"specification\"}")),ModelToolProposal.from(call("linux_system_info","{}")))){
            var executed=false
            val result=ReviewedModelTools.execute(proposal,{false}){executed=true;"Should not run"}
            assertFalse(executed)
            assertTrue(result.contains("No tool was executed"))
        }
    }
    @Test fun approvalReviewsExactArgumentsAndAuthorizesOnlyOneInvocation(){
        val proposal=ModelToolProposal.from(call("search_library","{\"query\":\"specific project\"}"))
        var count=0
        val result=ReviewedModelTools.execute(proposal,{request->
            assertEquals("specific project",JSONObject(request.arguments).getString("query"))
            assertTrue(request.effect.contains("send their content"));true
        }){count++;"Actual result"}
        assertEquals("Actual result",result);assertEquals(1,count)
        ReviewedModelTools.execute(proposal,{false}){count++;"Must not run"}
        assertEquals(1,count)
    }
    @Test fun arbitraryCommandsAndUnexpectedArgumentsAreRejectedBeforeReview(){
        for(request in listOf(call("shell","{\"command\":\"rm -rf /\"}"),call("linux_system_info","{\"command\":\"uname -a; echo injected\"}"),
            call("search_library","{\"query\":\"a\",\"command\":\"anything\"}"),call("search_library","{\"query\":123}"),call("search_library","{\"query\":\"\"}"))){
            assertTrue(runCatching{ModelToolProposal.from(request)}.isFailure)
        }
    }
    @Test fun cancellationWhileReviewingCannotExecuteTheAction(){
        val control=ModelRequestControl()
        try{
            var executed=false
            val outcome=runCatching{ReviewedModelTools.execute(ModelToolProposal.from(call("linux_system_info","{}")),{
                control.cancelRequest();control.checkActive();true
            }){executed=true;"Forbidden"}}
            assertTrue(outcome.isFailure);assertFalse(executed)
        }finally{control.close()}
    }
    @Test fun cancellationReasonIsStableAndDetachedConnectionsAreNotCancelled(){
        val control=ModelRequestControl()
        val connection=object:java.net.HttpURLConnection(java.net.URL("http://127.0.0.1")){
            var disconnected=false
            override fun disconnect(){disconnected=true}
            override fun usingProxy()=false
            override fun connect(){}
        }
        try{
            control.attach(connection);control.detach(connection)
            control.cancelRequest("Cancelled by user");control.cancelRequest("Later reason")
            assertEquals("Cancelled by user",control.reason)
            assertFalse(connection.disconnected)
            assertTrue(runCatching{control.checkActive()}.exceptionOrNull() is ModelRequestCancelledException)
        }finally{control.close()}
    }
}
