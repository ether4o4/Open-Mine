package com.openmine.ai

import java.util.Locale
import kotlin.math.ln

/** One source document: a library record, an ingested document or a memory note. */
data class KnowledgeDoc(val id: String, val title: String, val namespace: String, val text: String)

data class KnowledgeHit(val id: String, val docId: String, val title: String, val namespace: String, val score: Double, val preview: String)

/**
 * Progressive-disclosure retrieval for small local models (the Recursive Language Model pattern):
 * knowledge stays outside the prompt as an external object. The model sees a short namespace index,
 * then calls search (BM25 over ~500-token passages) and reads one bounded passage at a time, repeating
 * as needed before it answers. Nothing is ever placed in the prompt wholesale.
 */
class KnowledgeIndex(docs: List<KnowledgeDoc>) {
    data class Passage(val id: String, val doc: KnowledgeDoc, val text: String, val terms: Map<String, Int>, val length: Int)

    val passages: List<Passage>
    private val documentFrequency = HashMap<String, Int>()
    private val averageLength: Double

    init {
        passages = docs.flatMap { doc -> split(doc.text).mapIndexed { n, chunk ->
            val tokens = tokenize(doc.title + " " + chunk)
            Passage("${doc.id}#$n", doc, chunk, tokens.groupingBy { it }.eachCount(), tokens.size)
        } }
        passages.forEach { p -> p.terms.keys.forEach { documentFrequency.merge(it, 1, Int::plus) } }
        averageLength = passages.map { it.length }.average().takeIf { !it.isNaN() && it > 0 } ?: 1.0
    }

    /** BM25 (k1 = 1.2, b = 0.75). Returns at most [limit] passages with short previews. */
    fun search(query: String, limit: Int = 5): List<KnowledgeHit> {
        val terms = tokenize(query.take(1000)).distinct()
        if (terms.isEmpty() || passages.isEmpty()) return emptyList()
        val n = passages.size.toDouble()
        return passages.map { p ->
            var score = 0.0
            for (t in terms) {
                val tf = p.terms[t] ?: continue
                val df = documentFrequency[t] ?: continue
                val idf = ln(1 + (n - df + 0.5) / (df + 0.5))
                score += idf * (tf * 2.2) / (tf + 1.2 * (0.25 + 0.75 * p.length / averageLength))
            }
            p to score
        }.filter { it.second > 0 }.sortedWith(compareByDescending<Pair<Passage, Double>> { it.second }.thenBy { it.first.id })
            .take(limit.coerceIn(1, 10))
            .map { (p, s) -> KnowledgeHit(p.id, p.doc.id, p.doc.title, p.doc.namespace, s, preview(p.text, terms)) }
    }

    /** One passage, bounded to [MAX_SLICE] characters (~1,000 tokens). */
    fun read(id: String): String? = passages.firstOrNull { it.id == id }?.let { "[${it.id}] ${it.doc.title} (${it.doc.namespace})\n${it.text.take(MAX_SLICE)}" }

    /** The ~50-token index the system prompt carries instead of the knowledge itself. */
    fun summary(): String {
        if (passages.isEmpty()) return "Knowledge: none imported yet."
        val byNamespace = passages.groupBy { it.doc.namespace }.mapValues { (_, v) -> v.map { it.doc.id }.distinct().size }
        return "Knowledge (search_knowledge then read_slice): " + byNamespace.entries.sortedBy { it.key }.take(8)
            .joinToString("; ") { "${it.key} ${it.value} item${if (it.value == 1) "" else "s"}" }
    }

    companion object {
        const val PASSAGE_CHARS = 2000
        const val MAX_SLICE = 4000
        private val stop = setOf("the", "and", "for", "are", "with", "that", "this", "from", "what", "does", "say", "about", "how", "you", "your", "my", "is", "of", "to", "in", "a", "an", "on", "it", "do", "i")

        fun tokenize(text: String): List<String> = text.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length > 1 && it !in stop }.map(::stem)

        /** Tiny suffix stemmer so "inverters" matches "inverter" and "wiring" matches "wire". */
        private fun stem(t: String): String = when {
            t.length > 5 && t.endsWith("ing") -> t.dropLast(3)
            t.length > 4 && t.endsWith("ies") -> t.dropLast(3) + "y"
            t.length > 3 && t.endsWith("es") && !t.endsWith("ses") -> t.dropLast(2)
            t.length > 3 && t.endsWith("s") && !t.endsWith("ss") -> t.dropLast(1)
            t.length > 4 && t.endsWith("ed") -> t.dropLast(2)
            else -> t
        }.let { if (it.length > 3 && it.endsWith("e")) it.dropLast(1) else it }

        /** Splits on paragraph boundaries into passages of about [PASSAGE_CHARS] characters. */
        fun split(text: String): List<String> {
            val clean = text.replace("\r\n", "\n").trim()
            if (clean.isEmpty()) return emptyList()
            val out = mutableListOf<String>(); val current = StringBuilder()
            for (paragraph in clean.split(Regex("\n\\s*\n"))) {
                var rest = paragraph.trim()
                while (rest.length > PASSAGE_CHARS) {
                    if (current.isNotEmpty()) { out += current.toString(); current.clear() }
                    val cut = rest.lastIndexOf(' ', PASSAGE_CHARS).takeIf { it > PASSAGE_CHARS / 2 } ?: PASSAGE_CHARS
                    out += rest.substring(0, cut).trim(); rest = rest.substring(cut).trim()
                }
                if (current.length + rest.length + 2 > PASSAGE_CHARS && current.isNotEmpty()) { out += current.toString(); current.clear() }
                if (rest.isNotEmpty()) { if (current.isNotEmpty()) current.append("\n\n"); current.append(rest) }
            }
            if (current.isNotEmpty()) out += current.toString()
            return out
        }

        private fun preview(text: String, terms: List<String>): String {
            val lower = text.lowercase(Locale.ROOT)
            val at = terms.map { lower.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: 0
            val start = (at - 60).coerceAtLeast(0)
            return (if (start > 0) "…" else "") + text.substring(start, (start + 220).coerceAtMost(text.length)).replace('\n', ' ').trim()
        }
    }
}
