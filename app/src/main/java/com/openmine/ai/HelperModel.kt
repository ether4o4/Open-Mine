package com.openmine.ai

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** The optional small model, loaded directly (not through the server) for short background jobs. */
class HelperEngine(private val model: File) {
    @Synchronized fun complete(system: String, user: String, maxTokens: Int): String {
        check(NativeAi.available) { "Native AI library unavailable: ${NativeAi.loadError}" }
        if (!NativeAi.helperLoaded()) NativeAi.helperLoad(model.absolutePath, 2, 2048)?.let { error(it.removePrefix("ERROR: ")) }
        val reply = NativeAi.helperComplete(system.toByteArray(), user.toByteArray(), maxTokens).toString(Charsets.UTF_8)
        check(!reply.startsWith("ERROR:")) { reply.removePrefix("ERROR: ") }
        return reply
    }
    @Synchronized fun unload() { if (NativeAi.available) NativeAi.helperUnload() }
}

/**
 * Writes search tags (a summary plus related words) for each knowledge document once, keyed by a hash
 * of its content, so search can match a note by meaning as well as by its exact words. Tags only
 * affect ranking; passages returned to LFM3 are always the original text.
 */
class NoteTagger(private val cache: File, private val generate: (String, String) -> String) {
    private val tags: MutableMap<String, String> = load()

    fun key(doc: KnowledgeDoc): String = MessageDigest.getInstance("SHA-256")
        .digest((doc.id + "\u0000" + doc.title + "\u0000" + doc.text).toByteArray()).joinToString("") { "%02x".format(it) }.take(32)

    @Synchronized fun apply(docs: List<KnowledgeDoc>): List<KnowledgeDoc> = docs.map { d -> tags[key(d)]?.let { d.copy(tags = it) } ?: d }
    @Synchronized fun missing(docs: List<KnowledgeDoc>): List<KnowledgeDoc> = docs.filter { key(it) !in tags }

    /** Tags each untagged document; returns how many were tagged. Failures leave that document untagged. */
    fun tagMissing(docs: List<KnowledgeDoc>, ensureActive: () -> Unit = {}): Int {
        var count = 0
        for (doc in missing(docs)) {
            ensureActive()
            val raw = runCatching { generate(SYSTEM, "Title: ${doc.title}\nNote: ${doc.text.take(1500)}") }.getOrNull() ?: continue
            synchronized(this) { tags[key(doc)] = sanitize(raw); save() }
            count++
        }
        return count
    }

    private fun load(): MutableMap<String, String> = runCatching {
        val json = JSONObject(cache.readText()); json.keys().asSequence().associateWith { json.getString(it) }.toMutableMap()
    }.getOrDefault(mutableMapOf())

    private fun save() {
        cache.parentFile?.mkdirs()
        val tmp = File(cache.parentFile, cache.name + ".tmp")
        tmp.writeText(JSONObject(tags as Map<*, *>).toString()); tmp.renameTo(cache)
    }

    companion object {
        const val SYSTEM = "You index notes. Reply with one line: a short summary, then 8 search keywords including synonyms and related terms a person might use to look for this note."
        /** Keeps distinct words only: removes list numbering and the model's frequent repetitions, bounded in length. */
        fun sanitize(raw: String): String = raw.replace(Regex("[\\r\\n]+"), " ").replace(Regex("\\b\\d+\\.\\s"), " ")
            .split(Regex("\\s+")).filter { it.isNotBlank() }.distinctBy { it.lowercase().trim(',', '.', ':', ';') }.joinToString(" ").take(600)
    }
}
