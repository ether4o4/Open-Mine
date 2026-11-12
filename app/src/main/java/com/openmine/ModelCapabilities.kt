package com.openmine

import java.util.Locale

/**
 * Model-agnostic capability system. Every model, local or remote, is reached through the
 * same OpenAI-compatible [ModelClient] interface; a profile only describes what a model can
 * do so Open Mine can pick an appropriate one for a task. Nothing here downloads, bundles or
 * claims to have verified a model: [Verification] records what has actually been observed.
 */
enum class ModelRole { ROUTER, TOOL_SPECIALIST, EXTRACTION, GENERAL, PLANNING }
enum class ModelRuntime(val label:String,val integrated:Boolean) {
    LLAMA_CPP_GGUF("Built-in llama.cpp (GGUF)",true),
    OPENAI_COMPATIBLE("OpenAI-compatible HTTPS / Ollama",true),
    CACTUS("Cactus engine (not integrated in this build)",false),
}
enum class Verification(val label:String) {
    UNVERIFIED("Not yet loaded or tested in Open Mine"),
    IMPORT_VALIDATED("GGUF header and architecture accepted"),
    HEALTH_PASSED("Model server health check passed"),
    INFERENCE_PASSED("Completed an inference request"),
}
enum class TaskKind(val requires:ModelRole,val label:String) {
    ROUTE_INTENT(ModelRole.ROUTER,"Classify and route a request"),
    TOOL_CALL(ModelRole.TOOL_SPECIALIST,"Structured tool call"),
    EXTRACT(ModelRole.EXTRACTION,"Extract structured fields"),
    CHAT(ModelRole.GENERAL,"General conversation and reasoning"),
    PLAN(ModelRole.PLANNING,"Heavy planning, coding or analysis"),
}

data class ModelProfile(
    val id:String,
    val displayName:String,
    val roles:Set<ModelRole>,
    val runtime:ModelRuntime,
    val toolCalling:Boolean,
    val contextTokens:Int,
    /** Conservative resident memory needed for the app's bounded context, in MiB. */
    val minimumMemoryMb:Int,
    val license:String,
    /** True only when redistribution inside the APK has been confirmed. Weights stay user-imported otherwise. */
    val redistributionConfirmed:Boolean=false,
    val verification:Verification=Verification.UNVERIFIED,
    val notes:String="",
) {
    val runnable:Boolean get() = runtime.integrated
}

/** A model the user can actually reach right now: a loaded built-in model or a configured endpoint. */
data class ConfiguredModel(val endpoint:String,val modelId:String,val profile:ModelProfile,val healthy:Boolean)

data class RouteDecision(val task:TaskKind,val model:ConfiguredModel?,val reason:String)

object ModelCatalog {
    /**
     * Candidates under evaluation. Figures are planning estimates for scheduling, not benchmarks;
     * every entry starts UNVERIFIED and must load and answer in the intended runtime before support
     * is claimed. Licenses must be confirmed against each model card before any redistribution.
     */
    val candidates:List<ModelProfile> = listOf(
        ModelProfile("botpsy-0.8b","BotPsy 0.8B",setOf(ModelRole.ROUTER),ModelRuntime.LLAMA_CPP_GGUF,true,8192,900,
            "Confirm on model card",notes="Intent and tool routing candidate; unbenchmarked."),
        ModelProfile("needle-3","Cactus Needle 3",setOf(ModelRole.ROUTER,ModelRole.TOOL_SPECIALIST,ModelRole.EXTRACTION),ModelRuntime.CACTUS,true,4096,128,
            "Source-available (personal/hobby use reported); commercial redistribution unconfirmed",
            notes="Evaluated and not shipped: on 45 unseen setup requests it chose 10 actions right and 10 wrong, and its confidence did not separate them. Needs the Cactus runtime."),
        ModelProfile("lfm3-1.7b-uncensored","LFM3 1.7B Uncensored (AtomicChat)",setOf(ModelRole.ROUTER,ModelRole.TOOL_SPECIALIST,ModelRole.EXTRACTION,ModelRole.GENERAL),ModelRuntime.LLAMA_CPP_GGUF,true,32768,1600,
            "LFM Open License v1.0",
            notes="Open Mine's built-in model, downloaded on first run. 37 of 38 unseen setup actions routed correctly on a host test; phone speed unmeasured."),
        ModelProfile("gemma-4-e2b","Gemma 4 E2B (GGUF)",setOf(ModelRole.GENERAL,ModelRole.TOOL_SPECIALIST),ModelRuntime.LLAMA_CPP_GGUF,true,32768,3600,
            "Gemma terms (confirm)",notes="Primary general assistant candidate. Text inference only through the bundled server."),
        ModelProfile("gemma-4-e4b","Gemma 4 E4B (GGUF)",setOf(ModelRole.GENERAL,ModelRole.TOOL_SPECIALIST),ModelRuntime.LLAMA_CPP_GGUF,true,32768,6000,
            "Gemma terms (confirm)",notes="Higher quality; needs a 12 GB-class phone with headroom."),
        ModelProfile("nanbeige-3b","Nanbeige 3B (GGUF)",setOf(ModelRole.GENERAL),ModelRuntime.LLAMA_CPP_GGUF,false,32768,2600,
            "Confirm on model card",notes="Experimental quality alternative; runtime compatibility unverified."),
        ModelProfile("qwen3-1.7b","Qwen3 1.7B (GGUF)",setOf(ModelRole.ROUTER,ModelRole.EXTRACTION,ModelRole.GENERAL),ModelRuntime.LLAMA_CPP_GGUF,true,32768,1700,
            "Apache-2.0",notes="Well-supported baseline for comparison benchmarks."),
    )

    /** Infer a conservative profile for an imported GGUF from its validated architecture and size. */
    fun profileForGguf(fileName:String,architecture:String,bytes:Long):ModelProfile {
        val name=fileName.lowercase(Locale.ROOT)
        val sizeMb=(bytes/(1024*1024)).toInt()
        val small=bytes<1_400L*1024*1024
        val roles=buildSet {
            add(ModelRole.GENERAL)
            if(small){add(ModelRole.ROUTER);add(ModelRole.EXTRACTION)}
            if(architecture.startsWith("lfm2") || name.contains("tool") || name.contains("function"))add(ModelRole.TOOL_SPECIALIST)
            if(!small && bytes>=2_500L*1024*1024)add(ModelRole.PLANNING)
        }
        val tools=architecture.startsWith("qwen") || architecture.startsWith("gemma") || architecture.startsWith("lfm2") ||
            architecture in setOf("llama","mistral","granite","granitemoe","smollm3")
        return ModelProfile("gguf:$fileName",fileName.removeSuffix(".gguf"),roles,ModelRuntime.LLAMA_CPP_GGUF,tools,8192,
            sizeMb+sizeMb/4+512,"See the model's source card",verification=Verification.IMPORT_VALIDATED,
            notes="Profile inferred from GGUF architecture '$architecture' and file size.")
    }

    /** A user-configured remote/Ollama endpoint is treated as a capable planner without guessing its limits. */
    fun profileForEndpoint(endpoint:String,modelId:String):ModelProfile {
        val local=endpoint.startsWith("http://127.0.0.1") || endpoint.startsWith("http://localhost")
        return ModelProfile("api:$endpoint#$modelId",modelId,
            if(local)setOf(ModelRole.GENERAL,ModelRole.TOOL_SPECIALIST) else setOf(ModelRole.GENERAL,ModelRole.TOOL_SPECIALIST,ModelRole.PLANNING,ModelRole.EXTRACTION),
            ModelRuntime.OPENAI_COMPATIBLE,true,32768,0,"Provider terms",notes=if(local)"Local OpenAI-compatible server" else "User-selected remote provider")
    }
}

/**
 * Picks the least expensive reachable model that advertises the capability a task needs, then falls back
 * to a general model. Remote providers are used only when configured by the user; offline tasks never
 * require a cloud service.
 */
object ModelRouter {
    fun route(task:TaskKind,available:List<ConfiguredModel>,deviceMemoryMb:Long=Long.MAX_VALUE,offlineOnly:Boolean=false):RouteDecision {
        val usable=available.filter{it.healthy && it.profile.runnable && it.profile.minimumMemoryMb<=deviceMemoryMb}
            .filter{!offlineOnly || it.profile.runtime==ModelRuntime.LLAMA_CPP_GGUF || isLoopback(it.endpoint)}
        if(usable.isEmpty())return RouteDecision(task,null,when{
            available.isEmpty()->"No model is configured. Import a GGUF or add an endpoint."
            available.any{it.healthy && it.profile.runnable && it.profile.minimumMemoryMb>deviceMemoryMb}->"Configured models need more memory than this device has. Choose a smaller quantization."
            available.any{!it.healthy}->"No configured model is currently healthy. Load a model or check the endpoint."
            offlineOnly->"Only remote models are configured, and this task must stay offline."
            else->"No configured model can run in this build's runtimes."
        })
        val specialists=usable.filter{task.requires in it.profile.roles}
        // Cheapest local specialist first; remote providers (memory 0) are ranked after local ones for
        // non-planning work so that small routine tasks stay on the device.
        val ranked=specialists.sortedWith(compareBy<ConfiguredModel>{if(task!=TaskKind.PLAN && !isLocal(it))1 else 0}.thenBy{it.profile.minimumMemoryMb})
        ranked.firstOrNull()?.let{return RouteDecision(task,it,"${it.profile.displayName} advertises ${task.requires.name.lowercase(Locale.ROOT).replace('_',' ')}")}
        val general=usable.filter{ModelRole.GENERAL in it.profile.roles}.sortedByDescending{it.profile.minimumMemoryMb}.firstOrNull()
            ?: usable.first()
        return RouteDecision(task,general,"No specialist for ${task.label.lowercase(Locale.ROOT)}; using ${general.profile.displayName} as the general model")
    }
    private fun isLoopback(endpoint:String)=endpoint.startsWith("http://127.0.0.1") || endpoint.startsWith("http://localhost")
    private fun isLocal(model:ConfiguredModel)=model.profile.runtime==ModelRuntime.LLAMA_CPP_GGUF || isLoopback(model.endpoint)
}
