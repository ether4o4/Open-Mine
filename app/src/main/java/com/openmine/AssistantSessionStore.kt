package com.openmine

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

enum class AssistantTurnState { STREAMING, COMPLETED, LIMITED, CANCELLED, FAILED, INTERRUPTED }

data class AssistantTurn(
    val id:String=UUID.randomUUID().toString(),
    val question:String,
    val answer:String="",
    val state:AssistantTurnState=AssistantTurnState.STREAMING,
    val detail:String="",
    val endpoint:String="",
    val model:String="",
    val createdAt:Long=System.currentTimeMillis(),
    val sourceIds:List<String> = emptyList(),
    val toolAudit:List<String> = emptyList()
)

data class AssistantConversation(
    val id:String=UUID.randomUUID().toString(),
    val title:String="New conversation",
    val draft:String="",
    val turns:List<AssistantTurn> = emptyList()
)

data class AssistantSession(
    val endpoint:String="",
    val model:String="",
    val conversations:List<AssistantConversation> = listOf(AssistantConversation()),
    val selectedId:String=conversations.first().id,
    val toolsEnabled:Boolean=true
) {
    val current:AssistantConversation get()=conversations.first{it.id==selectedId}
    fun updateCurrent(change:(AssistantConversation)->AssistantConversation)=copy(conversations=conversations.map{if(it.id==selectedId)change(it) else it})
    fun recoverInterrupted():AssistantSession=copy(conversations=conversations.map{conversation->
        conversation.copy(turns=conversation.turns.map{turn->
            if(turn.state==AssistantTurnState.STREAMING)turn.copy(state=AssistantTurnState.INTERRUPTED,detail="The app stopped before this response finished. Partial text is retained; retry when your server is available.") else turn
        })
    })
    fun completeHistory():List<Pair<String,String>> = current.turns.filter{it.state==AssistantTurnState.COMPLETED || it.state==AssistantTurnState.LIMITED}
        .takeLast(6).map{it.question to it.answer}
}

/** Versioned private storage. Never persists API keys, and never silently discards a corrupt history. */
class AssistantSessionStore(private val file:File) {
    fun exists()=file.exists()
    @Synchronized fun load():AssistantSession? {
        if(!file.exists())return null
        require(file.length()<=8L*1024*1024){"Assistant history exceeds the safe read limit; existing data was preserved."}
        return decode(file.readText()).recoverInterrupted()
    }
    @Synchronized fun save(session:AssistantSession){
        val text=encode(session)
        require(text.toByteArray(Charsets.UTF_8).size<=8*1024*1024){"Assistant history storage limit reached; existing data was preserved."}
        file.parentFile?.let{check(it.isDirectory || it.mkdirs()){"Cannot open assistant storage."}}
        val temporary=File(file.parentFile,file.name+".pending")
        try{
            temporary.outputStream().use{it.write(text.toByteArray(Charsets.UTF_8));it.fd.sync()}
            try{Files.move(temporary.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)}
            catch(_:java.nio.file.AtomicMoveNotSupportedException){Files.move(temporary.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING)}
        }finally{temporary.delete()}
    }
    companion object {
        private fun strings(values:List<String>)=JSONArray(values)
        private fun list(json:JSONArray):List<String> = (0 until json.length()).map{json.getString(it)}
        fun encode(session:AssistantSession):String=JSONObject().put("schema",1).put("endpoint",session.endpoint).put("model",session.model).put("toolsEnabled",session.toolsEnabled)
            .put("selectedId",session.selectedId).put("conversations",JSONArray(session.conversations.map{conversation->
                JSONObject().put("id",conversation.id).put("title",conversation.title).put("draft",conversation.draft)
                    .put("turns",JSONArray(conversation.turns.map{turn->
                        JSONObject().put("id",turn.id).put("question",turn.question).put("answer",turn.answer).put("state",turn.state.name)
                            .put("detail",turn.detail).put("endpoint",turn.endpoint).put("model",turn.model).put("createdAt",turn.createdAt)
                            .put("sourceIds",strings(turn.sourceIds)).put("toolAudit",strings(turn.toolAudit))
                    }))
            })).toString()
        fun decode(text:String):AssistantSession {
            val json=JSONObject(text)
            require(json.getInt("schema")==1){"Assistant history was created by an unsupported version; existing data was preserved."}
            val rows=json.getJSONArray("conversations")
            require(rows.length()>0){"Assistant history is invalid; existing data was preserved."}
            val conversations=(0 until rows.length()).map{index->
                val row=rows.getJSONObject(index);val turns=row.getJSONArray("turns")
                AssistantConversation(row.getString("id"),row.getString("title"),row.getString("draft"),(0 until turns.length()).map{i->
                    val t=turns.getJSONObject(i)
                    AssistantTurn(t.getString("id"),t.getString("question"),t.getString("answer"),AssistantTurnState.valueOf(t.getString("state")),
                        t.getString("detail"),t.getString("endpoint"),t.getString("model"),t.getLong("createdAt"),list(t.getJSONArray("sourceIds")),list(t.getJSONArray("toolAudit")))
                })
            }
            val selected=json.getString("selectedId")
            require(conversations.map{it.id}.toSet().size==conversations.size && conversations.any{it.id==selected}){"Assistant conversation IDs are invalid; existing data was preserved."}
            return AssistantSession(json.getString("endpoint"),json.getString("model"),conversations,selected,json.optBoolean("toolsEnabled",true))
        }
        fun migrateLegacy(endpoint:String,model:String,lastAnswer:String):AssistantSession {
            val turns=if(lastAnswer.isBlank())emptyList() else listOf(AssistantTurn(question="Previous session (original question unavailable)",answer=lastAnswer,
                state=AssistantTurnState.INTERRUPTED,detail="Imported from the previous app version. Its completion state was not recorded.",endpoint=endpoint,model=model))
            return AssistantSession(endpoint,model,listOf(AssistantConversation(title=if(turns.isEmpty())"New conversation" else "Imported session",turns=turns)))
        }
    }
}
