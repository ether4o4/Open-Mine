package com.openmine

import org.junit.Assert.*
import org.junit.Test

class ModelRouterTest {
    private val GiB=1024L*1024*1024
    private fun local(name:String,arch:String,size:Long,healthy:Boolean=true)=
        ConfiguredModel(ModelEndpoint.BUILT_IN,"local",ModelCatalog.profileForGguf(name,arch,size),healthy)
    private val remote=ConfiguredModel("https://api.example.com/v1","big",ModelCatalog.profileForEndpoint("https://api.example.com/v1","big"),true)

    @Test fun smallLocalSpecialistHandlesRoutineTasksBeforeRemote(){
        val small=local("lfm.gguf","lfm2",GiB)
        val big=local("gemma.gguf","gemma4",3*GiB)
        assertEquals(small,ModelRouter.route(TaskKind.EXTRACT,listOf(big,remote,small)).model)
        assertEquals(small,ModelRouter.route(TaskKind.TOOL_CALL,listOf(big,remote,small)).model)
    }
    @Test fun planningPrefersAConfiguredRemoteButNeverRequiresIt(){
        val big=local("gemma.gguf","gemma4",3*GiB)
        assertEquals(remote,ModelRouter.route(TaskKind.PLAN,listOf(big,remote)).model)
        assertEquals(big,ModelRouter.route(TaskKind.PLAN,listOf(big,remote),offlineOnly=true).model)
        assertEquals(big,ModelRouter.route(TaskKind.PLAN,listOf(big)).model)
    }
    @Test fun unhealthyUnrunnableAndOversizedModelsAreSkipped(){
        val needle=ModelCatalog.candidates.first{it.id=="needle-3"}
        assertFalse(needle.runnable)
        val cactus=ConfiguredModel("cactus://","needle",needle,true)
        val stopped=local("q.gguf","qwen3",GiB,healthy=false)
        val decision=ModelRouter.route(TaskKind.TOOL_CALL,listOf(cactus,stopped))
        assertNull(decision.model);assertTrue(decision.reason.contains("healthy"))
        val big=local("gemma.gguf","gemma4",5*GiB)
        ModelRouter.route(TaskKind.CHAT,listOf(big),deviceMemoryMb=4096).let{assertNull(it.model);assertTrue(it.reason.contains("memory"))}
    }
    @Test fun generalFallbackWhenNoSpecialistExists(){
        val big=local("gemma.gguf","gemma4",3*GiB)
        val decision=ModelRouter.route(TaskKind.ROUTE_INTENT,listOf(big))
        assertEquals(big,decision.model);assertTrue(decision.reason.contains("general"))
    }
    @Test fun allCandidatesStartUnverifiedAndUnbundled(){
        assertTrue(ModelCatalog.candidates.all{it.verification==Verification.UNVERIFIED && !it.redistributionConfirmed})
    }
}
