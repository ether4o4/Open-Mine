package com.openmine

import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Operates only on this PROJECT's local workspace; a record's prose is never executed. */
@Composable
fun ProjectWorkspace(record: StrictObject, onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    if (record.type != "PROJECT") {
        Column(Modifier.padding(16.dp)) { Text("This record is not a project."); TextButton(onBack) { Text("Back") } }
        return
    }
    val store = remember(record.id) { ProjectStore(context.filesDir, record.id) }
    val preferences = remember { context.getSharedPreferences("open_mine_projects", android.content.Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable(record.id) { mutableIntStateOf(preferences.getInt("${record.id}.tab", 0).coerceIn(0, 1)) }
    var tasks by remember { mutableStateOf<List<ProjectTask>>(emptyList()) }
    var files by remember { mutableStateOf<List<ProjectFile>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf("") }
    var operation by remember { mutableStateOf<Job?>(null) }
    var retry by remember { mutableStateOf<(() -> Unit)?>(null) }
    var newFile by rememberSaveable(record.id) { mutableStateOf(false) }
    var newName by rememberSaveable(record.id) { mutableStateOf("notes.txt") }
    var saveCopy by rememberSaveable(record.id) { mutableStateOf(false) }
    var importUri by rememberSaveable(record.id) { mutableStateOf<String?>(null) }
    var importName by rememberSaveable(record.id) { mutableStateOf("") }
    var exportName by rememberSaveable(record.id) { mutableStateOf<String?>(null) }
    var deleteFile by remember { mutableStateOf<ProjectFile?>(null) }
    var deleteTask by remember { mutableStateOf<ProjectTask?>(null) }
    var taskEditor by rememberSaveable(record.id) { mutableStateOf(false) }
    var taskId by rememberSaveable(record.id) { mutableStateOf<String?>(null) }
    var taskTitle by rememberSaveable(record.id) { mutableStateOf("") }
    var taskNotes by rememberSaveable(record.id) { mutableStateOf("") }
    var taskStatus by rememberSaveable(record.id) { mutableStateOf(ProjectTaskStatus.TODO.name) }
    var editorName by rememberSaveable(record.id) { mutableStateOf<String?>(null) }
    var editorText by remember { mutableStateOf("") }
    var editorModified by remember { mutableLongStateOf(0) }
    var editorDirty by remember { mutableStateOf(false) }
    var editorLoaded by remember { mutableStateOf(false) }
    var discardDraft by remember { mutableStateOf(false) }
    var draftWriteJob by remember { mutableStateOf<Job?>(null) }

    suspend fun refresh() {
        val snapshot = runInterruptible(Dispatchers.IO) { store.tasks() to store.listFiles() }
        tasks = snapshot.first
        files = snapshot.second
    }
    fun runAction(label: String, action: suspend () -> String) {
        if (busy.isNotEmpty()) return
        busy = label
        error = ""
        operation = scope.launch {
            retry = { runAction(label, action) }
            try {
                status = action()
                retry = null
                try { refresh() } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    error = "The operation finished, but the workspace could not refresh: ${failure.message}"
                    retry = { runAction("Refreshing workspace") { refresh(); "Workspace refreshed" } }
                }
            } catch (cancelled: CancellationException) {
                status = "Operation cancelled. Changes already saved remain in the workspace."
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "Operation failed"
            } finally { busy = "" }
        }
    }
    fun closeEditor() {
        val name = editorName ?: return
        runAction("Preserving draft") {
            draftWriteJob?.cancelAndJoin()
            if (editorLoaded && editorDirty) projectDraftIo { store.saveDraft(ProjectDraft(name, editorText, editorModified)) }
            editorName = null
            editorLoaded = false
            editorDirty = false
            "Draft preserved locally"
        }
    }
    BackHandler {
        when {
            busy.isNotEmpty() -> operation?.cancel()
            editorName != null -> closeEditor()
            else -> onBack()
        }
    }
    LaunchedEffect(record.id) { runAction("Opening workspace") { refresh(); "Workspace stored on this device · available offline" } }
    LaunchedEffect(editorName) {
        val name = editorName ?: return@LaunchedEffect
        editorLoaded = false
        snapshotFlow { busy }.first { it.isEmpty() }
        runAction("Opening file") {
            val opened = projectDraftIo {
                val draft = store.readDraft(name)
                val current = store.listFiles().firstOrNull { it.name == name } ?: error("File no longer exists")
                Triple(draft?.text ?: store.readText(name), draft?.originalModified ?: current.modified, draft != null)
            }
            editorText = opened.first
            editorModified = opened.second
            editorDirty = opened.third
            editorLoaded = true
            if (opened.third) "Recovered the unsaved local draft" else "UTF-8 text editor · drafts saved locally"
        }
    }
    // Short debounce limits disk churn; explicit Back/Save flushes before the screen closes.
    LaunchedEffect(editorName, editorText, editorDirty, editorLoaded) {
        val name = editorName ?: return@LaunchedEffect
        if (!editorLoaded || !editorDirty) return@LaunchedEffect
        draftWriteJob = coroutineContext.job
        delay(250)
        try { projectDraftIo { store.saveDraft(ProjectDraft(name, editorText, editorModified)) } }
        catch (failure: Exception) { if (failure is CancellationException) throw failure else error = "Draft not saved: ${failure.message}" }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latestDraft by rememberUpdatedState(if (editorLoaded && editorDirty && editorName != null) ProjectDraft(editorName!!, editorText, editorModified) else null)
    DisposableEffect(lifecycle, store) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) latestDraft?.let { draft ->
                // Outlives the UI scope so rotating or backgrounding does not cancel the final flush.
                ProjectDraftFlush.executor.execute {
                    val result = runCatching { store.saveDraft(draft) }
                    preferences.edit().apply {
                        if (result.isFailure) putString("${record.id}.draftError", result.exceptionOrNull()?.message ?: "Storage failure")
                        else remove("${record.id}.draftError")
                    }.commit()
                }
            }
            if (event == Lifecycle.Event.ON_START) preferences.getString("${record.id}.draftError", null)?.let {
                error = "Could not preserve the latest draft while backgrounded: $it"
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            snapshotFlow { busy }.first { it.isEmpty() }
            runAction("Reading selected file") {
            val suggested = runInterruptible(Dispatchers.IO) {
                val display = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: "imported-file"
                store.uniqueName(display)
            }
            importUri = uri.toString()
            importName = suggested
            saveCopy = false
            "Choose a project filename. Import limit: 16 MiB."
            }
        }
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val name = exportName
        if (uri != null && name != null) scope.launch {
            snapshotFlow { busy }.first { it.isEmpty() }
            runAction("Exporting file") {
            try {
                runInterruptible(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { store.exportFile(name, it) }
                        ?: error("Cannot write the selected destination")
                }
                exportName = null
                "Exported $name"
            } catch (failure: Exception) {
                // SAF created this new destination for this operation; remove a partial export if possible.
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) }
                }
                throw failure
            }
            }
        }
    }

    Column(Modifier.fillMaxSize().imePadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton({ if (editorName != null) closeEditor() else onBack() }, enabled = busy.isEmpty()) { Text("Back") }
            Column(Modifier.weight(1f)) {
                Text(record.title, style = MaterialTheme.typography.titleLarge)
                Text(record.id, style = MaterialTheme.typography.labelSmall)
            }
        }
        if (busy.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(24.dp)); Spacer(Modifier.width(8.dp)); Text(busy, Modifier.weight(1f))
            TextButton({ operation?.cancel() }) { Text("Cancel") }
        }
        if (error.isNotEmpty()) Column {
            Text(error, color = MaterialTheme.colorScheme.error)
            retry?.let { action -> TextButton(action, enabled = busy.isEmpty()) { Text("Retry") } }
        }
        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
        if (editorName != null) {
            Text(editorName.orEmpty(), style = MaterialTheme.typography.titleMedium)
            if (editorLoaded) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({
                        val name = editorName ?: return@Button
                        val text = editorText
                        runAction("Saving file") {
                            draftWriteJob?.cancelAndJoin()
                            val saved = projectDraftIo {
                                store.saveDraft(ProjectDraft(name, text, editorModified))
                                store.saveText(name, text, editorModified)
                            }
                            editorModified = saved.modified
                            editorDirty = false
                            "Saved $name"
                        }
                    }, enabled = busy.isEmpty() && editorDirty) { Text("Save") }
                    OutlinedButton({ discardDraft = true }, enabled = busy.isEmpty() && editorDirty) { Text("Discard draft") }
                    OutlinedButton({ saveCopy = true; newName = "copy-${editorName.orEmpty()}".take(120); newFile = true }, enabled = busy.isEmpty()) { Text("Save a copy") }
                }
                OutlinedTextField(editorText, { value ->
                    if (value.toByteArray(Charsets.UTF_8).size <= ProjectStore.MAX_TEXT_BYTES) { editorText = value; editorDirty = true }
                    else error = "Text editor limit: 1 MiB"
                }, Modifier.fillMaxWidth().weight(1f), label = { Text("File contents") }, enabled = busy.isEmpty(), textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace))
            } else {
                Text("Select Retry if this file could not be opened. Binary files can be exported from the file list.")
                if (error.isNotEmpty()) OutlinedButton({ discardDraft = true }, enabled = busy.isEmpty()) { Text("Open saved file without draft") }
            }
        } else {
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                listOf("Tasks (${tasks.count { it.status != ProjectTaskStatus.DONE }}/${tasks.size})", "Files (${files.size})").forEachIndexed { index, label ->
                    TextButton({ tab = index; preferences.edit().putInt("${record.id}.tab", index).apply() }) { Text(if (tab == index) "• $label" else label) }
                }
            }
            if (tab == 0) {
                Button({ taskId = null; taskTitle = ""; taskNotes = ""; taskStatus = ProjectTaskStatus.TODO.name; taskEditor = true }, enabled = busy.isEmpty()) { Text("Add task") }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (tasks.isEmpty() && busy.isEmpty()) item { Text("No tasks yet. Add a task to plan this project.") }
                    items(tasks, key = { it.id }) { task ->
                        OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(task.title, style = MaterialTheme.typography.titleMedium)
                            Text(task.status.displayLabel(), style = MaterialTheme.typography.labelMedium)
                            if (task.notes.isNotBlank()) SelectionContainer { Text(task.notes) }
                            Row(Modifier.horizontalScroll(rememberScrollState())) {
                                TextButton({ taskId = task.id; taskTitle = task.title; taskNotes = task.notes; taskStatus = task.status.name; taskEditor = true }, enabled = busy.isEmpty()) { Text("Edit") }
                                TextButton({ runAction("Updating task") { runInterruptible(Dispatchers.IO) { store.saveTask(task.id, task.title, task.notes, if (task.status == ProjectTaskStatus.DONE) ProjectTaskStatus.TODO else ProjectTaskStatus.DONE) }; "Task updated" } }, enabled = busy.isEmpty()) { Text(if (task.status == ProjectTaskStatus.DONE) "Reopen" else "Complete") }
                                TextButton({ deleteTask = task }, enabled = busy.isEmpty()) { Text("Delete") }
                            }
                        } }
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ saveCopy = false; newFile = true }, enabled = busy.isEmpty()) { Text("New text file") }
                    OutlinedButton({ importPicker.launch(arrayOf("*/*")) }, enabled = busy.isEmpty()) { Text("Import") }
                }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (files.isEmpty() && busy.isEmpty()) item { Text("No project files yet. Create text or import a file from your device.") }
                    items(files, key = { it.name }) { file ->
                        OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                            Text(file.name, style = MaterialTheme.typography.titleMedium)
                            Text("${file.bytes} bytes · stored offline", style = MaterialTheme.typography.bodySmall)
                            Row(Modifier.horizontalScroll(rememberScrollState())) {
                                TextButton({ editorName = file.name }, enabled = busy.isEmpty()) { Text("View / edit text") }
                                TextButton({ exportName = file.name; exportPicker.launch(file.name) }, enabled = busy.isEmpty()) { Text("Export") }
                                TextButton({ deleteFile = file }, enabled = busy.isEmpty()) { Text("Delete") }
                            }
                        } }
                    }
                }
            }
        }
    }
    if (taskEditor) AlertDialog(onDismissRequest = { if (busy.isEmpty()) taskEditor = false }, title = { Text(if (taskId == null) "Add task" else "Edit task") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(taskTitle, { if (it.length <= 240) taskTitle = it }, label = { Text("Title") }, enabled = busy.isEmpty())
            OutlinedTextField(taskNotes, { if (it.length <= 10000) taskNotes = it }, label = { Text("Notes") }, enabled = busy.isEmpty(), minLines = 3)
            ProjectTaskStatus.entries.forEach { value -> Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(taskStatus == value.name, { taskStatus = value.name }, enabled = busy.isEmpty()); Text(value.displayLabel())
            } }
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton({ runAction("Saving task") { runInterruptible(Dispatchers.IO) { store.saveTask(taskId, taskTitle, taskNotes, ProjectTaskStatus.valueOf(taskStatus)) }; taskEditor = false; "Task saved" } }, enabled = busy.isEmpty() && taskTitle.isNotBlank()) { Text("Save task") } }, dismissButton = { TextButton({ taskEditor = false }, enabled = busy.isEmpty()) { Text("Cancel") } })
    if (newFile || importUri != null) {
        val importing = importUri != null
        AlertDialog(onDismissRequest = { if (busy.isEmpty()) { newFile = false; importUri = null; saveCopy = false } }, title = { Text(if (importing) "Import file" else if (saveCopy) "Save draft as a new file" else "New text file") }, text = {
            Column { OutlinedTextField(if (importing) importName else newName, { if (importing) importName = it else newName = it }, label = { Text("Filename") }, singleLine = true, enabled = busy.isEmpty()); if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error) }
        }, confirmButton = { TextButton({
            val name = if (importing) importName else newName
            val selected = importUri
            runAction(if (importing) "Importing file" else "Creating file") {
                runInterruptible(Dispatchers.IO) {
                    if (selected == null) store.createText(name, if (saveCopy) editorText else "")
                    else context.contentResolver.openInputStream(android.net.Uri.parse(selected))?.use { store.importFile(name, it) } ?: error("Cannot read the selected file; select it again")
                }
                newFile = false
                importUri = null
                if (saveCopy) editorName = name
                saveCopy = false
                "$name saved to this project"
            }
        }, enabled = busy.isEmpty() && (!saveCopy || editorLoaded)) { Text(if (importing) "Import" else "Create") } }, dismissButton = { TextButton({ newFile = false; importUri = null; saveCopy = false }, enabled = busy.isEmpty()) { Text("Cancel") } })
    }
    deleteTask?.let { task -> ProjectDeleteDialog("Delete task “${task.title}”?", busy.isNotEmpty(), { deleteTask = null }) { runAction("Deleting task") { runInterruptible(Dispatchers.IO) { store.deleteTask(task.id) }; deleteTask = null; "Task deleted" } } }
    deleteFile?.let { file -> ProjectDeleteDialog("Delete “${file.name}” and its saved draft?", busy.isNotEmpty(), { deleteFile = null }) { runAction("Deleting file") { runInterruptible(Dispatchers.IO) { store.deleteFile(file.name) }; deleteFile = null; "File deleted" } } }
    if (discardDraft) ProjectDeleteDialog("Discard unsaved changes? The last saved file will be restored.", busy.isNotEmpty(), { discardDraft = false }) {
        val name = editorName ?: return@ProjectDeleteDialog
        runAction("Restoring saved file") {
            draftWriteJob?.cancelAndJoin()
            val saved = projectDraftIo { store.readText(name).also { store.deleteDraft(name) } to store.listFiles().first { it.name == name }.modified }
            editorText = saved.first
            editorModified = saved.second
            editorDirty = false
            editorLoaded = true
            discardDraft = false
            "Restored saved file"
        }
    }
}

private fun ProjectTaskStatus.displayLabel() = when (this) { ProjectTaskStatus.TODO -> "To do"; ProjectTaskStatus.IN_PROGRESS -> "In progress"; ProjectTaskStatus.DONE -> "Done" }

private object ProjectDraftFlush {
    val executor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "OpenMine-project-draft").apply { isDaemon = true } }
}

private suspend fun <T> projectDraftIo(action: () -> T): T = withContext(Dispatchers.IO) {
    try { ProjectDraftFlush.executor.submit(java.util.concurrent.Callable(action)).get() }
    catch (failure: java.util.concurrent.ExecutionException) { throw (failure.cause ?: failure) }
}

@Composable private fun ProjectDeleteDialog(message: String, busy: Boolean, dismiss: () -> Unit, confirm: () -> Unit) {
    AlertDialog(onDismissRequest = { if (!busy) dismiss() }, title = { Text("Confirm deletion") }, text = { Text(message) }, confirmButton = { TextButton(confirm, enabled = !busy) { Text("Delete") } }, dismissButton = { TextButton(dismiss, enabled = !busy) { Text("Cancel") } })
}
