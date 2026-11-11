package com.openmine

import org.json.JSONArray
import org.json.JSONObject

enum class Severity { BLOCKING, WARNING, INFO, OK }

/** Repairs the Kotlin layer can perform or open for the user; the model never performs these directly. */
enum class RepairAction(val label:String) {
    VERIFY_SHELL("Check shell"),
    SETUP_SHELL("Set up / repair shell"),
    PROVISION_ENGINE("Install / repair engine"),
    IMPORT_MODEL("Import GGUF weights"),
    DIAGNOSE_FILE("Diagnose a model file"),
    LOAD_MODEL("Open Models to load a model"),
    CHECK_MODEL_HEALTH("Check model health"),
    CONFIGURE_ENDPOINT("Open chat connection settings"),
    REBUILD_INDEX("Rebuild knowledge index"),
    NOTIFICATION_PERMISSION("Allow notifications"),
    RETRY_OPERATION("Retry last operation"),
}

data class Finding(val id:String,val severity:Severity,val title:String,val detail:String,val repair:RepairAction?=null)

/** Facts gathered by the app. Each value comes from a real probe or file listing, never from a saved label. */
data class SetupSnapshot(
    val debugBuild:Boolean,
    val abis:List<String>,
    val shellInstalled:Boolean,
    val shellHealthy:Boolean,
    val engineInstalled:Boolean,
    val engineHealthy:Boolean,
    val modelHealthy:Boolean,
    val importedModels:List<String>,
    val freeStorageBytes:Long,
    val totalMemoryBytes:Long,
    val chatEndpoint:String,
    val chatModel:String,
    val libraryIssues:List<String>,
    val indexReady:Boolean,
    val notificationsAllowed:Boolean,
    val lastFailure:OperationRecord?,
    val runtimeBusy:Boolean,
)

object SetupDiagnostics {
    private const val GiB=1024L*1024*1024

    fun evaluate(s:SetupSnapshot):List<Finding> {
        val findings=mutableListOf<Finding>()
        val arm64="arm64-v8a" in s.abis
        if(!arm64) findings+=Finding("abi",Severity.WARNING,"Not an ARM64 device",
            "The included engine is ARM64-only (${s.abis.joinToString()}). Local GGUF inference needs a source build; remote or Ollama endpoints still work.")
        when{
            !s.shellInstalled && s.debugBuild -> findings+=Finding("shell-missing",Severity.BLOCKING,"Linux runtime not installed",
                "Local models and the terminal need Open Mine's Linux runtime. Setup downloads verified Alpine prerequisites.",RepairAction.SETUP_SHELL)
            !s.shellInstalled -> findings+=Finding("shell-missing",Severity.BLOCKING,"Linux runtime not installed",
                "This build cannot download runtime prerequisites. Use a remote or Ollama endpoint for chat.",RepairAction.CONFIGURE_ENDPOINT)
            !s.shellHealthy -> findings+=Finding("shell-unverified",Severity.WARNING,"Shell not verified this session",
                "Runtime files are present but the execution check has not passed since Open Mine started.",RepairAction.VERIFY_SHELL)
            else -> findings+=Finding("shell-ok",Severity.OK,"Linux shell","Execution check passed.")
        }
        if(s.shellInstalled && arm64){
            if(!s.engineInstalled) findings+=Finding("engine-missing",Severity.BLOCKING,"GGUF engine not installed",
                "Install the included llama.cpp server to run imported models offline.",if(s.debugBuild && s.shellHealthy)RepairAction.PROVISION_ENGINE else RepairAction.VERIFY_SHELL)
            else if(!s.engineHealthy && s.shellHealthy) findings+=Finding("engine-unverified",Severity.INFO,"Engine not checked",
                "Engine files exist; they are verified when a model is loaded.",RepairAction.PROVISION_ENGINE.takeIf{s.debugBuild})
        }
        if(s.importedModels.isEmpty()) findings+=Finding("no-models",Severity.WARNING,"No local model imported",
            "Import a GGUF that fits this phone. If an import failed, the file doctor explains why.",RepairAction.IMPORT_MODEL)
        else if(!s.modelHealthy) findings+=Finding("model-stopped",Severity.INFO,"No local model running",
            "${s.importedModels.size} imported model${if(s.importedModels.size==1)"" else "s"}; none passed a health check this session.",RepairAction.LOAD_MODEL)
        else findings+=Finding("model-ok",Severity.OK,"Local model server","Health check passed on 127.0.0.1:8080.")
        if(s.chatEndpoint.isBlank()) findings+=Finding("endpoint-missing",Severity.WARNING,"Chat has no model connection",
            "Choose the built-in model, Ollama, or an OpenAI-compatible HTTPS provider.",RepairAction.CONFIGURE_ENDPOINT)
        else {
            val valid=runCatching{ModelEndpoint.chatUri(s.chatEndpoint)}
            if(valid.isFailure) findings+=Finding("endpoint-invalid",Severity.BLOCKING,"Chat endpoint is invalid",valid.exceptionOrNull()?.message.orEmpty(),RepairAction.CONFIGURE_ENDPOINT)
            else if(s.chatModel.isBlank()) findings+=Finding("model-id-missing",Severity.WARNING,"Chat model ID missing",
                "Select a model ID for ${s.chatEndpoint}; use Discover models when the server supports it.",RepairAction.CONFIGURE_ENDPOINT)
            else if(s.chatEndpoint==ModelEndpoint.BUILT_IN && !s.modelHealthy) findings+=Finding("builtin-down",Severity.WARNING,
                "Chat uses the built-in model, which is not running","Load a model in Models before sending messages.",
                if(s.importedModels.isEmpty())RepairAction.IMPORT_MODEL else RepairAction.LOAD_MODEL)
        }
        if(s.freeStorageBytes in 0 until 2*GiB) findings+=Finding("storage-low",Severity.WARNING,"Low free storage",
            "${s.freeStorageBytes/(1024*1024)} MiB free. Model imports need the model size plus headroom.")
        if(s.totalMemoryBytes in 1 until 6*GiB) findings+=Finding("memory-low",Severity.INFO,"Limited memory",
            "${s.totalMemoryBytes/(1024*1024)} MiB RAM. Prefer models under 2 GB, such as 1–2B Q4 quantizations.")
        if(s.libraryIssues.isNotEmpty() || !s.indexReady) findings+=Finding("library",Severity.WARNING,"Knowledge library needs attention",
            (s.libraryIssues.take(3)+listOfNotNull("Index not validated against sources.".takeIf{!s.indexReady})).joinToString("\n"),RepairAction.REBUILD_INDEX)
        if(!s.notificationsAllowed) findings+=Finding("notifications",Severity.INFO,"Notifications are off",
            "Android hides the runtime's ongoing notification, so long setup and model operations are harder to monitor or stop.",RepairAction.NOTIFICATION_PERMISSION)
        s.lastFailure?.let{f-> findings+=Finding("last-failure",Severity.WARNING,"Last operation ${f.state.name.lowercase()}: ${f.label}",
            f.detail.ifBlank{"No details were reported."},RepairAction.RETRY_OPERATION.takeIf{f.retry!=null && !s.runtimeBusy})}
        return findings.sortedBy{it.severity.ordinal}
    }

    /** Read-only, bounded JSON for the reviewed `setup_diagnostics` model tool. Contains no credentials or file contents. */
    fun toModelJson(findings:List<Finding>):String {
        val array=JSONArray()
        findings.forEach{array.put(JSONObject().put("id",it.id).put("severity",it.severity.name).put("title",it.title)
            .put("detail",it.detail.take(500)).put("user_repair",it.repair?.label ?: JSONObject.NULL))}
        return JSONObject().put("findings",array)
            .put("note","Repairs are performed by the user from Setup Assistant. You cannot run them; explain which to choose.").toString()
    }
}
