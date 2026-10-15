package com.openmine

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.openmine.sandbox.OpenMineRuntime
import kotlinx.coroutines.*

@Composable fun WorkspaceRecoveryScreen() {
    val context=LocalContext.current
    val backup=remember{WorkspaceBackup(context.filesDir)}
    val scope=rememberCoroutineScope()
    var includeModels by remember{mutableStateOf(false)}
    var busy by remember{mutableStateOf(false)}
    var message by remember{mutableStateOf("")}
    var operation by remember{mutableStateOf<Job?>(null)}
    var preview by remember{mutableStateOf<WorkspaceBackup.Preview?>(null)}
    fun runWork(action:suspend ()->Unit) {
        if(busy)return
        val runtime=OpenMineRuntime.get(context)
        if(runtime.busy.value || runtime.modelHealthy.value || AssistantSessionController.get(context).state.value.busy) {
            message="Stop active shell/model/chat operations before backup or restore.";return
        }
        busy=true
        operation=scope.launch { try { AssistantSessionController.pauseForRecovery();pauseLibraryForRecovery();action() }
            catch(e:CancellationException){message="Cancelled. Existing workspace retained.";throw e}
            catch(e:Exception){message="Recovery failed: ${e.message}. Existing data retained; retry after correcting the error."}
            finally{busy=false} }
    }
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")){uri->
        if(uri!=null)runWork{
            try { runInterruptible(Dispatchers.IO){
                val prefs=org.json.JSONObject()
                for(name in listOf("open_mine","open_mine_projects","open_mine_browser","open_mine_runtime_ui")) {
                    val values=org.json.JSONObject()
                    context.getSharedPreferences(name,android.content.Context.MODE_PRIVATE).all.forEach{(key,value)->
                        if(safePreference(name,key))values.put(key,if(value is Set<*>)org.json.JSONArray(value.toList())else value)
                    }
                    prefs.put(name,values)
                }
                java.io.File(context.filesDir,"workspace_preferences").mkdirs()
                java.io.File(context.filesDir,"workspace_preferences/settings.json").writeText(prefs.toString())
                context.contentResolver.openOutputStream(uri,"wt")!!.use{out->backup.export(out,includeModels,
                check={if(Thread.currentThread().isInterrupted)throw java.io.InterruptedIOException("Cancelled")},progress={message="Backing up ${it/1024/1024} MiB"})}}
                message="Portable workspace backup complete. Credentials, runtime executables, indexes and phone storage excluded."
            }catch(e:Exception){withContext(NonCancellable+Dispatchers.IO){runCatching{android.provider.DocumentsContract.deleteDocument(context.contentResolver,uri)}};throw e}
        }
    }
    val restore=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null)runWork{
            preview=runInterruptible(Dispatchers.IO){context.contentResolver.openInputStream(uri)!!.use{backup.stage(it,
                check={if(Thread.currentThread().isInterrupted)throw java.io.InterruptedIOException("Cancelled")},progress={message="Validating ${it/1024/1024} MiB"})}}
            message="Backup validated. Review contents before restoring."
        }
    }
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("Portable workspace backup / restore",style=MaterialTheme.typography.titleMedium)
        Text("Includes conversations, object knowledge, project files/tasks/drafts, shell home files, explicit AI memory and ingested documents. Managed runtime packages and indexes are rebuilt. Phone originals, app credentials, shell keys and shell history are excluded. Review the manifest before sharing; your documents can contain private information.")
        Row { Checkbox(includeModels,{includeModels=it},enabled=!busy);Text("Include imported GGUF weights") }
        OutlinedButton({export.launch("open-mine-workspace.zip")},enabled=!busy){Text("Export whole workspace")}
        OutlinedButton({restore.launch(arrayOf("application/zip","application/octet-stream"))},enabled=!busy){Text("Validate backup for restore")}
        if(busy){LinearProgressIndicator(Modifier.fillMaxWidth());TextButton({operation?.cancel()}){Text("Cancel file operation")}}
        Text(message)
    }
    preview?.let{data->AlertDialog(onDismissRequest={if(!busy){preview=null;scope.launch(Dispatchers.IO){backup.discard()}}},title={Text("Restore validated workspace?")},
        text={Column(Modifier.verticalScroll(rememberScrollState())){
            Text("${data.items.size} files, ${data.totalBytes/1024} KiB. ${data.conflicts.size} matching paths will be replaced. Other files remain. A failed or interrupted commit rolls back on next start. Credentials must be entered again on a new device.")
            data.items.forEach{Text(it.path+(if(it.path in data.conflicts)" — replace existing"else " — add"))}
        }},confirmButton={TextButton({runWork{
            // Commit is deliberately non-cancellable after review; startup journal handles process death.
            withContext(NonCancellable+Dispatchers.IO){
                backup.commit(data,replaceConflicts=true)
                val file=java.io.File(context.filesDir,"workspace_preferences/settings.json")
                if(file.exists()) {
                    val prefs=org.json.JSONObject(file.readText())
                    for(name in listOf("open_mine","open_mine_projects","open_mine_browser","open_mine_runtime_ui")) {
                        val values=prefs.optJSONObject(name) ?: continue
                        val edit=context.getSharedPreferences(name,android.content.Context.MODE_PRIVATE).edit()
                        values.keys().forEach{key->if(safePreference(name,key))when(val value=values.get(key)){
                            is Boolean->edit.putBoolean(key,value)
                            is Int->edit.putInt(key,value)
                            is Long->edit.putLong(key,value)
                            is String->edit.putString(key,value)
                            is org.json.JSONArray->edit.putStringSet(key,(0 until value.length()).map{value.getString(it)}.toSet())
                        }}
                        check(edit.commit()){"Restored data but settings could not be applied; retry opening the app"}
                    }
                }
            }
            preview=null;message="Restore complete. Reopen Open Mine to reload restored conversations and rebuild indexes."
            context.getSharedPreferences("open_mine",android.content.Context.MODE_PRIVATE).edit().putString("route","").apply()
        }},enabled=!busy){Text(if(data.conflicts.isEmpty())"Restore"else "Replace reviewed conflicts")}},
        dismissButton={TextButton({preview=null;scope.launch(Dispatchers.IO){backup.discard()}},enabled=!busy){Text("Keep current workspace")}})
    }
}

private fun safePreference(name:String,key:String):Boolean = when(name) {
    "open_mine" -> key in setOf("screen","active","context","animations","haptics") || Regex("active_[0-9]+").matches(key)
    "open_mine_projects" -> key.endsWith(".tab")
    "open_mine_browser" -> key=="javascript"
    "open_mine_runtime_ui" -> key in setOf("selected_model","follow_output")
    else -> false
}
