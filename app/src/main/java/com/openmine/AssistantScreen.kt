package com.openmine

import android.app.Activity
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal data class AssistantUiState(
    val session:AssistantSession,
    val busy:Boolean=false,
    val phase:String="",
    val pendingReview:ModelToolProposal?=null,
    val apiKey:String="",
    val credentialLoading:Boolean=false,
    val storageError:String?=null,
    val loadBlocked:Boolean=false,
    val availableModels:List<String> = emptyList(),
    val connectionStatus:String="Not tested",
    val sourcePreview:List<StrictObject> = emptyList()
)

/** A process-owned request survives rotation. Background/navigation cancellation retains partial output. */
internal class AssistantSessionController(private val context:Context,private val credentialReader:((String)->String)?=null) {
    private val credentials=ProviderCredentials(context)
    private val store=AssistantSessionStore(File(context.filesDir,"assistant/sessions-v1.json"))
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val storageMutex=Mutex()
    @Volatile private var requestControl:ModelRequestControl?=null
    private var requestJob:Job?=null
    private var saveJob:Job?=null
    private var credentialLoadJob:Job?=null
    private var credentialRevision=0L
    @Volatile private var reviewGate:ReviewGate?=null
    @Volatile private var activeTurnId:String?=null
    private val _state=MutableStateFlow(load())
    private val checkpoint=RevisionedCheckpoint(_state.value.session,store::save)
    val state:StateFlow<AssistantUiState> = _state

    private fun readCredential(endpoint:String)=credentialReader?.invoke(endpoint) ?: credentials.load(endpoint)
    private fun load():AssistantUiState=try{
        val old=context.getSharedPreferences("assistant",Context.MODE_PRIVATE)
        val session=store.load() ?: AssistantSessionStore.migrateLegacy(old.getString("endpoint","").orEmpty(),old.getString("model","").orEmpty(),old.getString("last_answer","").orEmpty())
        AssistantUiState(session,apiKey=runCatching{readCredential(session.endpoint)}.getOrDefault(""))
    }catch(e:Exception){AssistantUiState(AssistantSession(),storageError="Cannot read saved assistant sessions: ${e.message}",loadBlocked=true)}

    @Synchronized private fun change(update:(AssistantUiState)->AssistantUiState){
        val previous=_state.value
        val next=update(previous)
        if(next.session!==previous.session)checkpoint.update(next.session)
        _state.value=next
    }
    private fun saveSoon(){
        // Throttle rather than debounce: a long stream is checkpointed even while tokens keep arriving.
        synchronized(this){
            if(saveJob!=null)return
            saveJob=scope.launch{
                delay(350)
                while(true){
                    val saved=persist()
                    // Change detection and worker retirement share the same lock as session edits.
                    // An edit after retirement starts a new worker; an edit during a write is flushed.
                    val finished=synchronized(this@AssistantSessionController){
                        if(!saved || !checkpoint.isDirty){saveJob=null;true}else false
                    }
                    if(finished)break
                    delay(350)
                }
            }
        }
    }
    private suspend fun persist():Boolean=storageMutex.withLock{
        if(_state.value.loadBlocked)return@withLock false
        try{checkpoint.flush();change{it.copy(storageError=null)};true}
        catch(e:Exception){change{it.copy(storageError="Session could not be saved: ${e.message}. Keep the app open and retry saving.")};false}
    }
    fun retryStorage(){scope.launch{if(_state.value.loadBlocked){change{load()}}else persist()}}
    @Synchronized fun setKey(key:String){
        if(_state.value.busy)return
        credentialRevision++;credentialLoadJob?.cancel()
        change{it.copy(apiKey=key,credentialLoading=false)}
    }
    fun saveKey() {
        val current=_state.value
        if(current.busy || current.loadBlocked || current.credentialLoading)return
        scope.launch {
            val message=try {
                credentials.save(current.session.endpoint,current.apiKey)
                if(current.apiKey.isBlank())"Stored credential removed"else "Credential saved securely for this endpoint; connection not tested"
            } catch(_:Exception){"Secure storage failed. Re-enter the key or retry; no key was exported."}
            change{if(it.session.endpoint==current.session.endpoint)it.copy(connectionStatus=message)else it}
        }
    }
    @Synchronized fun forgetKey() {
        val endpoint=_state.value.session.endpoint
        if(_state.value.busy || _state.value.loadBlocked)return
        val revision=++credentialRevision
        credentialLoadJob?.cancel()
        change{it.copy(credentialLoading=false)}
        scope.launch {
            runCatching { credentials.forget(endpoint) }
                .onSuccess { change{if(it.session.endpoint==endpoint && credentialRevision==revision)it.copy(apiKey="",connectionStatus="Stored credential removed")else it} }
                .onFailure { change{if(it.session.endpoint==endpoint)it.copy(connectionStatus="Could not remove stored credential; retry")else it} }
        }
    }
    @Synchronized fun testConnection() {
        if(_state.value.busy || _state.value.loadBlocked || _state.value.credentialLoading)return
        val current=_state.value
        val control=ModelRequestControl();requestControl=control
        change{it.copy(busy=true,phase="",connectionStatus="Requesting provider model list…")}
        scope.launch {
            try {
                val models=ModelApi.models(current.session.endpoint,current.apiKey,control)
                change{it.copy(availableModels=models,connectionStatus="API responded: ${models.size} models. Generation has not been tested.",
                    session=if(it.session.model.isBlank() && models.size==1)it.session.copy(model=models.single())else it.session)}
                saveSoon()
            } catch(e:Exception) { change{it.copy(connectionStatus=control.reason ?: connectionFailure(e))} }
            finally { control.close();requestControl=null;change{it.copy(busy=false)} }
        }
    }

    private fun connectionFailure(error:Exception):String=when(error){
        is ConnectException->if(_state.value.session.endpoint.trim().trimEnd('/')==ModelEndpoint.BUILT_IN)
            "The on-device model is not responding. Open Models, load your GGUF, then retry here."
            else "Cannot reach the model server. Check its address and that it is running, then retry."
        is UnknownHostException->"The server address could not be resolved. Check the address and network connection, then retry."
        is SocketTimeoutException->"The model server did not respond in time. Check that its model is loaded, then retry."
        else->error.message ?: "The model request failed. Open connection settings and retry."
    }
    fun sourceScope(projectId:String?, shared:Boolean, selected:Set<String>, excluded:Set<String> = _state.value.session.current.excludedIds) {
        if(_state.value.busy)return
        change{it.copy(session=it.session.updateCurrent{c->c.copy(projectId=projectId,sharedMemory=shared,sourceIds=selected,excludedIds=excluded)})}
        saveSoon();previewSources()
    }
    fun previewSources() {
        val session=_state.value.session
        val selectedScope=session.retrievalScope()
        scope.launch {
            try {
                val allRecords=OpenMineObjectStore.all(context)
                RetrievalPolicy.requireActiveProject(allRecords,selectedScope)
                val records=RetrievalPolicy.filter(allRecords,selectedScope)+DocumentKnowledge(context).sources(selectedScope,session.current.draft)+MemoryStore(context.filesDir).sources(selectedScope)
                change{if(it.session.retrievalScope()==selectedScope)it.copy(sourcePreview=records)else it}
            } catch(e:Exception) {
                change{if(it.session.retrievalScope()==selectedScope)it.copy(phase="Cannot read sources: ${e.message}",sourcePreview=emptyList())else it}
            }
        }
    }
    fun forgetConversation() {
        if(_state.value.busy)return
        val remaining=_state.value.session.conversations.filter{it.id!=_state.value.session.selectedId}.ifEmpty{listOf(AssistantConversation())}
        change{it.copy(session=it.session.copy(conversations=remaining,selectedId=remaining.first().id),sourcePreview=emptyList())};saveSoon()
    }
    fun setTools(enabled:Boolean){
        if(_state.value.busy || _state.value.loadBlocked)return
        change{it.copy(session=it.session.copy(toolsEnabled=enabled))};saveSoon()
    }
    @Synchronized fun configure(endpoint:String=_state.value.session.endpoint,model:String=_state.value.session.model){
        if(_state.value.busy || _state.value.loadBlocked)return
        val changed=endpoint!=_state.value.session.endpoint
        change{it.copy(session=it.session.copy(endpoint=endpoint,model=model),
            apiKey=if(changed)""else it.apiKey,credentialLoading=if(changed)endpoint.isNotBlank()else it.credentialLoading,
            availableModels=if(changed)emptyList()else it.availableModels,connectionStatus=if(changed)"Not tested"else it.connectionStatus,phase="")}
        if(changed){
            val revision=++credentialRevision
            credentialLoadJob?.cancel()
            if(endpoint.isNotBlank())credentialLoadJob=scope.launch{
                // Typing an endpoint must never wait for filesystem or Android Keystore IO.
                delay(250)
                val loaded=runCatching{readCredential(endpoint)}
                currentCoroutineContext().ensureActive()
                synchronized(this@AssistantSessionController){
                    if(revision==credentialRevision && _state.value.session.endpoint==endpoint){
                        change{it.copy(apiKey=loaded.getOrDefault(""),credentialLoading=false)}
                    }
                }
            }
        }
        saveSoon()
    }
    fun draft(text:String){
        if(_state.value.busy || _state.value.loadBlocked)return
        change{it.copy(session=it.session.updateCurrent{conversation->conversation.copy(draft=text.take(8000))})};saveSoon()
    }
    fun select(id:String){
        if(_state.value.busy || _state.value.loadBlocked)return
        change{state->if(state.session.conversations.any{it.id==id})state.copy(session=state.session.copy(selectedId=id),phase="")else state};saveSoon()
    }
    fun newConversation(){
        if(_state.value.busy || _state.value.loadBlocked)return
        val conversation=AssistantConversation()
        change{it.copy(session=it.session.copy(conversations=it.session.conversations+conversation,selectedId=conversation.id),phase="")};saveSoon()
    }
    private fun updateTurn(id:String,update:(AssistantTurn)->AssistantTurn){
        change{state->state.copy(session=state.session.copy(conversations=state.session.conversations.map{conversation->
            conversation.copy(turns=conversation.turns.map{if(it.id==id)update(it)else it})
        }))}
    }
    fun retry(turn:AssistantTurn,key:String){submit(key,turn.question)}
    @Synchronized fun submit(key:String,questionOverride:String?=null){
        val state=_state.value
        if(state.busy || state.loadBlocked || state.storageError!=null)return
        val session=state.session;val question=(questionOverride ?: session.current.draft).trim()
        val invalid=runCatching{ModelEndpoint.chatUri(session.endpoint);require(session.model.isNotBlank()){"Enter the installed server model ID."};require(question.isNotBlank()){"Enter a question."}}.exceptionOrNull()
        if(invalid!=null){change{it.copy(phase=invalid.message.orEmpty())};return}
        val control=ModelRequestControl();requestControl=control
        val turn=AssistantTurn(question=question,endpoint=session.endpoint,model=session.model)
        activeTurnId=turn.id
        change{it.copy(busy=true,phase="Saving request",session=it.session.updateCurrent{conversation->
            conversation.copy(draft=if(questionOverride==null)""else conversation.draft,title=if(conversation.turns.isEmpty())question.take(56)else conversation.title,turns=conversation.turns+turn)
        })}
        requestJob=scope.launch{
            try{
                check(persist()){"Request was not sent because session storage is unavailable."}
                val reply=ModelClient.ask(context,session.endpoint,session.model,key,question,control,
                    onProgress={phase->control.checkActive();change{it.copy(phase=phase)}},
                    onText={partial->control.checkActive();updateTurn(turn.id){it.copy(answer=partial)};saveSoon()},
                    history=session.completeHistory(),
                    reviewTool={proposal->
                        val gate=ReviewGate();reviewGate=gate
                        change{it.copy(pendingReview=proposal)}
                        try{gate.await(control)}finally{reviewGate=null;change{it.copy(pendingReview=null)}}
                    },
                    onToolResult={audit->updateTurn(turn.id){it.copy(toolAudit=it.toolAudit+audit)};saveSoon()},allowTools=session.toolsEnabled,retrievalScope=session.retrievalScope())
                control.checkActive()
                updateTurn(turn.id){control.checkActive();it.copy(answer=reply.text,state=if(reply.tokenLimited)AssistantTurnState.LIMITED else AssistantTurnState.COMPLETED,
                    detail=if(reply.tokenLimited)"Response reached the 1024-token limit. Ask a follow-up to continue."else "Server response finished.",sourceIds=reply.sources.map{source->source.id})}
                change{control.checkActive();it.copy(phase=if(reply.tokenLimited)"Response stopped at token limit"else "Response completed")}
            }catch(e:Exception){
                val cancelled=control.reason?.startsWith("Cancelled")==true
                val detail=control.reason ?: connectionFailure(e)
                updateTurn(turn.id){it.copy(state=if(cancelled)AssistantTurnState.CANCELLED else AssistantTurnState.FAILED,detail=detail)}
                change{it.copy(phase=detail)}
            }finally{
                control.close();reviewGate?.decide(false);reviewGate=null
                synchronized(this@AssistantSessionController){
                    requestControl=null;requestJob=null;activeTurnId=null
                    change{it.copy(busy=false,pendingReview=null)}
                }
                withContext(NonCancellable){persist()}
            }
        }
    }
    fun review(approve:Boolean){
        val gate=reviewGate ?: return
        val proposal=_state.value.pendingReview ?: return
        val turnId=activeTurnId ?: return
        change{it.copy(pendingReview=null,phase=if(approve)"Saving tool approval"else "Tool declined")}
        scope.launch{
            updateTurn(turnId){it.copy(toolAudit=it.toolAudit+"${if(approve)"User approved"else "User declined"} ${proposal.name} ${proposal.arguments}")}
            // Persist the decision before any approved action can begin.
            gate.decide(if(persist())approve else false)
        }
    }
    fun cancel(reason:String="Cancelled by user"){
        requestControl?.cancelRequest(reason);reviewGate?.decide(false)
        activeTurnId?.let{id->updateTurn(id){it.copy(state=AssistantTurnState.CANCELLED,detail=reason)};change{it.copy(phase="Cancelling request; partial text retained",pendingReview=null)}}
        scope.launch{persist()}
    }
    private class ReviewGate {
        private val decision=AtomicReference<Boolean?>(null)
        private val latch=CountDownLatch(1)
        fun decide(approved:Boolean){if(decision.compareAndSet(null,approved))latch.countDown()}
        fun await(control:ModelRequestControl):Boolean {
            while(!latch.await(150,TimeUnit.MILLISECONDS))control.checkActive()
            control.checkActive();return decision.get()==true
        }
    }
    companion object {
        @Volatile private var instance:AssistantSessionController?=null
        suspend fun pauseForRecovery() {
            val current=instance ?: return
            check(!current.state.value.busy){"Wait for chat to stop"}
            current.saveJob?.cancelAndJoin()
            current.persist()
            current.scope.cancel()
            instance=null
        }
        fun get(context:Context)=instance ?: synchronized(this){instance ?: AssistantSessionController(context.applicationContext).also{instance=it}}
    }
}

@Composable fun OperationalAssistantScreen(c:Context,onOpenModels:()->Unit = {}){
    val controller=remember{AssistantSessionController.get(c)}
    val state by controller.state.collectAsState()
    val session=state.session
    val key=state.apiKey
    var showConnections by rememberSaveable{mutableStateOf(session.endpoint.isBlank() || session.model.isBlank())}
    var showSources by rememberSaveable{mutableStateOf(false)}
    var showForget by rememberSaveable{mutableStateOf(false)}
    var sourceRecords by remember{mutableStateOf<List<StrictObject>>(emptyList())}
    var sourceError by remember{mutableStateOf<String?>(null)}
    LaunchedEffect(showSources,session.selectedId){
        if(showSources){
            sourceError=null
            try{
                sourceRecords=withContext(Dispatchers.IO){OpenMineObjectStore.all(c)}
                controller.previewSources()
            }catch(cancelled:CancellationException){throw cancelled}
            catch(error:Exception){sourceError="Could not read sources: ${error.message}";sourceRecords=emptyList()}
        }
    }
    var showSessions by rememberSaveable{mutableStateOf(false)}
    var copiedId by remember{mutableStateOf<String?>(null)}
    val clipboard=LocalClipboardManager.current
    val textColor=Color(0xFFEAF5FF)
    val muted=Color(0xFF91A9C8)
    AssistantRequestLifecycle(c,controller)
    BackHandler(state.pendingReview!=null){controller.review(false)}
    state.pendingReview?.let{proposal->
        AlertDialog(onDismissRequest={controller.review(false)},title={Text("Review model tool")},
            text={SelectionContainer{Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
                Text(proposal.name,fontWeight=FontWeight.Bold);Text(proposal.effect);Text("Arguments:\n${proposal.arguments}")
                Text("Destination: ${session.endpoint}\nOnly this one call will be approved.",fontSize=12.sp)
            }}},confirmButton={TextButton({controller.review(true)}){Text("Approve this call")}},dismissButton={TextButton({controller.review(false)}){Text("Decline")}})
    }
    if(showForget)AlertDialog(onDismissRequest={showForget=false},title={Text("Forget this conversation?")},
        text={Text("Removes its local messages and source selections. Source files remain in their own stores.")},
        confirmButton={TextButton({controller.forgetConversation();showForget=false}){Text("Forget")}},dismissButton={TextButton({showForget=false}){Text("Keep")}})
    if(showSources)AlertDialog(onDismissRequest={showSources=false},title={Text("Conversation sources")},text={
        Column(Modifier.verticalScroll(rememberScrollState())){
            Text("Only sources allowed here can be sent to ${session.endpoint.ifBlank{"your selected server"}}. Global HUD selection does not grant access. Imported instructions never authorize tools.")
            sourceError?.let{Text(it,color=MaterialTheme.colorScheme.error)}
            Text("Project: ${session.current.projectId ?: "none"}")
            TextButton({controller.sourceScope(null,session.current.sharedMemory,emptySet())}){Text("No project")}
            sourceRecords.filter{it.type=="PROJECT" && it.status!="ARCHIVED"}.forEach{project->TextButton({controller.sourceScope(project.id,session.current.sharedMemory,emptySet())}){Text(project.title)}}
            Row { Checkbox(session.current.sharedMemory,{controller.sourceScope(session.current.projectId,it,session.current.sourceIds)});Text("Opt in to shared memory") }
            sourceRecords.filter{RetrievalPolicy.allowed(it,session.retrievalScope().copy(selectedIds=session.current.sourceIds+it.id))}.forEach{record->
                val selected=record.id in session.current.sourceIds
                Row { Checkbox(selected,{checked->controller.sourceScope(session.current.projectId,session.current.sharedMemory,if(checked)session.current.sourceIds+record.id else session.current.sourceIds-record.id)});Text(record.title) }
            }
            Text("Eligible sources sent when selected or matched:\n"+state.sourcePreview.joinToString("\n"){it.title+" ("+it.id+")"}.ifBlank{"None. Only this conversation and your question will be sent."})
            Text("Archived records and records with RETRIEVAL_EXCLUDE are excluded. Edit or forget source records in Knowledge.")
        }
    },confirmButton={TextButton({showSources=false}){Text("Done")}})
    if(showSessions)AlertDialog(onDismissRequest={showSessions=false},title={Text("Saved conversations")},text={
        LazyColumn{items(session.conversations.asReversed(),key={it.id}){conversation->TextButton({controller.select(conversation.id);showSessions=false},enabled=!state.busy){Text(conversation.title)}}}
    },confirmButton={TextButton({showSessions=false}){Text("Close")}})
    LazyColumn(Modifier.fillMaxSize().imePadding().padding(16.dp).testTag("chat_screen"),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{
            Text("AI IN YOUR LIBRARY",color=textColor,fontSize=24.sp,fontWeight=FontWeight.Bold)
            Text("${session.current.title}\nDrafts, partial answers and conversation history are saved on this device.",color=muted,fontSize=14.sp)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                OutlinedButton({showSessions=true},enabled=!state.busy){Text("Sessions")}
                OutlinedButton({controller.newConversation()},enabled=!state.busy && !state.loadBlocked){Text("New conversation")}
            }
            TextButton({showSources=true},enabled=!state.busy){Text("Review sources sent to model")}
            TextButton({showForget=true},enabled=!state.busy){Text("Forget conversation")}
            TextButton({showConnections=!showConnections},enabled=!state.busy,modifier=Modifier.testTag("chat_settings")){Text(if(showConnections)"Hide connection settings"else "Connection: ${session.model.ifBlank{"configure server"}}")}
        }
        if(showConnections){
            item{ Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                TextButton({controller.saveKey()},enabled=!state.busy && !state.credentialLoading && session.endpoint.isNotBlank()){Text("Save key securely")}
                TextButton({controller.forgetKey()},enabled=!state.busy && session.endpoint.isNotBlank()){Text("Forget key")}
            } }
            item{ OutlinedButton({controller.testConnection()},enabled=!state.busy && !state.credentialLoading && session.endpoint.isNotBlank()){Text("Test connection / list models")}
                Text(state.connectionStatus,color=muted,modifier=Modifier.semantics{liveRegion=LiveRegionMode.Polite})
                state.availableModels.forEach{model->TextButton({controller.configure(model=model)},enabled=!state.busy){Text((if(session.model==model)"✓ "else "")+model)}}
            }
            item{Text("Connect a running built-in GGUF, Ollama, or HTTPS OpenAI-compatible server. Questions, the recent conversation and source context go to this server. Each model-requested tool needs your review. Save keys explicitly using Android secure storage. Keys are excluded from backups and exports.",color=muted,fontSize=14.sp)}
            item{Column(verticalArrangement=Arrangement.spacedBy(6.dp)){
                OutlinedButton({controller.configure(endpoint=ModelEndpoint.BUILT_IN,model="local");controller.setKey("")},enabled=!state.busy,modifier=Modifier.fillMaxWidth()){Text("Built-in GGUF · 8080")}
                OutlinedButton({controller.configure(endpoint=ModelEndpoint.OLLAMA,model="");controller.setKey("")},enabled=!state.busy,modifier=Modifier.fillMaxWidth()){Text("Ollama · 11434")}
                TextButton(onOpenModels,enabled=!state.busy){Text("Open model setup")}
                Text("Built-in ID: local. For Ollama, enter your installed tag from ollama list. A preset does not start or install a server.",color=muted,fontSize=12.sp)
            }}
            item{OutlinedTextField(session.endpoint,{controller.configure(endpoint=it)},Modifier.fillMaxWidth(),enabled=!state.busy && !state.loadBlocked,label={Text("API base URL")},supportingText={Text("HTTPS or phone loopback · use /v1")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri,autoCorrectEnabled=false),singleLine=true)}
            item{OutlinedTextField(session.model,{controller.configure(model=it)},Modifier.fillMaxWidth(),enabled=!state.busy && !state.loadBlocked,label={Text("Installed server model ID")},singleLine=true)}
            item{OutlinedTextField(key,{controller.setKey(it)},Modifier.fillMaxWidth(),enabled=!state.busy,label={Text("API key")},supportingText={if(state.credentialLoading)Text("Checking saved credential…")},singleLine=true,visualTransformation=PasswordVisualTransformation())}
            item{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                Text("Allow model tool proposals\nEach call still requires review. Turn off for models without tool support.",color=muted,modifier=Modifier.weight(1f),fontSize=14.sp)
                Switch(session.toolsEnabled,{controller.setTools(it)},enabled=!state.busy && !state.loadBlocked,modifier=Modifier.semantics{contentDescription="Allow model tool proposals; each call requires review"})
            }}
        }
        state.storageError?.let{error->item{Text(error,color=MaterialTheme.colorScheme.error);OutlinedButton({controller.retryStorage()}){Text("Retry session storage")}}}
        if(session.current.turns.isEmpty())item{Text("No messages in this conversation. Review and select sources for this conversation, then ask a question. You can use a local model without internet once its runtime and weights are installed.",color=muted)}
        items(session.current.turns,key={it.id}){turn->
            Surface(color=Color(0xE60A1830),shape=MaterialTheme.shapes.medium){Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                Text(turn.question,color=textColor,fontWeight=FontWeight.Bold)
                Text("${turn.model} · ${turn.state.name.lowercase().replaceFirstChar{it.uppercase()}}",color=Color(0xFF38D8FF),fontSize=12.sp)
                SelectionContainer{Text(turn.answer.ifBlank{if(turn.state==AssistantTurnState.STREAMING)"Waiting for the model…"else "No response text received."},color=textColor,fontSize=16.sp,lineHeight=24.sp)}
                if(turn.detail.isNotBlank())Text(turn.detail,color=muted,fontSize=12.sp)
                if(turn.sourceIds.isNotEmpty())Text("Source records: ${turn.sourceIds.joinToString()}",color=muted,fontSize=12.sp)
                if(turn.toolAudit.isNotEmpty())SelectionContainer{Text(turn.toolAudit.joinToString("\n\n"),color=muted,fontSize=12.sp)}
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    if(turn.answer.isNotBlank())TextButton({clipboard.setText(AnnotatedString(turn.answer));copiedId=turn.id}){Text(if(copiedId==turn.id)"Copied"else "Copy answer")}
                    if(turn.state in setOf(AssistantTurnState.FAILED,AssistantTurnState.CANCELLED,AssistantTurnState.INTERRUPTED))TextButton({if(turn.detail.contains("disable tool proposals",ignoreCase=true))controller.setTools(false);controller.retry(turn,key)},enabled=!state.busy && state.storageError==null){Text(if(turn.detail.contains("disable tool proposals",ignoreCase=true))"Retry without tools"else "Retry question")}
                    if(turn.state==AssistantTurnState.FAILED && turn.endpoint.trim().trimEnd('/')==ModelEndpoint.BUILT_IN)TextButton(onOpenModels,enabled=!state.busy){Text("Open model setup")}
                }
            }}
        }
        item{OutlinedTextField(session.current.draft,{controller.draft(it)},Modifier.fillMaxWidth().testTag("chat_composer"),enabled=!state.busy && !state.loadBlocked,label={Text("Ask your library")},supportingText={Text("${session.current.draft.length}/8000 · local draft")},minLines=2,maxLines=8)}
        item{Button({controller.submit(key)},enabled=!state.busy && !state.credentialLoading && !state.loadBlocked && state.storageError==null && session.current.draft.isNotBlank() && session.endpoint.isNotBlank() && session.model.isNotBlank(),modifier=Modifier.fillMaxWidth().testTag("chat_send")){Text(if(state.busy)"Request in progress"else "Ask model with library context")}}
        if(state.busy)item{LinearProgressIndicator(Modifier.fillMaxWidth());Text("180-second request budget, including tool review. Cancelling preserves partial output.",color=muted,fontSize=12.sp);OutlinedButton({controller.cancel()}){Text("Cancel request")}}
        if(state.phase.isNotBlank())item{Text(state.phase,color=Color(0xFF38D8FF),modifier=Modifier.semantics{liveRegion=LiveRegionMode.Polite})}
    }
}

/** The same lifecycle binding is used by the real screen and Android lifecycle verification. */
@Composable internal fun AssistantRequestLifecycle(c:Context,controller:AssistantSessionController){
    val owner=LocalLifecycleOwner.current
    DisposableEffect(owner,controller){
        val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_STOP && (c as? Activity)?.isChangingConfigurations!=true)controller.cancel("Cancelled when the app entered the background; retry to continue.")}
        owner.lifecycle.addObserver(observer)
        onDispose{owner.lifecycle.removeObserver(observer);if((c as? Activity)?.isChangingConfigurations!=true)controller.cancel("Cancelled when leaving AI Chat; retry to continue.")}
    }
}
