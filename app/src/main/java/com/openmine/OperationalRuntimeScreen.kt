package com.openmine

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import org.json.JSONArray
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openmine.sandbox.OpenMineRuntime

private val RuntimeText=Color(0xFFEAF5FF)
private val RuntimeMuted=Color(0xFF91A9C8)
private val RuntimeCyan=Color(0xFF38D8FF)

@Composable fun OperationalRuntimeScreen(c:Context,terminalOnly:Boolean=false,onOpenChat:(()->Unit)?=null){
    val runtime=remember{OpenMineRuntime.get(c)}
    val preferences=remember{c.getSharedPreferences("open_mine_runtime_ui",Context.MODE_PRIVATE)}
    val status by runtime.status.collectAsState()
    val busy by runtime.busy.collectAsState()
    val shellHealthy by runtime.shellHealthy.collectAsState()
    val shellInstalled by runtime.shellInstalled.collectAsState()
    val engineInstalled by runtime.engineInstalled.collectAsState()
    val engineHealthy by runtime.engineHealthy.collectAsState()
    val modelHealthy by runtime.modelHealthy.collectAsState()
    val output by (if(terminalOnly)runtime.terminalOutput else runtime.output).collectAsState()
    val models by runtime.models.collectAsState()
    var previouslySeenModels by rememberSaveable{mutableStateOf(models.toTypedArray())}
    var command by remember{mutableStateOf(preferences.getString("command_draft","").orEmpty())}
    var selectedModel by remember{mutableStateOf(preferences.getString("selected_model","").orEmpty())}
    var followOutput by remember{mutableStateOf(preferences.getBoolean("follow_output",true))}
    val history = remember { ShellCommandHistory(runCatching {
        val saved = JSONArray(preferences.getString("command_history", "[]"))
        List(saved.length()) { saved.getString(it) }
    }.getOrDefault(emptyList())) }
    var historyPosition by remember { mutableIntStateOf(history.position) }
    fun editCommand(value: String) {
        command = value
        preferences.edit().putString("command_draft", value).apply()
    }
    fun recall(previous: Boolean) {
        editCommand(if (previous) history.previous(command) else history.next(command))
        historyPosition = history.position
    }
    var localMessage by remember{mutableStateOf("")}
    var deleteWeights by remember{mutableStateOf(false)}
    var huggingFaceImport by rememberSaveable { mutableStateOf(false) }
    fun browseHuggingFace() {
        runCatching { c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://huggingface.co/models"))) }
            .onSuccess { huggingFaceImport = true; localMessage = "" }
            .onFailure { localMessage = "Could not open a browser. Open https://huggingface.co/models to download weights." }
    }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null){localMessage="";runtime.importModel(uri)}
    }
    if (huggingFaceImport) AlertDialog(onDismissRequest = { huggingFaceImport = false },
        title = { Text("Import from Hugging Face") },
        text = { Text("Choose a model on Hugging Face and download its GGUF weights from Files and versions. Then select the downloaded file here. You can browse any model; local loading requires a compatible GGUF that fits your device.") },
        confirmButton = { TextButton({ huggingFaceImport = false; picker.launch(arrayOf("application/octet-stream", "*/*")) }, enabled = !busy) { Text("Choose downloaded file") } },
        dismissButton = { TextButton({ huggingFaceImport = false }) { Text("Cancel") } })
    LaunchedEffect(models){
        val refreshed=ModelImportSelection.afterRefresh(previouslySeenModels.toList(),models,selectedModel)
        previouslySeenModels=models.toTypedArray()
        if(refreshed!=selectedModel){
            selectedModel=refreshed;preferences.edit().putString("selected_model",selectedModel).apply()
        }
    }
    if(deleteWeights)AlertDialog(onDismissRequest={deleteWeights=false},title={Text("Delete imported weights?")},text={Text("Deletes $selectedModel from Open Mine. Your original phone file is preserved.")},
        confirmButton={TextButton({runtime.deleteModel(selectedModel);deleteWeights=false}){Text("Delete weights")}},dismissButton={TextButton({deleteWeights=false}){Text("Keep")}})
    fun submitCommand() {
        if (runtime.busy.value || !runtime.ready || command.isBlank()) return
        runCatching { RuntimeCommandPolicy.validate(command) }.onSuccess { exact ->
            if (runtime.command(exact)) {
                history.record(exact)
                historyPosition = history.position
                preferences.edit().putString("command_history", JSONArray(history.entries).toString()).apply()
                editCommand("")
                localMessage = ""
            }
        }.onFailure { localMessage = it.message.orEmpty() }
    }
    LazyColumn(Modifier.fillMaxSize().imePadding().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{
            Text(if(terminalOnly)"LINUX SHELL"else "ON-DEVICE GGUF",color=RuntimeText,fontSize=24.sp,fontWeight=FontWeight.Bold)
            Text(if(terminalOnly)"Alpine Linux in Android PRoot, with app-private files and Android kernel limits; this is not root access to your phone. The Bash session and ordinary background jobs remain active between commands while the runtime runs. Cancel or timeout resets that shell; separately managed services continue. Completed cwd/output survive restart; exported variables are retained after cancellation."
                else "Import GGUF weights, install the engine, and start a local model server. AI Chat uses http://127.0.0.1:8080/v1 with model ID local after its health check passes.",color=RuntimeMuted,fontSize=14.sp)
        }
        item{RuntimeStatus(status,busy,shellHealthy,modelHealthy)}
        item{
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                OutlinedButton({localMessage="";runtime.verifyShell()},enabled=!busy){Text("Check shell")}
                if(BuildConfig.DEBUG)OutlinedButton({localMessage="";runtime.setup()},enabled=!busy && !shellInstalled){Text(if(shellInstalled)"Shell installed successfully"else "Download shell")}
            }
            if(shellInstalled){
                Text(if(shellHealthy)"Installed files retained · shell execution check passed"else "Installed files retained · shell execution check needed",color=RuntimeMuted,fontSize=12.sp)
                if(BuildConfig.DEBUG)TextButton({localMessage="";runtime.setup()},enabled=!busy){Text("Repair setup")}
            }
            if(BuildConfig.DEBUG)Text("Setup downloads Linux prerequisites. The runtime service keeps the operation active when you leave the app; cancelled or failed setup can be retried without deleting existing Linux files.",color=RuntimeMuted,fontSize=12.sp)
            else Text("Runtime downloads are disabled in this build. Shell execution requires already-installed, compatible prerequisites.",color=RuntimeMuted,fontSize=12.sp)
        }
        if(terminalOnly){
            item{
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton({ recall(true) }, enabled = !busy && historyPosition > 0,
                        modifier = Modifier.testTag("shell-history-up").semantics { contentDescription = "Previous command" }) { Text("↑") }
                    TextButton({ recall(false) }, enabled = !busy && historyPosition < history.entries.size,
                        modifier = Modifier.testTag("shell-history-down").semantics { contentDescription = "Next command" }) { Text("↓") }
                }
                OutlinedTextField(command, { editCommand(it) }, Modifier.fillMaxWidth().testTag("shell-command").onPreviewKeyEvent { event ->
                    when {
                        event.key == Key.Enter && !event.isShiftPressed -> {
                            if (event.type == KeyEventType.KeyUp) submitCommand()
                            true
                        }
                        event.key == Key.DirectionUp && !event.isShiftPressed -> {
                            if (event.type == KeyEventType.KeyDown && !busy) recall(true)
                            true
                        }
                        event.key == Key.DirectionDown && !event.isShiftPressed -> {
                            if (event.type == KeyEventType.KeyDown && !busy) recall(false)
                            true
                        }
                        else -> false
                    }
                }, enabled=!busy, label={Text("Shell command")},
                    supportingText={Text("${command.toByteArray(Charsets.UTF_8).size}/8192 bytes · Enter to run")}, minLines=2,maxLines=8,
                    keyboardOptions=KeyboardOptions(capitalization=KeyboardCapitalization.None,autoCorrectEnabled=false,imeAction=ImeAction.Send),
                    keyboardActions=KeyboardActions(onSend={submitCommand()}))
                Text("Install packages with apk add. Example: apk add --no-cache git",color=RuntimeMuted,fontSize=12.sp)
                if(!shellHealthy)Text("Check or set up the shell before running commands.",color=RuntimeMuted,fontSize=12.sp)
            }
            item{Button({ submitCommand() },enabled=!busy && shellHealthy && command.isNotBlank(),modifier=Modifier.fillMaxWidth()){Text("Run command")}}
        }else{
            if(BuildConfig.DEBUG)item{
                Button({localMessage="";runtime.engine("provision")},enabled=!busy && shellHealthy && !engineInstalled,modifier=Modifier.fillMaxWidth()){Text(if(engineInstalled)"Engine installed successfully"else "Install GGUF engine")}
                if(engineInstalled){
                    Text(if(engineHealthy)"Engine execution verified"else "Installed files retained · engine execution check needed",color=RuntimeMuted,fontSize=12.sp)
                    TextButton({localMessage="";runtime.engine("provision")},enabled=!busy && shellHealthy){Text("Check / repair engine")}
                }
                Text("ARM64 phones use the engine included in the app; setup downloads required Linux libraries. Other devices may build from source. Output and errors below come from the actual operation.",color=RuntimeMuted,fontSize=12.sp)
            }
            item { OutlinedButton({ browseHuggingFace() }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("import-hugging-face")) { Text("Import from Hugging Face") } }
            item{OutlinedButton({localMessage="";picker.launch(arrayOf("application/octet-stream","*/*"))},enabled=!busy,modifier=Modifier.fillMaxWidth()){Text("Import GGUF weights")}}
            if(models.isEmpty())item{Text("No model weights are imported. Choose a small quantized GGUF that fits this device's memory. Import checks the file header; loading verifies architecture and runtime compatibility.",color=RuntimeMuted,fontSize=14.sp)}
            items(models,key={it}){model->
                OutlinedButton({selectedModel=model;preferences.edit().putString("selected_model",model).apply()},enabled=!busy,modifier=Modifier.fillMaxWidth()){
                    Column(Modifier.fillMaxWidth()){
                        Text(if(model==selectedModel)"Selected weights"else "Select weights",color=if(model==selectedModel)RuntimeCyan else RuntimeMuted,fontSize=12.sp)
                        Text(model,color=RuntimeText,fontSize=13.sp)
                    }
                }
            }
            item{Button({localMessage="";runtime.engine("serve",selectedModel)},enabled=!busy && shellHealthy && selectedModel in models,modifier=Modifier.fillMaxWidth()){Text("Load selected model")}}
            if(modelHealthy && onOpenChat!=null)item{Button(onOpenChat,enabled=!busy,modifier=Modifier.fillMaxWidth()){Text("Open chat")}}
            item{Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                OutlinedButton({localMessage="";runtime.engine("status")},enabled=!busy && shellHealthy){Text("Check model health")}
                OutlinedButton({localMessage="";runtime.engine("stop")},enabled=!busy && shellHealthy){Text("Stop / unload model")}
            }}
        }
        if(!terminalOnly && selectedModel in models)item{OutlinedButton({deleteWeights=true},enabled=!busy && !modelHealthy){Text("Delete selected weights")}}
        if(busy)item{OutlinedButton({runtime.cancel();localMessage="Cancellation requested. Wait for the operation to stop before retrying."},modifier=Modifier.fillMaxWidth()){Text("Cancel operation")}}
        if(localMessage.isNotBlank())item{Text(localMessage,color=RuntimeCyan,modifier=Modifier.semantics{liveRegion=LiveRegionMode.Polite})}
        item{RuntimeOutput(output,followOutput){followOutput=it;preferences.edit().putBoolean("follow_output",it).apply()}}
    }
}

@Composable fun ReviewedToolScreen(record:StrictObject,onBack:()->Unit){
    val c=LocalContext.current
    val runtime=remember{OpenMineRuntime.get(c)}
    val status by runtime.status.collectAsState()
    val busy by runtime.busy.collectAsState()
    val healthy by runtime.shellHealthy.collectAsState()
    val output by runtime.terminalOutput.collectAsState()
    val command=remember(record.raw){runCatching{RuntimeCommandPolicy.validate(RecordActions.toolCommand(record))}}
    var review by remember(record.raw){mutableStateOf(false)}
    var message by remember{mutableStateOf("")}
    BackHandler{if(review)review=false else onBack()}
    if(review)CommandReviewDialog(command.getOrThrow(),onDismiss={review=false},onRun={
        review=false
        if(!runtime.busy.value && runtime.ready){runtime.command(command.getOrThrow());message="Command submitted. Inspect its exit code and output below."}
        else message="The shell state changed while you reviewed. Check the status and review again."
    })
    LazyColumn(Modifier.fillMaxSize().imePadding().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{TextButton(onBack){Text("Back to workspace")};Text(record.title,color=RuntimeText,fontSize=24.sp,fontWeight=FontWeight.Bold);Text(record.id,color=RuntimeMuted,fontSize=12.sp)}
        item{Text(record.fields["OBJECT_SUMMARY"].orEmpty(),color=RuntimeMuted)}
        item{Text("Imported tool records are data. Opening this screen does not run their procedure. Review the complete command before allowing it to execute.",color=RuntimeMuted,fontSize=14.sp)}
        item{Text(status,color=RuntimeCyan,modifier=Modifier.semantics{liveRegion=LiveRegionMode.Polite});if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())}
        item{SelectionContainer{Text(command.getOrNull() ?: command.exceptionOrNull()?.message.orEmpty(),color=if(command.isSuccess)RuntimeText else MaterialTheme.colorScheme.error,fontFamily=FontFamily.Monospace,fontSize=14.sp)}}
        item{Button({review=true},enabled=command.isSuccess && healthy && !busy,modifier=Modifier.fillMaxWidth()){Text("Review exact command")}}
        if(!healthy)item{Text("The Linux shell must pass its execution check before this tool can run.",color=RuntimeMuted);OutlinedButton({runtime.verifyShell()},enabled=!busy){Text("Check shell")};if(BuildConfig.DEBUG)OutlinedButton({runtime.setup()},enabled=!busy){Text("Set up / repair shell")}}
        if(busy)item{OutlinedButton({runtime.cancel();message="Cancellation requested."}){Text("Cancel operation")}}
        if(message.isNotBlank())item{Text(message,color=RuntimeCyan)}
        item{RuntimeOutput(output,true,null)}
    }
}

@Composable private fun RuntimeStatus(status:String,busy:Boolean,shellHealthy:Boolean,modelHealthy:Boolean){
    Column(verticalArrangement=Arrangement.spacedBy(6.dp)){
        Text(status,color=RuntimeCyan,modifier=Modifier.semantics{liveRegion=LiveRegionMode.Polite})
        Text("Shell: ${if(shellHealthy)"execution check passed"else "not currently verified"}\nModel: ${if(modelHealthy)"HTTP health check passed"else "not currently verified"}",color=RuntimeMuted,fontSize=12.sp)
        if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

@Composable private fun CommandReviewDialog(command:String,onDismiss:()->Unit,onRun:()->Unit){
    AlertDialog(onDismissRequest=onDismiss,title={Text("Run this exact command?")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
            Text("This runs in Open Mine's Linux environment for up to 30 seconds, with network access and access to its files. Commands can change or delete files. Only continue if you intend every action shown.")
            SelectionContainer{Text(command,fontFamily=FontFamily.Monospace,fontSize=14.sp)}
        }
    },confirmButton={TextButton(onRun){Text("Run reviewed command")}},dismissButton={TextButton(onDismiss){Text("Cancel")}})
}

@Composable private fun RuntimeOutput(output:String,follow:Boolean,onFollow:((Boolean)->Unit)?){
    val clipboard=LocalClipboardManager.current
    var copied by remember{mutableStateOf(false)}
    val scroll=rememberScrollState()
    LaunchedEffect(output,follow){copied=false;if(follow){withFrameNanos{};scroll.scrollTo(scroll.maxValue)}}
    Column(verticalArrangement=Arrangement.spacedBy(6.dp)){
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){
            Text("RETAINED OUTPUT",color=RuntimeMuted,fontSize=12.sp)
            TextButton({clipboard.setText(AnnotatedString(output));copied=true},enabled=output.isNotBlank()){Text(if(copied)"Copied"else "Copy output")}
        }
        if(onFollow!=null)Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
            Text("Follow latest output",color=RuntimeMuted,modifier=Modifier.weight(1f),fontSize=13.sp)
            Switch(follow,onFollow,modifier=Modifier.semantics{contentDescription="Follow latest output"})
        }
        Surface(color=Color(0xFF030A13),shape=MaterialTheme.shapes.small){
            SelectionContainer{Text(output.ifBlank{"No output has been recorded for this view."},color=RuntimeText,fontFamily=FontFamily.Monospace,fontSize=12.sp,lineHeight=17.sp,
                modifier=Modifier.fillMaxWidth().heightIn(min=120.dp,max=330.dp).verticalScroll(scroll).padding(10.dp))}
        }
        Text("Output is bounded to protect memory. Exit codes and errors come from the actual process.",color=RuntimeMuted,fontSize=12.sp)
    }
}
