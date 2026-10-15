package com.openmine

import java.io.File
import java.util.UUID
import org.json.JSONObject

data class MemoryNote(val id:String,val title:String,val text:String,val scope:String)
/** Durable user-authored memory. Never populated from phone storage or chat automatically. */
class MemoryStore(private val filesDir:File) {
    private val root=File(filesDir,"ai_memory")
    fun all():List<MemoryNote> = root.listFiles().orEmpty().filter{it.extension=="json"}.map{file->
        require(file.length()<=128*1024){"Memory note exceeds limit"};val data=JSONObject(file.readText());require(data.getInt("schema")==1)
        MemoryNote(data.getString("id"),data.getString("title"),data.getString("text"),data.getString("scope"))
    }
    fun save(id:String?,title:String,text:String,scope:String):MemoryNote {
        require(title.isNotBlank() && title.length<=200 && text.isNotBlank() && text.length<=16000){"Memory needs a title and text (up to 16000 characters)"}
        require(scope=="shared" || Regex("(project|conversation):[a-zA-Z0-9._-]+").matches(scope)){"Select a project, conversation, or explicitly shared scope"}
        val key=id ?: "memory."+UUID.randomUUID();require(Regex("memory\\.[a-zA-Z0-9-]+").matches(key))
        root.mkdirs();val target=File(root,"$key.json");val temp=File(root,"$key.pending")
        temp.outputStream().use{it.write(JSONObject().put("schema",1).put("id",key).put("title",title).put("text",text).put("scope",scope).toString().toByteArray());it.fd.sync()}
        java.nio.file.Files.move(temp.toPath(),target.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        return MemoryNote(key,title,text,scope)
    }
    fun forget(id:String){require(Regex("memory\\.[a-zA-Z0-9-]+").matches(id));check(File(root,"$id.json").delete()){"Could not forget memory"}}
    fun sources(scope:RetrievalScope):List<StrictObject> = all().filter{note->note.id !in scope.excludedIds && when {
        note.scope=="shared" -> scope.sharedMemory
        note.scope.startsWith("project:") -> note.scope.removePrefix("project:")==scope.projectId
        else -> note.scope=="conversation:${scope.conversationId}"
    }}.map{StrictObject(mapOf("OBJECT_ID" to it.id,"OBJECT_TITLE" to it.title,"OBJECT_TYPE" to "KNOWLEDGE","OBJECT_STATUS" to "VERIFIED"),emptyMap(),"USER MEMORY ${it.id} (${it.title}):\n${it.text}")}
}
