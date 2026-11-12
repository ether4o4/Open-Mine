package com.openmine.ai

import android.content.Context
import android.net.Uri
import com.openmine.MemoryStore
import com.openmine.OpenMineObjectStore
import com.openmine.SetupDiagnostics
import com.openmine.SetupProbe
import com.openmine.sandbox.OpenMineRuntime
import com.openmine.sandbox.TerminalResultFormatter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

data class GuideMessage(val id: String = UUID.randomUUID().toString(), val role: String, val text: String, val time: Long = System.currentTimeMillis()) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("role", role).put("text", text).put("time", time)
    companion object { fun from(j: JSONObject) = GuideMessage(j.getString("id"), j.getString("role"), j.getString("text"), j.optLong("time")) }
}

/** Requests that need the Activity (system pickers). The screen fulfils them and reports back. */
sealed class GuideUiRequest { data object PickFolder : GuideUiRequest(); data object PickModelFile : GuideUiRequest() }

data class GuideState(
    val messages: List<GuideMessage> = emptyList(),
    val pending: GuideAction? = null,
    val busy: Boolean = false,
    val status: String = "",
    val needleReady: Boolean = false,
    val needleNote: String = "",
    val lfmInstalled: Boolean = false,
    val lfmServer: LocalLlmServer.State = LocalLlmServer.State.STOPPED,
    val lfmNote: String = "",
    val downloadProgress: Float? = null,
    val uiRequest: GuideUiRequest? = null,
    val tutorialComplete: Boolean = false,
    val autonomy: AutonomyMode = AutonomyMode.ASK_EVERY_TIME,
    val grantedFolders: List<String> = emptyList(),
)

/**
 * The built-in setup and knowledge guide. Needle 3 (bundled) routes requests to tools in under a second;
 * LFM3 (downloaded) holds the conversation and handles anything Needle is unsure of. Every consequential
 * action is validated in Kotlin and, per [ApprovalPolicy], approved by the user before it runs.
 */
class GuideAgent private constructor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences("open_mine_guide", Context.MODE_PRIVATE)
    private val file = File(context.filesDir, "ai/guide-conversation.json")
    private val runtime get() = OpenMineRuntime.get(context)
    private val server = LocalLlmServer.get()
    private val needle = NeedleEngine(BuiltInModels.file(context.filesDir, BuiltInModels.NEEDLE3))
    private val _state = MutableStateFlow(load())
    val state: StateFlow<GuideState> = _state
    private var job: Job? = null
    private var downloadJob: Job? = null
    /** LFM3 conversation awaiting an approval decision, so the turn resumes with the real tool result. */
    private var suspendedTurn: JSONArray? = null
    /** Tool call waiting on a system picker; its result is the user's choice, reported later. */
    private var externalCall: GuideAction? = null
    private var knowledge: Pair<Long, KnowledgeIndex>? = null

    init {
        scope.launch { prepareNeedle() }
        scope.launch { server.state.collect { s -> update { it.copy(lfmServer = s, lfmNote = if (s == LocalLlmServer.State.FAILED) server.lastError else it.lfmNote) } } }
        if (_state.value.messages.isEmpty()) post("assistant", WELCOME)
    }

    private fun update(change: (GuideState) -> GuideState) { _state.value = change(_state.value) }

    private fun load(): GuideState {
        val messages = runCatching { JSONArray(file.readText()).let { a -> List(a.length()) { GuideMessage.from(a.getJSONObject(it)) } } }.getOrDefault(emptyList())
        return GuideState(messages = messages, lfmInstalled = BuiltInModels.installed(context.filesDir, BuiltInModels.LFM3),
            tutorialComplete = prefs.getBoolean("tutorial_complete", false),
            autonomy = runCatching { AutonomyMode.valueOf(prefs.getString("autonomy", "")!!) }.getOrDefault(AutonomyMode.ASK_EVERY_TIME),
            grantedFolders = context.contentResolver.persistedUriPermissions.map { it.uri.toString() })
    }

    private fun post(role: String, text: String) {
        update { it.copy(messages = (it.messages + GuideMessage(role = role, text = text.take(8000))).takeLast(200)) }
        runCatching { file.parentFile?.mkdirs(); val a = JSONArray(); _state.value.messages.forEach { a.put(it.toJson()) }
            val tmp = File(file.parentFile, file.name + ".tmp"); tmp.writeText(a.toString()); tmp.renameTo(file) }
    }

    private suspend fun prepareNeedle() {
        val result = runCatching {
            withContext(Dispatchers.IO) { BuiltInModels.installBundled(context.filesDir, BuiltInModels.NEEDLE3) { context.assets.open(it) } }
            needle.ensureLoaded()
        }
        val error = result.exceptionOrNull()?.message ?: result.getOrNull()
        update { it.copy(needleReady = error == null, needleNote = error ?: "Needle 3 ready (offline, bundled)") }
    }

    // ---- User input ---------------------------------------------------------------------------

    fun send(text: String) {
        val message = text.trim(); if (message.isEmpty() || _state.value.busy) return
        post("user", message)
        job = scope.launch {
            update { it.copy(busy = true, status = "Thinking…") }
            try {
                if (server.state.value == LocalLlmServer.State.READY) lfmTurn(message) else needleTurn(message)
            } catch (e: CancellationException) { post("system", "Stopped."); throw e }
            catch (e: Exception) { post("system", "Something went wrong: ${e.message}. Nothing else was changed.") }
            finally { update { it.copy(busy = false, status = "") } }
        }
    }

    fun stop() { job?.cancel(); suspendedTurn = null; externalCall = null; update { it.copy(pending = null, busy = false) } }

    /** Quick actions are explicit buttons: they go straight to review with no model involved. */
    fun quick(tool: GuideTool, args: Map<String, String> = emptyMap()) {
        if (_state.value.busy) return
        val action = runCatching { GuideActions.validate(tool.name, JSONObject(args)) }.getOrElse { post("system", it.message.orEmpty()); return }
        job = scope.launch { offer(action) }
    }

    private suspend fun needleTurn(message: String) {
        val route = if (_state.value.needleReady) needle.route(message, GuideTool.cactusJson(), GuideTool.names, NEEDLE_SYSTEM)
        else NeedleRoute(null, JSONObject(), 0.0, _state.value.needleNote)
        if (!NeedleResult.isConfident(route)) {
            post("assistant", buildString {
                append("I'm not sure which step you mean")
                if (!_state.value.lfmInstalled) append(". Download the LFM3 chat model so I can understand free-form questions; until then, use the buttons above")
                else append(". Start LFM3 above for full conversation, or pick one of the buttons")
                append(".")
            })
            return
        }
        val action = runCatching { GuideActions.validate(route.tool!!, route.arguments) }.getOrElse { post("system", it.message.orEmpty()); return }
        post("assistant", "Needle suggests: ${action.summary}")
        offer(action)
    }

    /** Either asks the user or runs immediately, depending on the approval policy. */
    private suspend fun offer(action: GuideAction) {
        val s = _state.value
        if (ApprovalPolicy.needsApproval(action, s.autonomy, s.tutorialComplete)) update { it.copy(pending = action) }
        else if (action.tool.waitsForPicker) { externalCall = action; execute(action) }
        else { val result = execute(action); post("result", result); resumeAfter(action, result) }
    }

    fun approve() {
        val action = _state.value.pending ?: return
        update { it.copy(pending = null) }
        job = scope.launch {
            update { it.copy(busy = true, status = "Running ${action.tool.name.replace('_', ' ')}…") }
            try {
                if (action.tool.waitsForPicker) { externalCall = action; execute(action) }
                else { val result = execute(action); post("result", result); resumeAfter(action, result) }
            }
            catch (e: CancellationException) { post("system", "Cancelled."); throw e }
            catch (e: Exception) { post("result", "Failed: ${e.message}"); resumeAfter(action, "Failed: ${e.message}") }
            finally { update { it.copy(busy = false, status = "") } }
        }
    }

    fun decline() {
        val action = _state.value.pending ?: return
        update { it.copy(pending = null) }
        post("system", "Declined. ${action.tool.name.replace('_', ' ')} did not run.")
        job = scope.launch { resumeAfter(action, "The user declined. This action did not run; do not claim it did.") }
    }

    private suspend fun resumeAfter(action: GuideAction, result: String) {
        val turn = suspendedTurn ?: return
        suspendedTurn = null
        if (action.callId.isBlank()) return
        turn.put(JSONObject().put("role", "tool").put("tool_call_id", action.callId).put("content", result.take(6000)))
        update { it.copy(busy = true, status = "Thinking…") }
        try { continueLfm(turn) } finally { update { it.copy(busy = false, status = "") } }
    }

    // ---- LFM3 conversation with progressive disclosure ------------------------------------------

    private fun systemPrompt(): String {
        val findings = runCatching { SetupDiagnostics.evaluate(SetupProbe.snapshot(context)).filter { it.severity != com.openmine.Severity.OK }.take(5) }.getOrDefault(emptyList())
        val s = _state.value
        return buildString {
            append("You are Open Mine's built-in assistant on the user's Android phone. Help set up and use Open Mine in short, plain sentences. ")
            append("Call a tool when an action or lookup is needed; Open Mine shows consequential tools to the user for approval. ")
            append("The terminal is Alpine Linux in an app sandbox (no root): install packages with apk add, Python packages with pip. ")
            append("Never claim a tool ran unless you received its result. For questions about the user's notes or documents, call search_knowledge, then read_slice for the passages you need, and cite their ids.\n")
            append("Setup now: ").append(if (findings.isEmpty()) "everything checked is working." else findings.joinToString("; ") { it.title }).append('\n')
            append(knowledgeIndex().summary()).append('\n')
            append("Approval: ").append(if (!s.tutorialComplete) "tutorial, every action asks" else s.autonomy.name.lowercase().replace('_', ' '))
        }
    }

    private suspend fun lfmTurn(message: String) {
        val history = _state.value.messages.dropLast(1).filter { it.role == "user" || it.role == "assistant" }.takeLast(8)
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", systemPrompt()))
        history.forEach { messages.put(JSONObject().put("role", it.role).put("content", it.text.take(2000))) }
        messages.put(JSONObject().put("role", "user").put("content", message))
        continueLfm(messages)
    }

    private suspend fun continueLfm(messages: JSONArray) {
        repeat(MAX_ROUNDS) { round ->
            currentCoroutineContext().ensureActive()
            update { it.copy(status = if (round == 0) "LFM3 is thinking…" else "LFM3 is reading results… (step ${round + 1})") }
            val reply = chat(messages)
            val calls = reply.optJSONArray("tool_calls")
            if (calls == null || calls.length() == 0) {
                post("assistant", reply.optString("content").trim().ifBlank { "(no answer)" }); return
            }
            reply.remove("reasoning_content"); messages.put(reply)
            for (i in 0 until calls.length()) {
                val call = calls.getJSONObject(i); val fn = call.getJSONObject("function")
                val id = call.optString("id", "call-$round-$i")
                val action = runCatching { GuideActions.validate(fn.getString("name"), JSONObject(fn.optString("arguments", "{}").ifBlank { "{}" }), id) }
                if (action.isFailure) { messages.put(JSONObject().put("role", "tool").put("tool_call_id", id).put("content", action.exceptionOrNull()?.message)); continue }
                val a = action.getOrThrow()
                val s = _state.value
                if (ApprovalPolicy.needsApproval(a, s.autonomy, s.tutorialComplete)) {
                    // Remaining calls in this batch are dropped; the model is told after the decision.
                    suspendedTurn = messages; update { it.copy(pending = a) }; return
                }
                if (a.tool.waitsForPicker) { suspendedTurn = messages; externalCall = a; execute(a); return }
                val result = execute(a)
                if (!a.tool.readOnly) post("result", result)
                messages.put(JSONObject().put("role", "tool").put("tool_call_id", id).put("content", result.take(6000)))
            }
        }
        post("assistant", "I stopped after $MAX_ROUNDS steps. Ask again if you want me to keep going.")
    }

    private suspend fun chat(messages: JSONArray): JSONObject = runInterruptible(Dispatchers.IO) {
        val body = JSONObject().put("model", "local").put("messages", messages).put("tools", GuideTool.openAiJson())
            .put("temperature", 0.1).put("top_k", 50).put("repeat_penalty", 1.1).put("max_tokens", 1024).toString().toByteArray()
        val c = URL(server.endpoint + "/chat/completions").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 5000; c.readTimeout = 300_000
            c.setRequestProperty("Content-Type", "application/json"); c.setRequestProperty("Authorization", "Bearer ${server.apiKey}")
            c.setFixedLengthStreamingMode(body.size); c.outputStream.use { it.write(body) }
            check(c.responseCode == 200) { "LFM3 server returned HTTP ${c.responseCode}" }
            val text = c.inputStream.use { com.openmine.ProjectStore.readBounded(it, 4 * 1024 * 1024) }.toString(Charsets.UTF_8)
            JSONObject(text).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        } finally { c.disconnect() }
    }

    // ---- Tool execution (Kotlin owns every effect) -------------------------------------------------

    private suspend fun execute(action: GuideAction): String = when (action.tool) {
        GuideTool.run_diagnostics -> SetupDiagnostics.toModelJson(SetupDiagnostics.evaluate(SetupProbe.snapshot(context)))
        GuideTool.search_knowledge -> knowledgeIndex().search(action.arguments.getValue("query")).let { hits ->
            if (hits.isEmpty()) "No matching passages." else JSONArray().apply { hits.forEach { put(JSONObject().put("id", it.id).put("title", it.title)
                .put("namespace", it.namespace).put("score", "%.2f".format(it.score)).put("preview", it.preview)) } }.toString() }
        GuideTool.read_slice -> knowledgeIndex().read(action.arguments.getValue("id")) ?: "No passage with that id. Use an id from search_knowledge."
        GuideTool.save_memory -> MemoryStore(context.filesDir).save(null, action.arguments.getValue("title"), action.arguments.getValue("text"), "shared")
            .let { knowledge = null; "Saved memory ${it.id}." }
        GuideTool.run_command -> runCommand(action.command)
        GuideTool.install_runtime -> installRuntime()
        GuideTool.download_chat_model -> { startLfmDownload(); "Download of ${BuiltInModels.LFM3.displayName} started; progress is shown at the top. LFM3 starts automatically when it is verified." }
        GuideTool.request_folder_access -> { update { it.copy(uiRequest = GuideUiRequest.PickFolder) }; "Android's folder picker is open. Waiting for the user's choice." }
        GuideTool.import_model_file -> { update { it.copy(uiRequest = GuideUiRequest.PickModelFile) }; "File picker opened. The model file doctor checks the file before import." }
    }

    private suspend fun runCommand(command: String): String {
        if (!runtime.shellInstalled.value) return "The Linux terminal is not installed yet. Propose install_runtime first. The command did not run."
        val result = runtime.controlCommand(com.openmine.RuntimeCommandPolicy.validate(command), 300)
        return TerminalResultFormatter.format(result).takeLast(4000)
    }

    private suspend fun installRuntime(): String {
        if (runtime.shellInstalled.value && runtime.shellHealthy.value) return "The Linux terminal is already installed and its execution check passed."
        if (!com.openmine.BuildConfig.DEBUG) return "This build cannot download the Linux runtime. Nothing changed."
        if (runtime.shellInstalled.value) runtime.verifyShell() else runtime.setup()
        delay(500)
        while (runtime.busy.value) { update { it.copy(status = runtime.status.value) }; delay(1000) }
        return if (runtime.shellHealthy.value) "Linux terminal installed; the execution check passed." else "Setup did not finish: ${runtime.status.value}"
    }

    // ---- Pickers -----------------------------------------------------------------------------

    fun uiRequestHandled() = update { it.copy(uiRequest = null) }

    fun folderPicked(uri: Uri?) {
        uiRequestHandled()
        val result = if (uri == null) "The user closed the folder picker; no access was granted." else runCatching {
            val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
            update { s -> s.copy(grantedFolders = context.contentResolver.persistedUriPermissions.map { it.uri.toString() }) }
            "Folder access granted and kept across restarts: ${Uri.decode(uri.lastPathSegment ?: uri.toString())}. Open Mine can now import and export files there."
        }.getOrElse { "Android did not allow keeping access to that folder: ${it.message}" }
        post("result", result)
        scope.launch { resumeAfterExternal(result) }
    }

    fun modelFilePicked(uri: Uri?) {
        uiRequestHandled()
        if (uri == null) { post("result", "No file was chosen."); scope.launch { resumeAfterExternal("The user closed the file picker; nothing was imported.") }; return }
        scope.launch {
            val candidate = SetupProbe.inspect(context, uri)
            val memory = android.app.ActivityManager.MemoryInfo().also { (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(it) }.totalMem
            val d = com.openmine.ModelImportDoctor.diagnose(candidate, context.filesDir.usableSpace, memory)
            val result = if (d.canImport) { runtime.importModel(uri); "${d.summary} Import started; it is copied into Open Mine and your original is unchanged." }
                else "${d.problem.label}: ${d.summary}\n" + d.steps.joinToString("\n") { "• $it" }
            post("result", result)
            resumeAfterExternal(result)
        }
    }

    private suspend fun resumeAfterExternal(result: String) {
        val action = externalCall ?: return
        externalCall = null
        resumeAfter(action, result)
    }

    // ---- LFM3 lifecycle -----------------------------------------------------------------------

    fun startLfmDownload() {
        if (downloadJob?.isActive == true || _state.value.lfmInstalled) { if (_state.value.lfmInstalled) startLfm(); return }
        downloadJob = scope.launch {
            update { it.copy(downloadProgress = 0f, lfmNote = "Downloading ${BuiltInModels.LFM3.displayName}…") }
            try {
                BuiltInModels.download(context.filesDir, BuiltInModels.LFM3, { got, total -> update { it.copy(downloadProgress = got.toFloat() / total) } }) { ensureActive() }
                update { it.copy(lfmInstalled = true, downloadProgress = null, lfmNote = "Verified by SHA-256.") }
                post("system", "${BuiltInModels.LFM3.displayName} downloaded and verified.")
                startLfm()
            } catch (e: CancellationException) { update { it.copy(downloadProgress = null, lfmNote = "Download paused; progress is kept.") }; throw e }
            catch (e: Exception) { update { it.copy(downloadProgress = null, lfmNote = e.message.orEmpty()) }; post("system", "Download failed: ${e.message}") }
        }
    }

    fun cancelDownload() { downloadJob?.cancel() }

    fun startLfm() {
        if (!_state.value.lfmInstalled || server.state.value == LocalLlmServer.State.STARTING || server.state.value == LocalLlmServer.State.READY) return
        scope.launch {
            update { it.copy(lfmNote = "Loading LFM3 into memory…") }
            val ok = server.start(BuiltInModels.file(context.filesDir, BuiltInModels.LFM3), LocalLlmServer.defaultThreads(), LocalLlmServer.installKey(context.noBackupFilesDir))
            update { it.copy(lfmNote = if (ok) "LFM3 ready on this phone (offline)." else server.lastError) }
            if (ok && _state.value.messages.none { it.text.startsWith("LFM3 is ready") }) post("assistant", "LFM3 is ready. Ask me anything about setting up Open Mine, or about your imported knowledge.")
        }
    }

    fun stopLfm() { scope.launch { server.stop() } }

    // ---- Tutorial and autonomy ----------------------------------------------------------------

    fun finishTutorial(mode: AutonomyMode) {
        prefs.edit().putBoolean("tutorial_complete", true).putString("autonomy", mode.name).apply()
        update { it.copy(tutorialComplete = true, autonomy = mode) }
        post("system", if (mode == AutonomyMode.FULL_AUTONOMY) "Full autonomy is on: commands and downloads run without asking. Destructive commands and memory changes still ask. Change this anytime in the guide."
            else "I'll ask before every action. Change this anytime in the guide.")
    }

    fun setAutonomy(mode: AutonomyMode) { prefs.edit().putString("autonomy", mode.name).apply(); update { it.copy(autonomy = mode) } }

    fun clearConversation() { file.delete(); suspendedTurn = null; update { it.copy(messages = emptyList(), pending = null) }; post("assistant", WELCOME) }

    // ---- Knowledge -----------------------------------------------------------------------------

    private fun knowledgeIndex(): KnowledgeIndex {
        val revision = OpenMineObjectStore.revision.value.toLong() * 31 + (File(context.filesDir, "ai_memory").listFiles()?.size ?: 0) * 7 +
            (File(context.filesDir, "knowledge_documents").listFiles()?.size ?: 0)
        knowledge?.takeIf { it.first == revision }?.let { return it.second }
        val index = KnowledgeIndex(KnowledgeSources.collect(context))
        knowledge = revision to index
        return index
    }

    companion object {
        const val MAX_ROUNDS = 6
        const val WELCOME = "Hi, I'm Open Mine's built-in guide. I run on this phone. Needle 3 is included for quick setup actions; LFM3 adds full offline conversation after a one-time download.\n\nUse the steps above, or tell me what you want to set up. I'll always show you what I'm about to do first."
        private const val NEEDLE_SYSTEM = "You are the setup assistant inside the Open Mine Android app. Pick the single tool that matches what the user asked."
        @Volatile private var instance: GuideAgent? = null
        fun get(c: Context) = instance ?: synchronized(this) { instance ?: GuideAgent(c.applicationContext).also { instance = it } }
    }
}
