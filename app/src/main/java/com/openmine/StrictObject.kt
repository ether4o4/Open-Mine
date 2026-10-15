package com.openmine

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

data class StrictObject(val fields: Map<String,String>, val sections: Map<String,Map<String,String>>, val raw: String) {
    val id get() = fields["OBJECT_ID"].orEmpty()
    val type get() = fields["OBJECT_TYPE"].orEmpty()
    val status get() = fields["OBJECT_STATUS"].orEmpty()
    val title get() = fields["OBJECT_TITLE"].orEmpty()
}
data class ValidationResult(val valid:Boolean,val errors:List<String>,val normalized:StrictObject?=null)

object OpenMineObjectFormat {
    const val MAX_RECORD_BYTES = 1024 * 1024
    private val schema = linkedMapOf(
        "OPEN_MINE_OBJECT" to listOf("OBJECT_VERSION","OBJECT_ID","OBJECT_TYPE","OBJECT_STATUS","OBJECT_TITLE","OBJECT_SUMMARY","OBJECT_TAGS","OBJECT_SOURCE","OBJECT_CREATED","OBJECT_UPDATED"),
        "CONTEXT_INDEX" to listOf("INDEX_KEYWORDS","INDEX_ALIASES","INDEX_TRIGGERS","INDEX_SCOPE","INDEX_PRIORITY"),
        "CONTENT" to listOf("CONTENT_PURPOSE","CONTENT_FACTS","CONTENT_PROCEDURE","CONTENT_CONSTRAINTS","CONTENT_EXAMPLES"),
        "RELATIONSHIPS" to listOf("REL_PROJECTS","REL_MODELS","REL_SKILLS","REL_TOOLS","REL_KNOWLEDGE","REL_MISSIONS"),
        "RETRIEVAL" to listOf("RETRIEVAL_QUERY","RETRIEVAL_WHEN","RETRIEVAL_EXCLUDE"),
        "VERIFICATION" to listOf("VERIFICATION_STATUS","VERIFICATION_SOURCE","VERIFICATION_NOTES")
    )
    private val types = setOf("MODEL","PROJECT","CONNECTOR","KNOWLEDGE","SKILL","MISSION","VIBE","TOOL","FILE","BROWSER","TERMINAL","DIAGNOSTIC")
    private val statuses = setOf("DRAFT","TESTED","VERIFIED","PROVEN","ARCHIVED")

    fun parse(raw:String):StrictObject {
        val sections = linkedMapOf<String,MutableMap<String,String>>()
        var current = ""
        raw.replace("\r","").lines().forEach { line0 ->
            val line = line0.trim()
            if (line.startsWith("[") && line.endsWith("]")) {
                current = line.substring(1,line.length-1)
                if (current != "END_OBJECT") sections.getOrPut(current){linkedMapOf()}
            } else {
                val p = line.indexOf(":")
                if (p > 0 && current.isNotBlank() && current != "END_OBJECT")
                    sections.getOrPut(current){linkedMapOf()}[line.substring(0,p).trim()] = line.substring(p+1).trim()
            }
        }
        val maps = sections.mapValues { it.value.toMap() }
        return StrictObject(maps["OPEN_MINE_OBJECT"].orEmpty(),maps,raw)
    }

    fun validate(raw:String):ValidationResult {
        if (raw.length > MAX_RECORD_BYTES || raw.toByteArray(Charsets.UTF_8).size > MAX_RECORD_BYTES)
            return ValidationResult(false, listOf("Object exceeds 1 MiB"))
        if (raw.any { (it.code < 32 && it !in "\r\n\t") || it.code == 127 })
            return ValidationResult(false, listOf("Object contains unsupported control characters"))
        if (raw.replace("\r\n", "").contains('\r'))
            return ValidationResult(false, listOf("Object contains an isolated carriage return"))
        if (runCatching {
                Charsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(raw))
            }.isFailure) return ValidationResult(false, listOf("Object is not valid UTF-8 text"))
        val o = parse(raw)
        val e = mutableListOf<String>()
        var section = ""
        var ended = false
        val seenSections = mutableSetOf<String>()
        val seenLabels = mutableSetOf<String>()
        raw.replace("\r", "").lines().forEachIndexed { index, original ->
            val line = original.trim()
            if (line.isBlank()) return@forEachIndexed
            if (ended) { e.add("Content after END_OBJECT at line ${index + 1}."); return@forEachIndexed }
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length - 1)
                if (section == "END_OBJECT") ended = true
                else if (section !in schema || !seenSections.add(section)) e.add("Unknown or duplicate section at line ${index + 1}.")
            } else {
                val colon = line.indexOf(':')
                val label = if (colon > 0) line.substring(0, colon).trim() else ""
                if (label !in schema[section].orEmpty() || !seenLabels.add("$section.$label"))
                    e.add("Unknown, duplicate or unlabeled content at line ${index + 1}.")
            }
        }
        schema.forEach { pair ->
            if (!o.sections.containsKey(pair.key)) e.add("Missing [" + pair.key + "] section.")
            pair.value.forEach { label ->
                val v = o.sections[pair.key]?.get(label)
                if (v == null) e.add(label + " is required.")
                else if (v.isBlank()) e.add(label + " cannot be blank.")
            }
        }
        if (!raw.contains("[END_OBJECT]")) e.add("Missing [END_OBJECT] marker.")
        if (o.fields["OBJECT_VERSION"] != "1") e.add("OBJECT_VERSION must be 1.")
        val id = o.id
        if (!Regex("^[a-z0-9][a-z0-9._-]{2,100}$").matches(id)) e.add("OBJECT_ID format is invalid.")
        if (o.type !in types) e.add("OBJECT_TYPE is invalid.")
        if (o.status !in statuses) e.add("OBJECT_STATUS is invalid.")
        if (o.title == "NONE") e.add("OBJECT_TITLE needs a meaningful value.")
        val vs = o.sections["VERIFICATION"]?.get("VERIFICATION_STATUS").orEmpty()
        if (vs !in statuses) e.add("VERIFICATION_STATUS is invalid.")
        val priority = o.sections["CONTEXT_INDEX"]?.get("INDEX_PRIORITY")?.toIntOrNull()
        if (priority == null || priority !in 0..100) e.add("INDEX_PRIORITY must be 0–100.")
        listOf("OBJECT_CREATED", "OBJECT_UPDATED").forEach { label ->
            if (runCatching { java.time.Instant.parse(o.fields[label]) }.isFailure) e.add("$label must be an ISO UTC timestamp.")
        }
        return if (e.isEmpty()) ValidationResult(true,emptyList(),normalize(o)) else ValidationResult(false,e)
    }

    fun normalize(o:StrictObject):StrictObject {
        val sections = o.sections.mapValues { (_,m) -> m.mapValues { (k,v) ->
            if (k.endsWith("TAGS") || k.startsWith("INDEX_") || k.startsWith("REL_")) canonicalList(v) else v.trim()
        }}
        return StrictObject(sections["OPEN_MINE_OBJECT"].orEmpty(),sections,serialize(StrictObject(sections["OPEN_MINE_OBJECT"].orEmpty(),sections,"")))
    }

    fun serialize(o:StrictObject):String {
        val b=StringBuilder()
        schema.forEach { pair ->
            b.append("[").append(pair.key).append("]\n")
            pair.value.forEach { k -> b.append(k).append(": ").append(o.sections[pair.key]?.get(k)?.ifBlank{"NONE"} ?: "NONE").append("\n") }
            b.append("\n")
        }
        return b.append("[END_OBJECT]\n").toString()
    }

    fun canonicalList(v:String):String = if (v.equals("NONE",true)) "NONE" else
        v.split(",").map{it.trim().lowercase(Locale.US)}.filter{it.isNotBlank()}.distinct().sorted().joinToString(", ")

    fun now():String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'",Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

    fun template(type:String,title:String,summary:String,tags:String,source:String,purpose:String,facts:String,procedure:String,constraints:String,examples:String,keywords:String,aliases:String,triggers:String,project:String,model:String,skill:String,tool:String,mission:String,query:String,whenText:String,exclude:String,verification:String,verificationSource:String,notes:String):StrictObject {
        val slug=title.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"),"-").trim('-').ifBlank{"object"}
        val id=type.lowercase(Locale.US)+"."+slug
        val n=now()
        val s=linkedMapOf<String,Map<String,String>>(
            "OPEN_MINE_OBJECT" to linkedMapOf("OBJECT_VERSION" to "1","OBJECT_ID" to id,"OBJECT_TYPE" to type,"OBJECT_STATUS" to "DRAFT","OBJECT_TITLE" to title,"OBJECT_SUMMARY" to summary,"OBJECT_TAGS" to tags,"OBJECT_SOURCE" to source,"OBJECT_CREATED" to n,"OBJECT_UPDATED" to n),
            "CONTEXT_INDEX" to linkedMapOf("INDEX_KEYWORDS" to keywords,"INDEX_ALIASES" to aliases,"INDEX_TRIGGERS" to triggers,"INDEX_SCOPE" to "workspace","INDEX_PRIORITY" to "50"),
            "CONTENT" to linkedMapOf("CONTENT_PURPOSE" to purpose,"CONTENT_FACTS" to facts,"CONTENT_PROCEDURE" to procedure,"CONTENT_CONSTRAINTS" to constraints,"CONTENT_EXAMPLES" to examples),
            "RELATIONSHIPS" to linkedMapOf("REL_PROJECTS" to project,"REL_MODELS" to model,"REL_SKILLS" to skill,"REL_TOOLS" to tool,"REL_KNOWLEDGE" to "NONE","REL_MISSIONS" to mission),
            "RETRIEVAL" to linkedMapOf("RETRIEVAL_QUERY" to query,"RETRIEVAL_WHEN" to whenText,"RETRIEVAL_EXCLUDE" to exclude),
            "VERIFICATION" to linkedMapOf("VERIFICATION_STATUS" to verification,"VERIFICATION_SOURCE" to verificationSource,"VERIFICATION_NOTES" to notes)
        )
        val safe = s.mapValues { (_, fields) -> fields.mapValues { (_, value) -> value.replace(Regex("[\\r\\n]+"), " ").ifBlank { "NONE" } } }
        return normalize(StrictObject(safe["OPEN_MINE_OBJECT"].orEmpty(),safe,""))
    }
}

object OpenMineObjectStore {
    private val changed = MutableStateFlow(0L)
    val revision: StateFlow<Long> = changed.asStateFlow()
    private val repositories = ConcurrentHashMap<String, KnowledgeRepository>()
    private fun repository(c:Context):KnowledgeRepository = repositories.getOrPut(c.filesDir.canonicalPath) {
        KnowledgeRepository(c.filesDir) { synchronized(changed) { changed.value += 1 } }
    }
    fun inspect(c:Context):KnowledgeStoreSnapshot = repository(c).inspect()
    fun all(c:Context):List<StrictObject> = inspect(c).records
    fun import(c:Context,raw:String):ValidationResult = repository(c).import(raw)
    fun update(c:Context,id:String,raw:String):ValidationResult = repository(c).update(id,raw)
    fun delete(c:Context,o:StrictObject) = repository(c).delete(o.id)
    fun create(c:Context,o:StrictObject):ValidationResult = import(c,o.raw)
    fun rebuildIndex(c:Context) { repository(c).rebuildIndex() }
    fun search(c:Context,q:String):List<StrictObject> = repository(c).search(q)
}
