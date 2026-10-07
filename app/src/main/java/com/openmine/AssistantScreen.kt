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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
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

private data class AssistantUiState(
    val session:AssistantSession,
    val busy:Boolean=false,
    val phase:String="",
    val pendingReview:ModelToolProposal?=null,
    val apiKey:String="",
    val storageError:String?=null,
    val loadBlocked:Boolean=false
)

/** A process-owned request survives rotation. Background/navigation cancellation retains partial output. */
private class AssistantSessionController private constructor(private val context:Context) {
    private val store=AssistantSessionStore(File(context.filesDir,"assistant/sessions-v1.json"))
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val storageMutex=Mutex()
    @Volatile private var requestControl:ModelRequestControl?=null
    private var requestJob:Job?=null
    private var saveJob:Job?=null
    @Volatile private var reviewGate:ReviewGate?=null
    @Volatile private var activeTurnId:String?=null
    private val _state=MutableStateFlow(load())
    private val checkpoint=RevisionedCheckpoint(_state.value.session,store::save)
    val state:StateFlow<AssistantUiState> = _state

    private fun load():AssistantUiState=try{
        val old=context.getSharedPreferences("assistant",Context.MODE_PRIVATE)
        val session=store.load() ?: AssistantSessionStore.migrateLegacy(old.getString("endpoint","").orEmpty(),old.getString("model","").orEmpty(),old.getString("last_answer","").orEmpty())
        AssistantUiState(session)
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
    fun setKey(key:String){if(!_state.value.busy)change{it.copy(apiKey=key)}}
    fun setTools(enabled:Boolean){
        if(_state.value.busy || _state.value.loadBlocked)return
        change{it.copy(session=it.session.copy(toolsEnabled=enabled))};saveSoon()
    }
    fun configure(endpoint:String=_state.value.session.endpoint,model:String=_state.value.session.model){
        if(_state.value.busy || _state.value.loadBlocked)return
        change{it.copy(session=it.session.copy(endpoint=endpoint,model=model))};saveSoon()
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
    fun retry(turn:AssistantTurn,key:String){draft(turn.question);submit(key)}
    @Synchronized fun submit(key:String){
        val state=_state.value
        if(state.busy || state.loadBlocked || state.storageError!=null)return
        val session=state.session;val question=session.current.draft.trim()
        val invalid=runCatching{ModelEndpoint.chatUri(session.endpoint);require(session.model.isNotBlank()){"Enter the installed server model ID."};require(question.isNotBlank()){"Enter a question."}}.exceptionOrNull()
        if(invalid!=null){change{it.copy(phase=invalid.message.orEmpty())};return}
        val control=ModelRequestControl();requestControl=control
        val turn=AssistantTurn(question=question,endpoint=session.endpoint,model=session.model)
        activeTurnId=turn.id
        change{it.copy(busy=true,phase="Saving request",session=it.session.updateCurrent{conversation->
            conversation.copy(draft="",title=if(conversation.turns.isEmpty())question.take(56)else conversation.title,turns=conversation.turns+turn)
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
                    onToolResult={audit->updateTurn(turn.id){it.copy(toolAudit=it.toolAudit+audit)};saveSoon()},allowTools=session.toolsEnabled)
                control.checkActive()
                updateTurn(turn.id){control.checkActive();it.copy(answer=reply.text,state=if(reply.tokenLimited)AssistantTurnState.LIMITED else AssistantTurnState.COMPLETED,
                    detail=if(reply.tokenLimited)"Response reached the server's 256-token limit. Ask a follow-up to continue."else "Server response finished.",sourceIds=reply.sources.map{source->source.id})}
                change{control.checkActive();it.copy(phase=if(reply.tokenLimited)"Response stopped at token limit"else "Response completed")}
            }catch(e:Exception){
                val cancelled=control.reason?.startsWith("Cancelled")==true
                val detail=control.reason ?: when(e){
                    is ConnectException->"Cannot reach this model server. Start the built-in engine or Ollama, then retry. Your draft and partial response are retained."
                    is UnknownHostException->"The server address could not be resolved. Check your connection or use a running local server, then retry."
                    is SocketTimeoutException->"The model server did not respond in time. Check that its model is loaded, then retry."
                    else->e.message ?: "The model request failed. Check the server and retry."
                }
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
        fun get(context:Context)=instance ?: synchronized(this){instance ?: AssistantSessionController(context.applicationContext).also{instance=it}}
    }
}

@Composable fun OperationalAssistantScreen(c:Context){
    val controller=remember{AssistantSessionController.get(c)}
    val state by controller.state.collectAsState()
    val session=state.session
    val key=state.apiKey
    var showConnections by remember{mutableStateOf(session.endpoint.isBlank() || session.model.isBlank())}
    var showSessions by remember{mutableStateOf(false)}
    var copiedId by remember{mutableStateOf<String?>(null)}
    val lifecycle=LocalLifecycleOwner.current
    val clipboard=LocalClipboardManager.current
    val textColor=Color(0xFFEAF5FF)
    val muted=Color(0xFF91A9C8)
    DisposableEffect(lifecycle){
        val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_STOP && (c as? Activity)?.isChangingConfigurations!=true)controller.cancel("Cancelled when the app entered the background; retry to continue.")}
        lifecycle.lifecycle.addObserver(observer)
        onDispose{lifecycle.lifecycle.removeObserver(observer);if((c as? Activity)?.isChangingConfigurations!=true)controller.cancel("Cancelled when leaving AI Chat; retry to continue.")}
    }
    BackHandler(state.pendingReview!=null){controller.review(false)}
    state.pendingReview?.let{proposal->
        AlertDialog(onDismissRequest={controller.review(false)},title={Text("Review model tool")},
            text={SelectionContainer{Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
                Text(proposal.name,fontWeight=FontWeight.Bold);Text(proposal.effect);Text("Arguments:\n${proposal.arguments}")
                Text("Destination: ${session.endpoint}\nOnly this one call will be approved.",fontSize=12.sp)
            }}},confirmButton={TextButton({controller.review(true)}){Text("Approve this call")}},dismissButton={TextButton({controller.review(false)}){Text("Decline")}})
    }
    if(showSessions)AlertDialog(onDismissRequest={showSessions=false},title={Text("Saved conversations")},text={
        LazyColumn{items(session.conversations.asReversed(),key={it.id}){conversation->TextButton({controller.select(conversation.id);showSessions=false},enabled=!state.busy){Text(conversation.title)}}}
    },confirmButton={TextButton({showSessions=false}){Text("Close")}})
    LazyColumn(Modifier.fillMaxSize().imePadding().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{
            Text("AI IN YOUR LIBRARY",color=textColor,fontSize=24.sp,fontWeight=FontWeight.Bold)
            Text("${session.current.title}\nDrafts, partial answers and conversation history are saved on this device.",color=muted,fontSize=14.sp)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                OutlinedButton({showSessions=true},enabled=!state.busy){Text("Sessions")}
                OutlinedButton({controller.newConversation()},enabled=!state.busy && !state.loadBlocked){Text("New conversation")}
            }
            TextButton({showConnections=!showConnections},enabled=!state.busy){Text(if(showConnections)"Hide connection settings"else "Connection: ${session.model.ifBlank{"configure server"}}")}
        }
        if(showConnections){
            item{Text("Connect a running built-in GGUF, Ollama, or HTTPS OpenAI-compatible server. Questions, the recent conversation and source context go to this server. Each model-requested tool needs your review. API keys are kept in memory only.",color=muted,fontSize=14.sp)}
            item{Column(verticalArrangement=Arrangement.spacedBy(6.dp)){
                OutlinedButton({controller.configure(endpoint=ModelEndpoint.BUILT_IN,model="local");controller.setKey("")},enabled=!state.busy,modifier=Modifier.fillMaxWidth()){Text("Built-in GGUF · 8080")}
                OutlinedButton({controller.configure(endpoint=ModelEndpoint.OLLAMA);controller.setKey("")},enabled=!state.busy,modifier=Modifier.fillMaxWidth()){Text("Ollama · 11434")}
                Text("Built-in ID: local. For Ollama, enter your installed tag from ollama list. A preset does not start or install a server.",color=muted,fontSize=12.sp)
            }}
            item{OutlinedTextField(session.endpoint,{controller.configure(endpoint=it)},Modifier.fillMaxWidth(),enabled=!state.busy && !state.loadBlocked,label={Text("API base URL")},supportingText={Text("HTTPS or phone loopback · use /v1")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri,autoCorrectEnabled=false),singleLine=true)}
            item{OutlinedTextField(session.model,{controller.configure(model=it)},Modifier.fillMaxWidth(),enabled=!state.busy && !state.loadBlocked,label={Text("Installed server model ID")},singleLine=true)}
            item{OutlinedTextField(key,{controller.setKey(it)},Modifier.fillMaxWidth(),enabled=!state.busy,label={Text("API key (memory only)")},singleLine=true,visualTransformation=PasswordVisualTransformation())}
            item{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                Text("Allow model tool proposals\nEach call still requires review. Turn off for models without tool support.",color=muted,modifier=Modifier.weight(1f),fontSize=14.sp)
                Switch(session.toolsEnabled,{controller.setTools(it)},enabled=!state.busy && !state.loadBlocked,modifier=Modifier.semantics{contentDescription="Allow model tool proposals; each call requires review"})
            }}
        }
        state.storageError?.let{error->item{Text(error,color=MaterialTheme.colorScheme.error);OutlinedButton({controller.retryStorage()}){Text("Retry session storage")}}}
        if(session.current.turns.isEmpty())item{Text("No messages in this conversation. Select source records or activate skills in your workspace, then ask a question. You can use a local model without internet once its runtime and weights are installed.",color=muted)}
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
                    if(turn.state in setOf(AssistantTurnState.FAILED,AssistantTurnState.CANCELLED,AssistantTurnState.INTERRUPTED))TextButton({controller.retry(turn,key)},enabled=!state.busy && state.storageError==null){Text("Retry question")}
                }
            }}
        }
        item{OutlinedTextField(session.current.draft,{controller.draft(it)},Modifier.fillMaxWidth(),enabled=!state.busy && !state.loadBlocked,label={Text("Ask your library")},supportingText={Text("${session.current.draft.length}/8000 · local draft")},minLines=2,maxLines=8)}
        item{Button({controller.submit(key)},enabled=!state.busy && state.storageError==null && session.current.draft.isNotBlank() && session.endpoint.isNotBlank() && session.model.isNotBlank(),modifier=Modifier.fillMaxWidth()){Text(if(state.busy)"Request in progress"else "Ask model with library context")}}
        if(state.busy)item{LinearProgressIndicator(Modifier.fillMaxWidth());Text("180-second request budget, including tool review. Cancelling preserves partial output.",color=muted,fontSize=12.sp);OutlinedButton({controller.cancel()}){Text("Cancel request")}}
        if(state.phase.isNotBlank())item{Text(state.phase,color=Color(0xFF38D8FF),modifier=Modifier.semantics{liveRegion=LiveRegionMode.Polite})}
    }
}
