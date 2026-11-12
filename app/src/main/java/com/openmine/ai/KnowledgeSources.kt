package com.openmine.ai

import android.content.Context
import com.openmine.MemoryStore
import com.openmine.OpenMineObjectStore
import com.openmine.RetrievalPolicy
import org.json.JSONObject
import java.io.File

/** Gathers on-device knowledge for the built-in guide. Data never leaves the phone through this path. */
object KnowledgeSources {
    private const val MAX_DOC_CHARS = 4 * 1024 * 1024

    fun collect(context: Context): List<KnowledgeDoc> = buildList {
        runCatching { OpenMineObjectStore.all(context) }.getOrDefault(emptyList())
            .filter { RetrievalPolicy.indexable(it) && it.status != "ARCHIVED" }
            .forEach { add(KnowledgeDoc(it.id, it.title, "library:${it.type.lowercase()}", it.raw)) }
        File(context.filesDir, "knowledge_documents").listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith("document.") }.forEach { dir ->
                runCatching {
                    val meta = JSONObject(File(dir, "metadata.json").readText())
                    val text = File(dir, "text.txt")
                    if (meta.optBoolean("enabled", false) && text.length() <= MAX_DOC_CHARS * 4L)
                        add(KnowledgeDoc(meta.getString("id"), meta.getString("name"), "project:${meta.getString("projectId")}", text.readText()))
                }
            }
        runCatching { MemoryStore(context.filesDir).all() }.getOrDefault(emptyList())
            .forEach { add(KnowledgeDoc(it.id, it.title, "memory", it.text)) }
    }
}
