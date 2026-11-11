package com.openmine

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SetupDiagnosticsTest {
    private val GiB=1024L*1024*1024
    private fun snapshot(shellInstalled:Boolean=true,shellHealthy:Boolean=true,engineInstalled:Boolean=true,modelHealthy:Boolean=true,
        models:List<String> = listOf("a.gguf"),endpoint:String=ModelEndpoint.BUILT_IN,model:String="local",abis:List<String> = listOf("arm64-v8a"),
        free:Long=50*GiB,ram:Long=12*GiB,failure:OperationRecord?=null,debug:Boolean=true,notifications:Boolean=true)=
        SetupSnapshot(debug,abis,shellInstalled,shellHealthy,engineInstalled,true,modelHealthy,models,free,ram,endpoint,model,emptyList(),true,notifications,failure,false)

    @Test fun healthyWorkspaceReportsOnlyOkFindings(){
        val findings=SetupDiagnostics.evaluate(snapshot())
        assertTrue(findings.all{it.severity==Severity.OK})
    }
    @Test fun freshInstallLeadsWithRuntimeSetup(){
        val findings=SetupDiagnostics.evaluate(snapshot(shellInstalled=false,shellHealthy=false,engineInstalled=false,modelHealthy=false,models=emptyList(),endpoint="",model=""))
        assertEquals("shell-missing",findings.first().id);assertEquals(RepairAction.SETUP_SHELL,findings.first().repair)
        assertTrue(findings.any{it.id=="no-models" && it.repair==RepairAction.IMPORT_MODEL})
        assertTrue(findings.any{it.id=="endpoint-missing"})
    }
    @Test fun releaseBuildWithoutRuntimePointsToEndpoints(){
        val f=SetupDiagnostics.evaluate(snapshot(shellInstalled=false,shellHealthy=false,debug=false)).first{it.id=="shell-missing"}
        assertEquals(RepairAction.CONFIGURE_ENDPOINT,f.repair)
    }
    @Test fun builtInEndpointWithoutRunningModelSuggestsLoading(){
        val findings=SetupDiagnostics.evaluate(snapshot(modelHealthy=false))
        assertTrue(findings.any{it.id=="builtin-down" && it.repair==RepairAction.LOAD_MODEL})
    }
    @Test fun invalidEndpointIsBlocking(){
        val f=SetupDiagnostics.evaluate(snapshot(endpoint="http://example.com/v1")).first{it.id=="endpoint-invalid"}
        assertEquals(Severity.BLOCKING,f.severity)
    }
    @Test fun lowResourcesAndNonArmDevicesAreReported(){
        val ids=SetupDiagnostics.evaluate(snapshot(abis=listOf("x86_64"),free=GiB,ram=4*GiB,notifications=false)).map{it.id}
        assertTrue(ids.containsAll(listOf("abi","storage-low","memory-low","notifications")))
    }
    @Test fun retryableFailureOffersRetry(){
        val failure=OperationRecord("1","Linux shell","Setting up Linux shell",0,1,OperationState.FAILED,"exit 1",RetryAction.SETUP_SHELL)
        assertEquals(RepairAction.RETRY_OPERATION,SetupDiagnostics.evaluate(snapshot(failure=failure)).first{it.id=="last-failure"}.repair)
        val manual=failure.copy(retry=null)
        assertNull(SetupDiagnostics.evaluate(snapshot(failure=manual)).first{it.id=="last-failure"}.repair)
    }
    @Test fun modelJsonIsBoundedAndCarriesNoEndpointSecrets(){
        val json=JSONObject(SetupDiagnostics.toModelJson(SetupDiagnostics.evaluate(snapshot(modelHealthy=false,endpoint="https://api.example.com/v1",model=""))))
        assertTrue(json.getJSONArray("findings").length()>0)
        assertFalse(json.toString().contains("Bearer"))
    }
    @Test fun setupDiagnosticsToolAcceptsNoArgumentsAndIsReviewed(){
        fun call(args:String)=JSONObject().put("id","c").put("function",JSONObject().put("name","setup_diagnostics").put("arguments",args))
        val proposal=ModelToolProposal.from(call("{}"))
        assertTrue(proposal.effect.contains("No repair is performed"))
        assertTrue(runCatching{ModelToolProposal.from(call("{\"repair\":\"SETUP_SHELL\"}"))}.isFailure)
        var ran=false
        ReviewedModelTools.execute(proposal,{false}){ran=true;""}
        assertFalse(ran)
    }
}
