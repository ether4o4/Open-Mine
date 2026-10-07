package com.openmine

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

private data class LibraryDraftState(val session: LibrarySession = LibrarySession(), val loaded: Boolean = false, val saving: Boolean = false, val error: String = "")

/** Process-owned writes survive composition disposal and rotation; raw text never enters a Bundle. */
private class LibraryDraftController(private val store: LibrarySessionStore) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = Channel<Pair<Long, LibrarySession>>(Channel.CONFLATED)
    private val mutable = MutableStateFlow(LibraryDraftState())
    val state: StateFlow<LibraryDraftState> = mutable
    private var version = 0L
    private var loading = false
    init {
        reload()
        scope.launch {
            for ((revision, session) in pending) {
                val error = runCatching { store.save(session) }.exceptionOrNull()?.message.orEmpty()
                synchronized(this@LibraryDraftController) {
                    if (revision == version) mutable.value = mutable.value.copy(saving = false, error = error)
                }
            }
        }
    }
    fun reload() {
        synchronized(this) { if (loading || mutable.value.loaded) return; loading = true }
        scope.launch {
            try { val session = store.load(); synchronized(this@LibraryDraftController) { mutable.value = LibraryDraftState(session, loaded = true) } }
            catch (error: Exception) { mutable.value = mutable.value.copy(error = "Cannot restore library session: ${error.message}") }
            finally { synchronized(this@LibraryDraftController) { loading = false } }
        }
    }
    @Synchronized fun update(change: (LibrarySession) -> LibrarySession) {
        check(mutable.value.loaded) { "Library session has not loaded" }
        val session = change(mutable.value.session)
        version += 1
        mutable.value = mutable.value.copy(session = session, saving = true)
        check(pending.trySend(version to session).isSuccess) { "Cannot queue library draft" }
    }
    fun retrySave() { if (state.value.loaded) update { it } else reload() }
    suspend fun flush() {
        val saved = state.first { (it.loaded && !it.saving) || (!it.loaded && it.error.isNotBlank()) }
        check(saved.loaded && saved.error.isBlank()) { saved.error.ifBlank { "Library session was not saved" } }
    }
}

private object LibraryDrafts {
    private val controllers = ConcurrentHashMap<String, LibraryDraftController>()
    fun get(context: Context, category: String?): LibraryDraftController {
        val key = category?.lowercase(Locale.ROOT)?.takeIf { it.matches(Regex("[a-z]+")) } ?: "all"
        val file = File(context.filesDir, "library-sessions/$key.json")
        return controllers.getOrPut(file.absolutePath) { LibraryDraftController(LibrarySessionStore(file)) }
    }
}

/** Local CRUD and exact-token retrieval. Importing a procedure never authorizes its execution. */
@Composable
fun OperationalLibraryScreen(
    category: String? = null,
    initialRecordId: String? = null,
    contextIds: Set<String>,
    onToggleContext: (StrictObject) -> Unit,
    onOpenRecord: (StrictObject) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val controller = remember(category) { LibraryDrafts.get(context, category) }
    val draft by controller.state.collectAsState()
    val session = draft.session
    val revision by OpenMineObjectStore.revision.collectAsState()
    val scope = rememberCoroutineScope()
    val latestContextIds by rememberUpdatedState(contextIds)
    var snapshot by remember { mutableStateOf<KnowledgeStoreSnapshot?>(null) }
    var shown by remember { mutableStateOf<List<StrictObject>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var message by rememberSaveable { mutableStateOf("") }
    var actionError by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf("") }
    var operation by remember { mutableStateOf<Job?>(null) }
    var retry by remember { mutableStateOf<(() -> Unit)?>(null) }
    var deleteRecord by remember { mutableStateOf<StrictObject?>(null) }
    var discardDraft by rememberSaveable { mutableStateOf(false) }
    var validationErrors by remember { mutableStateOf<List<String>>(emptyList()) }
    val selected = snapshot?.records?.firstOrNull { it.id == session.selectedId && (category == null || it.type == category) }

    suspend fun finishCommit(committed: LibraryCommitRecovery): String = committed.finish {
        controller.retrySave()
        controller.flush()
    }
    fun runAction(label: String, action: suspend (LibraryCommitRecovery) -> String) {
        if (busy.isNotBlank()) return
        busy = label; actionError = ""; message = ""
        retry = { runAction(label, action) }
        operation = scope.launch {
            val committed = LibraryCommitRecovery()
            try { message = action(committed); retry = null; refresh += 1 }
            catch (cancelled: CancellationException) {
                if (committed.committedMessage != null) {
                    try {
                        message = withContext(NonCancellable) { finishCommit(committed) }
                        retry = null
                    } catch (failure: Exception) {
                        actionError = "${committed.committedMessage} Workspace state still needs saving: ${failure.message}"
                        retry = { runAction("Recovering workspace state") { finishCommit(committed) } }
                    }
                } else message = "Operation interrupted before success was recorded. Reload the library before retrying; an interrupted export can leave a partial destination."
                refresh += 1
                throw cancelled
            }
            catch (error: Exception) {
                if (committed.committedMessage != null) {
                    actionError = "${committed.committedMessage} Workspace state still needs saving: ${error.message}"
                    // Only the post-commit workspace save is retried, never import/update/delete/export.
                    retry = { runAction("Recovering workspace state") { finishCommit(committed) } }
                    refresh += 1
                } else actionError = error.message ?: "Operation failed"
            }
            finally { busy = "" }
        }
    }
    fun leaveEditor() {
        runAction("Saving draft") { controller.update { it.copy(editorActive = false) }; controller.flush(); "Draft saved locally. Resume it from the library." }
    }
    fun back() {
        when {
            !draft.loaded -> onBack()
            busy.isNotBlank() -> { message = "Wait for the current operation or use Cancel operation." }
            session.creating -> controller.update { it.copy(creating = false) }
            session.editorActive -> leaveEditor()
            else -> runAction("Saving workspace") { controller.flush(); onBack(); "" }
        }
    }
    BackHandler(onBack = ::back)
    LaunchedEffect(draft.loaded, initialRecordId) {
        if (draft.loaded && initialRecordId != null) controller.update { it.copy(selectedId = initialRecordId) }
    }
    LaunchedEffect(category, revision, session.query, refresh, draft.loaded) {
        if (!draft.loaded) return@LaunchedEffect
        loading = true; loadError = ""
        try {
            if (session.query.isNotBlank()) delay(150)
            val result = withContext(Dispatchers.IO) {
                val inspected = OpenMineObjectStore.inspect(context)
                inspected to (if (session.query.isBlank()) inspected.records else OpenMineObjectStore.search(context, session.query))
                    .filter { category == null || it.type == category }
            }
            snapshot = result.first; shown = result.second
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { loadError = error.message ?: "Could not load library" }
        finally { loading = false }
    }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) { message = "Import cancelled." }
        else {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            runAction("Importing and indexing") { committed ->
                val raw = runInterruptible(Dispatchers.IO) { readLibraryImport(context, uri) }
                currentCoroutineContext().ensureActive()
                val checked = withContext(Dispatchers.IO) { OpenMineObjectFormat.validate(raw) }
                require(checked.valid) { checked.errors.joinToString("\n") }
                require(category == null || checked.normalized!!.type == category) { "This view accepts $category records. Use the all-record library to import ${checked.normalized!!.type}." }
                withContext(NonCancellable + Dispatchers.IO) {
                    val saved = OpenMineObjectStore.import(context, checked.normalized!!.raw)
                    require(saved.valid) { saved.errors.joinToString("\n") }
                    val imported = saved.normalized!!
                    committed.markCommitted("Imported and indexed ${imported.title}.") { controller.update { it.copy(selectedId = imported.id) } }
                }
                finishCommit(committed)
            }
        }
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri == null) { message = "Export cancelled." }
        else scope.launch {
            val loaded = controller.state.first { it.loaded || it.error.isNotBlank() }
            val id = loaded.session.exportId
            if (id == null) actionError = "No record was selected for export. Select a record and export again."
            else runAction("Exporting canonical record") { committed ->
                withContext(Dispatchers.IO) {
                    val record = OpenMineObjectStore.all(context).firstOrNull { it.id == id } ?: error("Record no longer exists")
                    try {
                        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(record.raw.toByteArray(Charsets.UTF_8)); it.flush() }
                            ?: error("Cannot open export destination")
                    } catch (error: Exception) { throw IllegalStateException("Export failed; the destination may contain a partial file. Retry or choose another destination. ${error.message}", error) }
                    committed.markCommitted("Canonical .omd record exported.") { controller.update { it.copy(exportId = null) } }
                }
                finishCommit(committed)
            }
        }
    }

    Column(Modifier.fillMaxSize().imePadding().testTag("operational-library")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(::back, Modifier.size(48.dp)) { Icon(Icons.Outlined.ArrowBack, "Back to workspace") }
            Column(Modifier.weight(1f)) {
                Text(if (session.editorActive) if (session.editId == null) "Create record" else "Edit record" else category?.let { "$it RECORDS" } ?: "OBJECT LIBRARY", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(if (draft.saving) "Saving workspace draft…" else if (draft.error.isBlank() && draft.loaded) "Local storage · offline available" else "Restoring workspace", fontSize = 12.sp)
            }
        }
        if (busy.isNotBlank()) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(busy, Modifier.weight(1f)); TextButton({ operation?.cancel() }) { Text("Cancel operation") }
            }
        }
        if (draft.error.isNotBlank() || actionError.isNotBlank() || message.isNotBlank()) {
            Column(Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState()).padding(12.dp)) {
                if (draft.error.isNotBlank()) { Text(draft.error, color = MaterialTheme.colorScheme.error); TextButton(controller::retrySave) { Text("Retry saving or restoring draft") } }
                if (actionError.isNotBlank()) { Text(actionError, color = MaterialTheme.colorScheme.error); retry?.let { TextButton(it, enabled = busy.isBlank()) { Text("Retry operation") } } }
                if (message.isNotBlank()) Text(message)
            }
        }
        if (!draft.loaded) {
            if (draft.error.isBlank()) CircularProgressIndicator(Modifier.padding(24.dp))
            TextButton(onBack, Modifier.heightIn(min = 48.dp)) { Text("Back without changing library data") }
        } else if (session.editorActive) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("library-editor-list"), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { Text("Edit the labeled V1 document. Use NONE for empty values. Saving validates every section and rebuilds retrieval. Procedures are stored as text and never run here.") }
                item { OutlinedTextField(session.rawDraft, { raw ->
                    if (raw.length <= OpenMineObjectFormat.MAX_RECORD_BYTES) { controller.update { it.copy(rawDraft = raw) }; validationErrors = emptyList(); actionError = ""; retry = null }
                    else actionError = "Draft exceeds the 1 MiB character limit. Existing draft was preserved."
                }, Modifier.fillMaxWidth().heightIn(min = 360.dp, max = 600.dp).testTag("library-raw-editor"), label = { Text("Canonical .omd document") }, enabled = busy.isBlank(), textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 14.sp)) }
                if (validationErrors.isNotEmpty()) item { Text(validationErrors.joinToString("\n"), color = MaterialTheme.colorScheme.error) }
                item { Button({
                    val raw = session.rawDraft; val editId = session.editId; val base = session.editBaseRaw
                    runAction("Validating and saving") { committed ->
                        val result = withContext(Dispatchers.IO) { OpenMineObjectFormat.validate(raw) }
                        if (!result.valid) { validationErrors = result.errors; error("Validation failed. Draft retained; correct the labeled fields below.") }
                        require(category == null || result.normalized!!.type == category) { "Record type must remain $category in this category." }
                        currentCoroutineContext().ensureActive()
                        withContext(NonCancellable + Dispatchers.IO) {
                            val saved = if (editId == null) OpenMineObjectStore.import(context, result.normalized!!.raw)
                            else {
                                val original = OpenMineObjectStore.all(context).firstOrNull { it.id == editId } ?: error("Record no longer exists; your draft is preserved.")
                                require(original.raw == base) { "This record changed after editing began. Your draft is preserved; compare it with the latest saved record before replacing it." }
                                val normalized = result.normalized!!
                                val sections = normalized.sections.toMutableMap()
                                sections["OPEN_MINE_OBJECT"] = normalized.fields + ("OBJECT_UPDATED" to OpenMineObjectFormat.now())
                                OpenMineObjectStore.update(context, editId, OpenMineObjectFormat.serialize(normalized.copy(sections = sections)))
                            }
                            require(saved.valid) { saved.errors.joinToString("\n") }
                            val record = saved.normalized!!
                            committed.markCommitted("Saved and indexed ${record.title}.") {
                                controller.update { it.copy(selectedId = record.id, editorActive = false, editId = null, rawDraft = "", editBaseRaw = "", creating = false, createTitle = "", createSummary = "") }
                            }
                        }
                        finishCommit(committed)
                    }
                }, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("library-save-record"), enabled = busy.isBlank()) { Text("VALIDATE + SAVE RECORD") } }
                item { OutlinedButton(::leaveEditor, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = busy.isBlank()) { Text("Keep draft and return to library") } }
                item { TextButton({ discardDraft = true }, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = busy.isBlank()) { Text("Discard this draft") } }
            }
        } else LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("library-list"), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ controller.update { it.copy(creating = true, createType = category ?: it.createType) } }, enabled = busy.isBlank() && session.rawDraft.isBlank(), modifier = Modifier.heightIn(min = 48.dp).testTag("library-create")) { Text("CREATE") }
                OutlinedButton({ importPicker.launch(arrayOf("text/*", "application/octet-stream")) }, enabled = busy.isBlank(), modifier = Modifier.heightIn(min = 48.dp)) { Text("IMPORT .OMD") }
                OutlinedButton({ runAction("Rebuilding retrieval index") { withContext(NonCancellable + Dispatchers.IO) { OpenMineObjectStore.rebuildIndex(context) }; "Retrieval index rebuilt from saved records." } }, enabled = busy.isBlank(), modifier = Modifier.heightIn(min = 48.dp)) { Text("REBUILD INDEX") }
            } }
            if (session.rawDraft.isNotBlank()) item { Surface(tonalElevation = 2.dp) { Column(Modifier.padding(12.dp)) {
                Text("Uncommitted ${if (session.editId == null) "new record" else session.editId} draft saved on this device.")
                Button({ controller.update { it.copy(editorActive = true) } }, Modifier.heightIn(min = 48.dp), enabled = busy.isBlank()) { Text("RESUME DRAFT") }
                TextButton({ discardDraft = true }, Modifier.heightIn(min = 48.dp), enabled = busy.isBlank()) { Text("Discard draft") }
            } } }
            item { OutlinedTextField(session.query, { value -> controller.update { it.copy(query = value.take(8192)) } }, Modifier.fillMaxWidth().testTag("library-search"), label = { Text("Search indexed terms") }, singleLine = true,
                trailingIcon = { if (session.query.isNotBlank()) IconButton({ controller.update { it.copy(query = "") } }) { Icon(Icons.Outlined.Close, "Clear search") } }) }
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (loadError.isNotBlank()) item { Text(loadError, color = MaterialTheme.colorScheme.error); OutlinedButton({ refresh += 1 }, Modifier.heightIn(min = 48.dp)) { Text("Retry loading library") } }
            snapshot?.let { state ->
                item { Text("${state.records.size} valid records · ${state.chunkCount} labeled chunks · ${if (state.indexReady) "Index verified" else "Index unavailable"}", fontSize = 13.sp) }
                if (state.issues.isNotEmpty()) item { Surface(color = MaterialTheme.colorScheme.errorContainer) { Column(Modifier.padding(12.dp)) {
                    Text("Some source files need attention. Their original bytes are preserved.", fontWeight = FontWeight.Bold)
                    state.issues.forEach { Text(it, Modifier.padding(top = 8.dp)) }
                } } }
            }
            if (!loading && loadError.isBlank() && shown.isEmpty()) item { Text(if (session.query.isNotBlank()) "No matching records. Try another exact term or clear the search." else "No records in this category. Create one or import a labeled UTF-8 .omd file up to 1 MiB. PDF, JSON and model weights are not supported by this import.") }
            items(shown, key = { it.id }) { record ->
                Card(Modifier.fillMaxWidth().clickable(role = Role.Button) { controller.update { it.copy(selectedId = if (it.selectedId == record.id) null else record.id) } }.testTag("library-record-${record.id}")) {
                    Column(Modifier.padding(12.dp)) {
                        Text(record.title, fontWeight = FontWeight.Bold); Text(record.id, fontSize = 12.sp)
                        Text("${record.type} · recorded state ${record.status}", fontSize = 12.sp)
                        Text(record.fields["OBJECT_SUMMARY"].orEmpty(), maxLines = 3)
                        if (record.id in contextIds) Text(if (record.type == "SKILL") "Skill active in assistant context" else "Selected for assistant context", color = MaterialTheme.colorScheme.primary)
                    }
                }
            if (selected?.id == record.id) {
                HorizontalDivider()
                Text(record.title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                SelectionContainer { Text(record.raw.take(16_384), Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState()).padding(vertical = 12.dp), fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                if (record.raw.length > 16_384) Text("Preview limited to 16,384 characters. Export includes the complete canonical record.", fontSize = 12.sp)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (record.type == "PROJECT" || record.type == "TOOL") Button({ onOpenRecord(record) }, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = busy.isBlank()) { Text(if (record.type == "PROJECT") "OPEN PROJECT WORKSPACE" else "REVIEW TOOL ACTIONS") }
                    OutlinedButton({ onToggleContext(record) }, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = busy.isBlank()) { Text(if (record.id in contextIds) if (record.type == "SKILL") "DEACTIVATE SKILL" else "REMOVE FROM CONTEXT" else if (record.type == "SKILL") "ACTIVATE SKILL" else "ADD TO CONTEXT") }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton({ controller.update { it.copy(editorActive = true, editId = record.id, rawDraft = record.raw, editBaseRaw = record.raw) }; validationErrors = emptyList() }, enabled = busy.isBlank() && session.rawDraft.isBlank(), modifier = Modifier.heightIn(min = 48.dp)) { Text("EDIT") }
                        OutlinedButton({ runAction("Preparing export") { controller.update { it.copy(exportId = record.id) }; controller.flush(); exportPicker.launch(record.id + ".omd"); "Choose a destination for the canonical record." } }, enabled = busy.isBlank(), modifier = Modifier.heightIn(min = 48.dp)) { Text("EXPORT .OMD") }
                        TextButton({ deleteRecord = record }, enabled = busy.isBlank(), modifier = Modifier.heightIn(min = 48.dp)) { Text("DELETE") }
                    }
                }
            } }
        }
    }
    if (session.creating && draft.loaded) AlertDialog(onDismissRequest = { controller.update { it.copy(creating = false) } }, title = { Text("New labeled record") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (category == null) OutlinedTextField(session.createType, { value -> controller.update { it.copy(createType = value.uppercase(Locale.ROOT).take(20)) } }, label = { Text("Type, such as KNOWLEDGE or TOOL") }, singleLine = true)
            else Text("Type: $category")
            OutlinedTextField(session.createTitle, { value -> controller.update { it.copy(createTitle = value.take(200)) } }, label = { Text("Title") }, modifier = Modifier.testTag("library-create-title"), singleLine = true)
            OutlinedTextField(session.createSummary, { value -> controller.update { it.copy(createSummary = value.take(8000)) } }, label = { Text("Summary") }, modifier = Modifier.heightIn(max = 200.dp))
            Text("Next opens the complete canonical document for review. It is not indexed until you validate and save it.", fontSize = 12.sp)
            if (actionError.isNotBlank()) Text(actionError, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton({
        runAction("Preparing draft") {
            require(session.createTitle.isNotBlank()) { "Enter a meaningful title." }
            val record = withContext(Dispatchers.Default) { libraryTemplate(category ?: session.createType, session.createTitle.trim(), session.createSummary.trim()) }
            val checked = OpenMineObjectFormat.validate(record.raw)
            require(checked.valid) { checked.errors.joinToString("\n") }
            controller.update { it.copy(creating = false, editorActive = true, editId = null, rawDraft = checked.normalized!!.raw, editBaseRaw = "") }; controller.flush()
            "Draft prepared. Review the labeled fields and save when correct."
        }
    }, enabled = busy.isBlank() && session.createTitle.isNotBlank()) { Text("REVIEW DOCUMENT") } }, dismissButton = { TextButton({ controller.update { it.copy(creating = false) } }) { Text("Cancel") } })
    if (discardDraft) AlertDialog(onDismissRequest = { discardDraft = false }, title = { Text("Discard unsaved draft?") }, text = { Text("This removes the uncommitted editor text. Existing saved records remain unchanged.") },
        confirmButton = { TextButton({ controller.update { it.copy(rawDraft = "", editBaseRaw = "", editId = null, editorActive = false) }; validationErrors = emptyList(); discardDraft = false }) { Text("Discard draft") } }, dismissButton = { TextButton({ discardDraft = false }) { Text("Keep draft") } })
    deleteRecord?.let { record -> AlertDialog(onDismissRequest = { deleteRecord = null }, title = { Text("Delete ${record.title}?") },
        text = { Text("This deletes ${record.id} and removes its retrieval entries. Export it first if you need a copy. Project files, model weights and other records are preserved.") },
        confirmButton = { TextButton({ deleteRecord = null; runAction("Deleting record") { committed ->
            withContext(NonCancellable + Dispatchers.IO) {
                val current = OpenMineObjectStore.all(context).firstOrNull { it.id == record.id } ?: error("Record already removed")
                require(current.raw == record.raw) { "Record changed since you selected it. Reload and review before deleting." }
                OpenMineObjectStore.delete(context, current)
                committed.markCommitted("Deleted ${record.title}; retrieval index updated.") {
                    controller.update { it.copy(selectedId = null) }
                    if (record.id in latestContextIds) onToggleContext(record)
                }
            }
            finishCommit(committed)
        } }) { Text("Delete record") } }, dismissButton = { TextButton({ deleteRecord = null }) { Text("Cancel") } }) }
}

internal fun libraryTemplate(type: String, title: String, summary: String): StrictObject = OpenMineObjectFormat.template(
    type = type, title = title, summary = summary, tags = "NONE", source = "Local author",
    purpose = "NONE", facts = "NONE", procedure = "NONE", constraints = "NONE", examples = "NONE",
    keywords = title, aliases = "NONE", triggers = "NONE", project = "NONE", model = "NONE", skill = "NONE", tool = "NONE", mission = "NONE",
    query = title, whenText = "NONE", exclude = "NONE", verification = "DRAFT", verificationSource = "Local author", notes = "Not verified",
)

private fun readLibraryImport(context: Context, uri: Uri): String {
    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Import cancelled")
            val count = input.read(buffer); if (count < 0) break
            require(output.size() + count <= OpenMineObjectFormat.MAX_RECORD_BYTES) { "Import exceeds 1 MiB" }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    } ?: error("Cannot open the selected document")
    return Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
}
