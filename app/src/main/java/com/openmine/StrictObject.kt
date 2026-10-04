package com.openmine

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class StrictObject(val fields: Map<String,String>, val sections: Map<String,Map<String,String>>, val raw: String) {
    val id get() = fields["OBJECT_ID"].orEmpty()
    val type get() = fields["OBJECT_TYPE"].orEmpty()
    val status get() = fields["OBJECT_STATUS"].orEmpty()
    val title get() = fields["OBJECT_TITLE"].orEmpty()
}
data class ValidationResult(val valid:Boolean,val errors:List<String>,val normalized:StrictObject?=null)

object OpenMineObjectFormat {
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
        val o = parse(raw)
        val e = mutableListOf<String>()
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
        val vs = o.sections["VERIFICATION"]?.get("VERIFICATION_STATUS").orEmpty()
        if (vs !in statuses) e.add("VERIFICATION_STATUS is invalid.")
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

    fun now():String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'",Locale.US).format(Date())

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
        return normalize(StrictObject(s["OPEN_MINE_OBJECT"].orEmpty(),s,""))
    }
}

object OpenMineObjectStore {
    private const val DIR="open_mine_objects"
    private const val INDEX="open_mine_index.json"
    private fun dir(c:Context)=File(c.filesDir,DIR).apply{mkdirs()}

    fun all(c:Context):List<StrictObject> = dir(c).listFiles()?.filter{it.extension=="omd"}?.mapNotNull {
        runCatching{OpenMineObjectFormat.validate(it.readText()).normalized}.getOrNull()
    }?.filterNotNull()?.sortedBy{it.title} ?: emptyList()

    fun import(c:Context,raw:String):ValidationResult {
        val r=OpenMineObjectFormat.validate(raw)
        if(!r.valid)return r
        val o=r.normalized!!
        File(dir(c),o.id+".omd").writeText(o.raw)
        rebuildIndex(c)
        return r
    }

    fun create(c:Context,o:StrictObject)=import(c,o.raw)

    fun rebuildIndex(c:Context) {
        val root=JSONObject()
        all(c).forEach { o ->
            val terms=linkedSetOf<String>()
            val source=(o.id+" "+o.title+" "+o.fields["OBJECT_SUMMARY"].orEmpty()+" "+o.sections.values.flatMap{it.values}.joinToString(" "))
            terms.addAll(tokenize(source))
            val chunks=JSONArray()
            o.sections.forEach { (section,values) -> values.forEach { (label,value) ->
                if(value.isNotBlank()&&!value.equals("NONE",true)) chunks.put(JSONObject()
                    .put("OBJECT_ID",o.id).put("OBJECT_TYPE",o.type).put("OBJECT_TITLE",o.title).put("OBJECT_STATUS",o.status)
                    .put("SECTION",section).put("LABEL",label).put("CHUNK_ID",o.id+"."+section+"."+label)
                    .put("CHUNK_PRIORITY",o.sections["CONTEXT_INDEX"]?.get("INDEX_PRIORITY") ?: "50").put("CONTENT",value))
            }}
            root.put(o.id,JSONObject().put("OBJECT_ID",o.id).put("OBJECT_TYPE",o.type).put("OBJECT_TITLE",o.title).put("OBJECT_STATUS",o.status).put("TERMS",JSONArray(terms.toList())).put("CHUNKS",chunks))
        }
        File(c.filesDir,INDEX).writeText(root.toString(2))
    }

    fun search(c:Context,q:String):List<StrictObject> {
        val terms=tokenize(q)
        return all(c).map{o ->
            val text=o.id+" "+o.title+" "+o.fields["OBJECT_SUMMARY"].orEmpty()+" "+o.sections.values.flatMap{it.values}.joinToString(" ")
            o to terms.count{tokenize(text).contains(it)}
        }.filter{it.second>0}.sortedByDescending{it.second}.map{it.first}
    }

    private fun tokenize(v:String)=v.lowercase(Locale.US).split(Regex("[^a-z0-9._-]+")).filter{it.length>=2}.toSet()
}
