package com.openmine.ai

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * The fixed capabilities the built-in guide may propose. The model only names a tool and its
 * arguments; Kotlin validates them and owns permissions, storage, intents and execution.
 */
enum class GuideTool(
    val description: String,
    val parameters: List<Param>,
    /** Read-only tools inspect local state and run without a prompt; their data never leaves the phone. */
    val readOnly: Boolean = false,
) {
    install_runtime("First-time setup: install the Linux terminal environment. Use when the user wants a terminal, shell or Linux set up.", emptyList()),
    download_chat_model("Download the offline chat AI model (LFM3) from the internet. Use when the user wants to chat offline or needs a local model.", emptyList()),
    request_folder_access("Let Open Mine read and write a phone storage folder such as Downloads or Documents. Use when the user mentions a phone folder.",
        listOf(Param("folder", "Folder name the user mentioned, e.g. Downloads", required = true), Param("purpose", "What it will hold, e.g. projects"))),
    run_command("Run a Linux shell command, for example to install packages like git or python with apk add. The user approves the exact command first.",
        listOf(Param("command", "The exact shell command, e.g. apk add git python3", required = true))),
    run_diagnostics("Find out what is missing or why something in Open Mine is broken or not working.", emptyList(), readOnly = true),
    import_model_file("Add a GGUF model file the user already has on the phone.", emptyList()),
    search_knowledge("Search the user's own notes, documents, memory and imported knowledge. Returns short matching passages with ids.",
        listOf(Param("query", "What to look for", required = true)), readOnly = true),
    read_slice("Read one passage of the user's knowledge by the id that search_knowledge returned.",
        listOf(Param("id", "Passage id from search_knowledge", required = true)), readOnly = true),
    save_memory("Remember a fact or preference for future conversations. The user approves what is saved.",
        listOf(Param("title", "Short title", required = true), Param("text", "What to remember", required = true))),
    ;

    data class Param(val name: String, val description: String, val required: Boolean = false)
    /** Fulfilled by a system picker; the result is the user's choice there. */
    val waitsForPicker get() = this == request_folder_access || this == import_model_file

    fun schema(): JSONObject {
        val props = JSONObject(); val required = JSONArray()
        parameters.forEach { props.put(it.name, JSONObject().put("type", "string").put("description", it.description)); if (it.required) required.put(it.name) }
        return JSONObject().put("type", "object").put("properties", props).put("required", required)
    }

    companion object {
        val names = entries.map { it.name }.toSet()
        fun of(name: String) = entries.firstOrNull { it.name == name }

        /** Needle's tool format: a JSON array of {name, description, parameters}. */
        fun cactusJson(tools: Collection<GuideTool> = entries): String =
            JSONArray().apply { tools.forEach { put(JSONObject().put("name", it.name).put("description", it.description).put("parameters", it.schema())) } }.toString()

        /** OpenAI-compatible tool definitions for LFM3 through the embedded server. */
        fun openAiJson(tools: Collection<GuideTool> = entries): JSONArray =
            JSONArray().apply { tools.forEach { put(JSONObject().put("type", "function").put("function",
                JSONObject().put("name", it.name).put("description", it.description).put("parameters", it.schema()))) } }
    }
}

/** A validated request awaiting execution. [summary] is exactly what the user is asked to approve. */
data class GuideAction(val tool: GuideTool, val arguments: Map<String, String>, val summary: String, val callId: String = "") {
    val command get() = arguments["command"].orEmpty()
}

object GuideActions {
    private val folders = setOf("downloads", "documents", "download", "document", "pictures", "music", "movies", "dcim", "projects")

    /** Validates model arguments strictly: unknown keys, wrong types or oversized values are rejected. */
    fun validate(name: String, arguments: JSONObject, callId: String = ""): GuideAction {
        val tool = GuideTool.of(name) ?: throw IllegalArgumentException("Unknown tool '$name'; nothing ran.")
        val allowed = tool.parameters.map { it.name }.toSet()
        val keys = arguments.keys().asSequence().toSet()
        require(keys.all { it in allowed }) { "Unexpected arguments for ${tool.name}: ${(keys - allowed).joinToString()}; nothing ran." }
        val values = keys.associateWith { key ->
            val value = arguments.get(key)
            require(value is String) { "${tool.name}.$key must be text; nothing ran." }
            require(value.length <= if (key == "text") 4000 else 1000) { "${tool.name}.$key is too long; nothing ran." }
            value.trim()
        }
        tool.parameters.filter { it.required }.forEach { require(!values[it.name].isNullOrBlank()) { "${tool.name} needs ${it.name}; nothing ran." } }
        val summary = when (tool) {
            GuideTool.install_runtime -> "Install the Linux terminal environment (downloads verified Alpine packages)."
            GuideTool.download_chat_model -> "Download ${BuiltInModels.LFM3.displayName} (${BuiltInModels.LFM3.sizeBytes / (1024 * 1024)} MiB), verified by SHA-256."
            GuideTool.request_folder_access -> "Open Android's folder picker so you can grant access to ${values["folder"]}${values["purpose"]?.takeIf { it.isNotBlank() }?.let { " for $it" }.orEmpty()}."
            GuideTool.run_command -> "Run in the Linux terminal:\n${com.openmine.RuntimeCommandPolicy.validate(values.getValue("command"))}"
            GuideTool.run_diagnostics -> "Check Open Mine's setup."
            GuideTool.import_model_file -> "Open the file picker to choose a GGUF model to import."
            GuideTool.search_knowledge -> "Search your knowledge for \"${values["query"]}\"."
            GuideTool.read_slice -> "Read passage ${values["id"]}."
            GuideTool.save_memory -> "Remember \"${values["title"]}\": ${values["text"]}"
        }
        return GuideAction(tool, values, summary, callId)
    }

    fun normalizedFolder(name: String): String? = name.lowercase(Locale.ROOT).trim().removePrefix("my ").removeSuffix(" folder").trim().takeIf { it in folders }
}

/** Approval policy. During the tutorial everything consequential asks; afterwards the user chooses. */
enum class AutonomyMode { ASK_EVERY_TIME, FULL_AUTONOMY }

object ApprovalPolicy {
    private val destructive = listOf(
        Regex("""\brm\s+(-[a-zA-Z]*[rf][a-zA-Z]*\s+)+(/|~|\*|\${'$'}HOME)(\s|$)"""), Regex("""\bmkfs\b"""), Regex("""\bdd\s+.*\bof="""),
        Regex(""":\(\)\s*\{"""), Regex("""\bchmod\s+-R\s+0?00\b"""), Regex(""">\s*/dev/(sd|block|mmc)"""), Regex("""\b(shutdown|reboot)\b"""),
    )
    fun isDestructive(command: String) = destructive.any { it.containsMatchIn(command) }

    /**
     * True when the user must approve. Read-only tools never prompt. In full autonomy, commands and
     * downloads run automatically except destructive commands, which always ask. Folder access always
     * goes through Android's own picker, which is itself the user's decision.
     */
    fun needsApproval(action: GuideAction, mode: AutonomyMode, tutorialComplete: Boolean): Boolean = when {
        action.tool.readOnly -> false
        action.tool == GuideTool.run_command && isDestructive(action.command) -> true
        !tutorialComplete || mode == AutonomyMode.ASK_EVERY_TIME -> true
        action.tool == GuideTool.save_memory -> true
        else -> false
    }
}
