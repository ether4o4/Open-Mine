package com.openmine

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.*

@Composable fun MemoryScreen() {
    val context=LocalContext.current
    val store=remember{MemoryStore(context.filesDir)}
    val scope=rememberCoroutineScope()
    var notes by remember{mutableStateOf<List<MemoryNote>>(emptyList())}
    var editing by remember{mutableStateOf<MemoryNote?>(null)}
    var creating by remember{mutableStateOf(false)}
    var title by remember{mutableStateOf("")}
    var text by remember{mutableStateOf("")}
    var shared by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf("")}
    var forget by remember{mutableStateOf<MemoryNote?>(null)}
    LaunchedEffect(Unit){try{notes=withContext(Dispatchers.IO){store.all()}}catch(e:Exception){error=e.message.orEmpty()}}
    Text("Durable AI memory",style=MaterialTheme.typography.titleMedium)
    Text("Only notes you explicitly save here become memory. Phone files and conversations are separate. Shared notes require each conversation to opt in.")
    TextButton({editing=null;title="";text="";shared=false;creating=true}){Text("Add memory note")}
    notes.forEach{note->Row{
        TextButton({editing=note;title=note.title;text=note.text;shared=note.scope=="shared";creating=true}){Text("View / edit ${note.title}")}
        TextButton({forget=note}){Text("Forget")}
    }}
    if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
    if(creating)AlertDialog(onDismissRequest={creating=false},title={Text("Memory note")},text={Column{
        OutlinedTextField(title,{title=it},label={Text("Title")})
        OutlinedTextField(text,{text=it},label={Text("Memory text")},minLines=3,maxLines=8)
        Row{Checkbox(shared,{shared=it});Text("Shared memory (requires conversation opt-in)")}
        Text(if(shared)"Shared scope"else editing?.scope?.takeIf{it!="shared"} ?: "Current conversation only")
    }},confirmButton={TextButton({scope.launch{try{
        withContext(Dispatchers.IO){store.save(editing?.id,title,text,if(shared)"shared"else editing?.scope?.takeIf{it!="shared"} ?: "conversation:${AssistantSessionController.get(context).state.value.session.current.id}")}
        notes=withContext(Dispatchers.IO){store.all()};creating=false
    }catch(e:Exception){error=e.message.orEmpty()}}}){Text("Save memory")}},dismissButton={TextButton({creating=false}){Text("Cancel")}})
    forget?.let{note->AlertDialog(onDismissRequest={forget=null},title={Text("Forget ${note.title}?")},text={Text("Removes this note from future retrieval. Existing chat responses remain until their conversation is forgotten.")},confirmButton={TextButton({scope.launch{try{withContext(Dispatchers.IO){store.forget(note.id)};notes=withContext(Dispatchers.IO){store.all()};forget=null}catch(e:Exception){error=e.message.orEmpty()}}}){Text("Forget")}},dismissButton={TextButton({forget=null}){Text("Keep")}})}
}
