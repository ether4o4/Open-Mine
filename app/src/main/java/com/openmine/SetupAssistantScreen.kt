package com.openmine

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openmine.sandbox.OpenMineRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private val SetupText=Color(0xFFEAF5FF)
private val SetupMuted=Color(0xFF91A9C8)
private val SetupCyan=Color(0xFF38D8FF)
private val SetupAmber=Color(0xFFFFC857)
private val SetupRed=Color(0xFFFF6B7A)
private val SetupGreen=Color(0xFF59E3A7)

/** Gathers real device and workspace facts for diagnostics. Safe to call off the main thread. */
object SetupProbe {
    fun snapshot(c:Context):SetupSnapshot {
        val runtime=OpenMineRuntime.get(c)
        val library=runCatching{OpenMineObjectStore.inspect(c)}
        val memory=ActivityManager.MemoryInfo().also{(c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it)}
        val session=AssistantSessionController.get(c).state.value.session
        val notifications=Build.VERSION.SDK_INT<33 || c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED
        return SetupSnapshot(BuildConfig.DEBUG,Build.SUPPORTED_ABIS.toList(),runtime.shellInstalled.value,runtime.shellHealthy.value,
            runtime.engineInstalled.value,runtime.engineHealthy.value,runtime.modelHealthy.value,runtime.models.value,
            runCatching{StatFs(c.filesDir.absolutePath).availableBytes}.getOrDefault(-1L),memory.totalMem,session.endpoint,session.model,
            library.getOrNull()?.issues ?: listOf(library.exceptionOrNull()?.message ?: "Library could not be read"),library.getOrNull()?.indexReady ?: false,
            notifications,runtime.journal.latestFailure(),runtime.busy.value)
    }

    /** Reads only what the SAF grant allows: display metadata and a bounded header prefix. Never writes. */
    fun inspect(c:Context,uri:Uri):ImportCandidate {
        var name:String?=null;var size:Long?=null
        runCatching{c.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE),null,null,null)?.use{cursor->
            if(cursor.moveToFirst()){
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf{it>=0 && !cursor.isNull(it)}?.let{name=cursor.getString(it)}
                cursor.getColumnIndex(OpenableColumns.SIZE).takeIf{it>=0 && !cursor.isNull(it)}?.let{size=cursor.getLong(it)}
            }
        }}
        val mime=runCatching{c.contentResolver.getType(uri)}.getOrNull()
        val header=runCatching{c.contentResolver.openInputStream(uri)?.use{input->
            val buffer=ByteArray(ModelImportDoctor.PROBE_BYTES);var read=0
            while(read<buffer.size){val n=input.read(buffer,read,buffer.size-read);if(n<0)break;read+=n}
            buffer.copyOf(read)
        }}
        return ImportCandidate(name ?: uri.lastPathSegment,size,mime,header.getOrNull(),
            header.exceptionOrNull()?.let{"${it.javaClass.simpleName}${it.message?.let{m->": $m"}.orEmpty()}"} ?: if(header.getOrNull()==null)"provider returned no stream" else null)
    }
}

/** Opens the system picker, preferably at Downloads, where browser model downloads land. */
private class OpenModelDocument:ActivityResultContracts.OpenDocument() {
    override fun createIntent(context:Context,input:Array<String>):Intent = super.createIntent(context,input).apply{
        addCategory(Intent.CATEGORY_OPENABLE)
        putExtra(DocumentsContract.EXTRA_INITIAL_URI,Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload"))
    }
}

@Composable fun SetupAssistantPanel(c:Context,onOpenModels:()->Unit,onOpenChat:()->Unit) {
    val runtime=remember{OpenMineRuntime.get(c)}
    val busy by runtime.busy.collectAsState()
    val status by runtime.status.collectAsState()
    val models by runtime.models.collectAsState()
    val operations by runtime.journal.records.collectAsState()
    val shellHealthy by runtime.shellHealthy.collectAsState()
    val modelHealthy by runtime.modelHealthy.collectAsState()
    val scope=rememberCoroutineScope()
    var findings by remember{mutableStateOf<List<Finding>>(emptyList())}
    var refresh by remember{mutableIntStateOf(0)}
    var message by remember{mutableStateOf("")}
    LaunchedEffect(refresh,busy,models,shellHealthy,modelHealthy,operations.size){
        findings=runCatching{withContext(Dispatchers.IO){SetupDiagnostics.evaluate(SetupProbe.snapshot(c))}}
            .getOrElse{listOf(Finding("probe",Severity.WARNING,"Diagnostics incomplete",it.message.orEmpty()))}
    }
    val notificationPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){refresh++}
    val doctor=remember{ModelFileDoctorState()}
    val picker=rememberLauncherForActivityResult(OpenModelDocument()){uri->
        if(uri!=null)scope.launch{
            doctor.reset();doctor.uri=uri;doctor.inspecting=true
            val candidate=withContext(Dispatchers.IO){SetupProbe.inspect(c,uri)}
            val free=runCatching{StatFs(c.filesDir.absolutePath).availableBytes}.getOrDefault(-1L)
            val memory=ActivityManager.MemoryInfo().also{(c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it)}.totalMem
            doctor.candidate=candidate;doctor.diagnosis=ModelImportDoctor.diagnose(candidate,free,memory);doctor.inspecting=false
        }
    }
    fun repair(action:RepairAction){
        message=""
        when(action){
            RepairAction.VERIFY_SHELL->runtime.verifyShell()
            RepairAction.SETUP_SHELL->runtime.setup()
            RepairAction.PROVISION_ENGINE->runtime.engine("provision")
            RepairAction.CHECK_MODEL_HEALTH->runtime.engine("status")
            RepairAction.IMPORT_MODEL,RepairAction.DIAGNOSE_FILE->picker.launch(arrayOf("application/octet-stream","*/*"))
            RepairAction.LOAD_MODEL->onOpenModels()
            RepairAction.CONFIGURE_ENDPOINT->onOpenChat()
            RepairAction.REBUILD_INDEX->scope.launch{
                message=runCatching{withContext(Dispatchers.IO){OpenMineObjectStore.rebuildIndex(c)};"Knowledge index rebuilt."}.getOrElse{"Index rebuild failed: ${it.message}"};refresh++
            }
            RepairAction.NOTIFICATION_PERMISSION->if(Build.VERSION.SDK_INT>=33)notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            RepairAction.RETRY_OPERATION->runtime.journal.latestFailure()?.let{if(!runtime.retry(it))message="This operation needs fresh input; use its repair action instead."}
        }
    }
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text("SETUP ASSISTANT",color=SetupText,fontSize=22.sp,fontWeight=FontWeight.Bold,modifier=Modifier.semantics{heading()})
        Text("Checks what Open Mine needs, explains each problem and offers the repair it can perform. Every repair is started by you; consequential changes ask first.",color=SetupMuted,fontSize=14.sp)
        Text(status,color=SetupCyan,fontSize=13.sp,modifier=Modifier.semantics{liveRegion=LiveRegionMode.Polite})
        if(busy){LinearProgressIndicator(Modifier.fillMaxWidth());OutlinedButton({runtime.cancel()}){Text("Cancel running operation")}}
        findings.forEach{finding->FindingCard(finding,!busy){repair(it)}}
        TextButton({refresh++},modifier=Modifier.testTag("setup-refresh")){Text("Run checks again")}
        if(message.isNotBlank())Text(message,color=SetupCyan,modifier=Modifier.semantics{liveRegion=LiveRegionMode.Polite})
        HorizontalDivider()
        ModelFileDoctor(doctor,busy,models,onPick={picker.launch(arrayOf("application/octet-stream","*/*"))},onImport={uri->runtime.importModel(uri);doctor.importRequested=true;doctor.knownModels=models})
        HorizontalDivider()
        OperationCenter(operations,busy,onRetry={record->if(!runtime.retry(record))message=if(record.retry==RetryAction.CHOOSE_MODEL_FILE)"Choose the file again with the model file doctor above." else "This operation cannot be retried automatically."},
            onClear={runtime.journal.clearFinished()})
        HorizontalDivider()
        ModelCapabilityPanel(c,modelHealthy)
    }
}

private class ModelFileDoctorState {
    var uri by mutableStateOf<Uri?>(null)
    var inspecting by mutableStateOf(false)
    var candidate by mutableStateOf<ImportCandidate?>(null)
    var diagnosis by mutableStateOf<ImportDiagnosis?>(null)
    var pasted by mutableStateOf("")
    var confirm by mutableStateOf(false)
    var importRequested by mutableStateOf(false)
    var knownModels by mutableStateOf<List<String>>(emptyList())
    fun reset(){uri=null;candidate=null;diagnosis=null;confirm=false;importRequested=false}
}

@Composable private fun FindingCard(finding:Finding,enabled:Boolean,onRepair:(RepairAction)->Unit){
    val color=when(finding.severity){Severity.BLOCKING->SetupRed;Severity.WARNING->SetupAmber;Severity.INFO->SetupCyan;Severity.OK->SetupGreen}
    Surface(color=Color(0xFF071224),shape=MaterialTheme.shapes.small,modifier=Modifier.fillMaxWidth().testTag("finding-${finding.id}")){
        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
            Text("${finding.severity.name} · ${finding.title}",color=color,fontWeight=FontWeight.Bold,fontSize=14.sp)
            Text(finding.detail,color=SetupMuted,fontSize=13.sp)
            finding.repair?.let{action->OutlinedButton({onRepair(action)},enabled=enabled){Text(action.label)}}
        }
    }
}

@Composable private fun ModelFileDoctor(state:ModelFileDoctorState,busy:Boolean,models:List<String>,onPick:()->Unit,onImport:(Uri)->Unit){
    Text("MODEL FILE DOCTOR",color=SetupText,fontWeight=FontWeight.Bold,fontSize=16.sp,modifier=Modifier.semantics{heading()})
    Text("If an import fails or Open Mine cannot find a file, choose it here. The doctor reads only its name, size and first megabyte through Android's picker, then explains whether the problem is access, location, format, filename, storage or compatibility.",color=SetupMuted,fontSize=13.sp)
    OutlinedTextField(state.pasted,{state.pasted=it},Modifier.fillMaxWidth().testTag("doctor-pasted"),label={Text("Optional: paste the filename or location from Files")},singleLine=true)
    if(state.pasted.isNotBlank())Text(ModelImportDoctor.interpretPastedLocation(state.pasted),color=SetupCyan,fontSize=13.sp)
    Button(onPick,enabled=!busy && !state.inspecting,modifier=Modifier.fillMaxWidth().testTag("doctor-pick")){Text(if(state.inspecting)"Inspecting…" else "Choose file to diagnose")}
    state.candidate?.let{c->Text("Selected: ${c.displayName ?: "unnamed"} · ${c.sizeBytes?.let{"${it/(1024*1024)} MiB"} ?: "size unknown"} · ${c.mimeType ?: "type unknown"}",color=SetupMuted,fontSize=12.sp)}
    state.diagnosis?.let{d->
        Surface(color=Color(0xFF071224),shape=MaterialTheme.shapes.small,modifier=Modifier.fillMaxWidth().testTag("doctor-result")){
            Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                Text(d.problem.label,color=if(d.canImport)SetupGreen else SetupAmber,fontWeight=FontWeight.Bold)
                SelectionContainer{Text(d.summary,color=SetupText,fontSize=13.sp)}
                d.warnings.forEach{Text("• $it",color=SetupAmber,fontSize=12.sp)}
                d.steps.forEachIndexed{i,step->Text("${i+1}. $step",color=SetupMuted,fontSize=13.sp)}
                if(d.canImport && !state.importRequested)Button({state.confirm=true},enabled=!busy){Text("Review import")}
            }
        }
    }
    if(state.importRequested){
        val added=models.filterNot{it in state.knownModels}
        Text(when{
            added.isNotEmpty()->"Verified: ${added.joinToString()} is now in Models. Load it there to test inference."
            busy->"Importing… progress is shown in the status line."
            else->"The import did not add a model. See the Operation center below for the exact error."
        },color=if(added.isNotEmpty())SetupGreen else SetupCyan,modifier=Modifier.semantics{liveRegion=LiveRegionMode.Polite})
    }
    if(state.confirm){
        val c=state.candidate
        AlertDialog(onDismissRequest={state.confirm=false},title={Text("Import this model?")},text={Text(
            "Open Mine will copy ${c?.displayName ?: "the selected file"}${c?.sizeBytes?.let{" (${it/(1024*1024)} MiB)"}.orEmpty()} into its private model folder and validate it again.\n\n" +
            "• Your original file is not modified or deleted.\n• Existing models with the same name are kept; the copy receives a unique name.\n• You can cancel while it copies.")},
            confirmButton={TextButton({state.confirm=false;state.uri?.let(onImport)}){Text("Import")}},
            dismissButton={TextButton({state.confirm=false}){Text("Cancel")}})
    }
}

@Composable private fun OperationCenter(records:List<OperationRecord>,busy:Boolean,onRetry:(OperationRecord)->Unit,onClear:()->Unit){
    Text("OPERATION CENTER",color=SetupText,fontWeight=FontWeight.Bold,fontSize=16.sp,modifier=Modifier.semantics{heading()})
    Text("Recent setup, model and terminal operations, kept across restarts. Operations interrupted by Android are marked so they can be retried.",color=SetupMuted,fontSize=13.sp)
    if(records.isEmpty())Text("No operations recorded yet.",color=SetupMuted,fontSize=13.sp)
    val format=remember{DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT)}
    records.take(15).forEach{r->
        val color=when(r.state){OperationState.SUCCEEDED->SetupGreen;OperationState.RUNNING->SetupCyan;OperationState.CANCELLED->SetupMuted;else->SetupAmber}
        Column(Modifier.fillMaxWidth().testTag("operation-${r.id}"),verticalArrangement=Arrangement.spacedBy(2.dp)){
            Text("${r.state.name.lowercase()} · ${r.label}",color=color,fontSize=13.sp,fontWeight=FontWeight.Medium)
            val seconds=r.endedAt?.let{(it-r.startedAt)/1000}
            Text("${r.component} · ${format.format(Date(r.startedAt))}${seconds?.let{" · ${it}s"}.orEmpty()}${if(r.state==OperationState.RUNNING)" · cancellable" else ""}",color=SetupMuted,fontSize=11.sp)
            if(r.detail.isNotBlank() && r.state!=OperationState.SUCCEEDED)SelectionContainer{Text(r.detail.take(400),color=SetupMuted,fontSize=12.sp)}
            if(r.retry!=null && (r.state==OperationState.FAILED || r.state==OperationState.INTERRUPTED || r.state==OperationState.CANCELLED))
                TextButton({onRetry(r)},enabled=!busy){Text(r.retry.label)}
        }
    }
    if(records.any{it.state!=OperationState.RUNNING})TextButton(onClear,enabled=!busy){Text("Clear finished operations")}
}

@Composable private fun ModelCapabilityPanel(c:Context,modelHealthy:Boolean){
    val session by AssistantSessionController.get(c).state.collectAsState()
    val endpoint=session.session.endpoint;val modelId=session.session.model
    val configured=buildList{
        if(endpoint.isNotBlank() && modelId.isNotBlank())add(ConfiguredModel(endpoint,modelId,ModelCatalog.profileForEndpoint(endpoint,modelId),
            endpoint!=ModelEndpoint.BUILT_IN || modelHealthy))
    }
    Text("MODEL CAPABILITIES",color=SetupText,fontWeight=FontWeight.Bold,fontSize=16.sp,modifier=Modifier.semantics{heading()})
    Text("Every model is reached through one OpenAI-compatible interface. A model's profile describes its roles, runtime and memory so tasks can go to the smallest suitable model.",color=SetupMuted,fontSize=13.sp)
    TaskKind.entries.forEach{task->
        val decision=ModelRouter.route(task,configured)
        Text("${task.label}: ${decision.model?.profile?.displayName ?: "unavailable"} — ${decision.reason}",color=if(decision.model!=null)SetupText else SetupMuted,fontSize=12.sp)
    }
    Text("Candidates under evaluation (none bundled; import weights yourself):",color=SetupMuted,fontSize=13.sp)
    ModelCatalog.candidates.forEach{p->
        Text("${p.displayName} · ${p.roles.joinToString{it.name.lowercase()}} · ${p.runtime.label} · ${p.verification.label}\n${p.notes}",
            color=if(p.runnable)SetupText else SetupMuted,fontSize=12.sp)
    }
}
