package com.openmine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class OperationState { RUNNING, SUCCEEDED, FAILED, CANCELLED, INTERRUPTED }

/** What a failed or interrupted operation can be retried with; null when it needs fresh user input. */
enum class RetryAction(val label:String) {
    VERIFY_SHELL("Check shell again"),
    SETUP_SHELL("Retry shell setup"),
    PROVISION_ENGINE("Retry engine install"),
    ENGINE_STATUS("Check model health"),
    SERVE_MODEL("Load model again"),
    CHOOSE_MODEL_FILE("Choose the file again"),
}

data class OperationRecord(
    val id:String,
    val component:String,
    val label:String,
    val startedAt:Long,
    val endedAt:Long?=null,
    val state:OperationState=OperationState.RUNNING,
    val detail:String="",
    val retry:RetryAction?=null,
    val argument:String="",
) {
    fun toJson():JSONObject=JSONObject().put("id",id).put("component",component).put("label",label).put("startedAt",startedAt)
        .put("endedAt",endedAt ?: JSONObject.NULL).put("state",state.name).put("detail",detail.take(MAX_DETAIL))
        .put("retry",retry?.name ?: JSONObject.NULL).put("argument",argument.take(512))
    companion object {
        const val MAX_DETAIL=2000
        fun from(json:JSONObject)=OperationRecord(json.getString("id"),json.getString("component"),json.getString("label"),json.getLong("startedAt"),
            if(json.isNull("endedAt"))null else json.getLong("endedAt"),OperationState.valueOf(json.getString("state")),json.optString("detail"),
            if(json.isNull("retry"))null else runCatching{RetryAction.valueOf(json.getString("retry"))}.getOrNull(),json.optString("argument"))
    }
}

/**
 * Persistent audit of consequential operations: what ran, which component owned it, whether it can be
 * cancelled or retried and how it ended. Writes are atomic; a corrupt journal is preserved beside the new
 * one rather than silently discarded. Operations still RUNNING when the process died become INTERRUPTED.
 */
class OperationJournal(private val file:File,private val clock:()->Long={System.currentTimeMillis()}) {
    private val _records=MutableStateFlow(load())
    val records:StateFlow<List<OperationRecord>> = _records

    @Synchronized fun begin(component:String,label:String,retry:RetryAction?=null,argument:String=""):String {
        val record=OperationRecord(UUID.randomUUID().toString(),component,label,clock(),retry=retry,argument=argument)
        save(listOf(record)+_records.value)
        return record.id
    }
    @Synchronized fun finish(id:String,state:OperationState,detail:String="") {
        require(state!=OperationState.RUNNING){"An operation cannot finish as running."}
        save(_records.value.map{if(it.id==id && it.state==OperationState.RUNNING)it.copy(state=state,endedAt=clock(),detail=detail)else it})
    }
    @Synchronized fun clearFinished() = save(_records.value.filter{it.state==OperationState.RUNNING})
    fun latestFailure():OperationRecord? = _records.value.firstOrNull{it.state==OperationState.FAILED || it.state==OperationState.INTERRUPTED}

    private fun load():List<OperationRecord> {
        if(!file.exists())return emptyList()
        val parsed=runCatching{
            val array=JSONArray(file.readText())
            List(array.length()){OperationRecord.from(array.getJSONObject(it))}
        }.getOrElse{
            // Keep the unreadable journal for inspection and start a new one.
            runCatching{file.copyTo(File(file.parentFile,file.name+".corrupt-"+clock()),overwrite=false)}
            return emptyList()
        }
        val recovered=parsed.map{if(it.state==OperationState.RUNNING)it.copy(state=OperationState.INTERRUPTED,endedAt=it.endedAt ?: clock(),
            detail="Open Mine stopped before this operation reported a result. ${it.detail}".trim())else it}
        if(recovered!=parsed)runCatching{write(recovered)}
        return recovered
    }
    private fun save(records:List<OperationRecord>) {
        val bounded=records.take(MAX_RECORDS)
        write(bounded)
        _records.value=bounded
    }
    private fun write(records:List<OperationRecord>) {
        file.parentFile?.mkdirs()
        val temporary=File(file.parentFile,file.name+".tmp")
        val array=JSONArray();records.forEach{array.put(it.toJson())}
        temporary.outputStream().use{out->out.write(array.toString().toByteArray(Charsets.UTF_8));out.fd.sync()}
        check(temporary.renameTo(file)){"Could not save the operation journal."}
    }
    companion object {
        const val MAX_RECORDS=100
        @Volatile private var instance:OperationJournal?=null
        fun get(filesDir:File)=instance ?: synchronized(this){instance ?: OperationJournal(File(filesDir,"operations/journal.json")).also{instance=it}}
    }
}
