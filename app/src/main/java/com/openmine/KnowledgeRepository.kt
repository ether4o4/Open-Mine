package com.openmine

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale

data class KnowledgeStoreSnapshot(
    val records: List<StrictObject>,
    val issues: List<String>,
    val indexReady: Boolean,
    val chunkCount: Int,
)

/** Source .omd files remain authoritative. The versioned index is disposable derived data. */
class KnowledgeRepository(private val filesDir: File, private val onChange: () -> Unit = {}) {
    private val directory = File(filesDir, "open_mine_objects")
    private val index = File(filesDir, "open_mine_index.json")
    private data class Source(val file: File, val bytes: ByteArray, val hash: String, val record: StrictObject?)
    private data class Sources(val files: List<Source>, val issues: List<String>, val duplicateIds: Set<String>)
    private data class Entry(val record: StrictObject, val terms: Set<String>, val chunks: Int)

    @Synchronized fun inspect(): KnowledgeStoreSnapshot {
        val sources = readSources()
        val entries = loadIndex(sources)
        return KnowledgeStoreSnapshot(entries.map { it.record }.sortedWith(recordOrder), sources.issues, true, entries.sumOf { it.chunks })
    }

    @Synchronized fun search(query: String): List<StrictObject> {
        val terms = tokenize(query.take(8192))
        val entries = loadIndex(readSources())
        if (terms.isEmpty()) return emptyList()
        return entries.map { it to terms.count(it.terms::contains) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<Entry, Int>> { it.second }
                .thenByDescending { it.first.record.sections["CONTEXT_INDEX"]?.get("INDEX_PRIORITY")?.toIntOrNull() ?: 0 }
                .thenBy { it.first.record.title.lowercase(Locale.ROOT) }.thenBy { it.first.record.id })
            .map { it.first.record }
    }

    @Synchronized fun import(raw: String): ValidationResult {
        val checked = OpenMineObjectFormat.validate(raw)
        if (!checked.valid) return checked
        val record = checked.normalized!!
        return try {
            val sources = readSources()
            val target = File(directory, record.id + ".omd")
            if (target.exists() || record.id in sources.duplicateIds || sources.files.any { it.record?.id == record.id })
                return failure("Object ID already exists: ${record.id}. Existing knowledge was preserved.")
            atomicWrite(target, record.raw.toByteArray(Charsets.UTF_8))
            try { writeIndex(readSources()) }
            catch (error: Exception) {
                check(target.delete()) { "Index failed and the newly created record could not be rolled back: ${target.name}" }
                throw error
            }
            checked
        } catch (error: Exception) { failure("Could not save and index object: ${error.message}") }
    }

    @Synchronized fun update(id: String, raw: String): ValidationResult {
        val checked = OpenMineObjectFormat.validate(raw)
        if (!checked.valid) return checked
        if (checked.normalized!!.id != id) return failure("Editing cannot change the object ID")
        return try {
            val source = uniqueSource(id)
            atomicWrite(source.file, checked.normalized.raw.toByteArray(Charsets.UTF_8))
            try { writeIndex(readSources()) }
            catch (error: Exception) { atomicWrite(source.file, source.bytes); throw error }
            checked
        } catch (error: Exception) { failure("Update failed: ${error.message}") }
    }

    @Synchronized fun delete(id: String) {
        val source = uniqueSource(id)
        check(source.file.delete()) { "Could not delete object" }
        try { writeIndex(readSources()) }
        catch (error: Exception) { atomicWrite(source.file, source.bytes); throw error }
    }

    @Synchronized fun rebuildIndex() { writeIndex(readSources()) }

    private fun uniqueSource(id: String): Source {
        require(Regex("^[a-z0-9][a-z0-9._-]{2,100}$").matches(id)) { "Invalid object ID" }
        return readSources().files.singleOrNull { it.record?.id == id }
            ?: throw IOException("Object is missing, invalid, or has a duplicate ID; its source files were preserved")
    }

    private fun readSources(): Sources {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot open object storage" }
        // Earlier versions used AtomicFile. Recover a completed backup only when the main file
        // is absent; never delete an ambiguous copy or an interrupted .new source file.
        val initial = directory.listFiles() ?: throw IOException("Cannot list object storage")
        for (backup in initial.filter { it.name.endsWith(".omd.bak") }) {
            val original = File(directory, backup.name.removeSuffix(".bak"))
            if (!original.exists() && backup.isFile && backup.canonicalFile.parentFile == directory.canonicalFile) {
                val bytes = readBounded(backup, OpenMineObjectFormat.MAX_RECORD_BYTES)
                if (runCatching { OpenMineObjectFormat.validate(decode(bytes)).valid }.getOrDefault(false)) {
                    atomicWrite(original, bytes)
                    onChange()
                }
            }
            if (original.isFile && backup.isFile && backup.canonicalFile.parentFile == directory.canonicalFile) {
                // Retain the backup bytes, but retire the automatic-recovery name so a later
                // deliberate delete cannot resurrect an old object on the next read.
                Files.move(backup.toPath(), File(directory, backup.name + ".preserved-" + java.util.UUID.randomUUID()).toPath())
                onChange()
            }
        }
        val issues = mutableListOf<String>()
        val files = directory.listFiles() ?: throw IOException("Cannot list object storage")
        val sources = files.filter { it.extension == "omd" }.sortedBy { it.name }.map { file ->
            require(file.isFile && file.canonicalFile.parentFile == directory.canonicalFile) { "Unsafe object path: ${file.name}" }
            val bytes = try { readBounded(file, OpenMineObjectFormat.MAX_RECORD_BYTES) }
                catch (error: Exception) {
                    // A failed read must not become a successful empty library or silently enter the index.
                    throw IOException("Cannot read ${file.name}: ${error.message}", error)
                }
            val checked = runCatching { OpenMineObjectFormat.validate(decode(bytes)) }
            val result = checked.getOrNull()
            if (result?.valid != true) issues += "${file.name}: ${checked.exceptionOrNull()?.message ?: result?.errors?.joinToString("; ")}. Source preserved."
            Source(file, bytes, sha256(bytes), result?.normalized)
        }
        val duplicateIds = sources.mapNotNull { it.record?.id }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        duplicateIds.sorted().forEach { issues += "Duplicate OBJECT_ID $it; conflicting source files were preserved and excluded from retrieval." }
        for (pending in files.filter { it.name.endsWith(".omd.new") || it.name.endsWith(".omd.bak") || it.name.contains(".omd.bak.preserved-") })
            issues += "${pending.name}: retained recovery copy; inspect before removing it."
        return Sources(sources.map { if (it.record?.id in duplicateIds) it.copy(record = null) else it }, issues, duplicateIds)
    }

    private fun loadIndex(sources: Sources): List<Entry> {
        if (index.isFile) {
            val loaded = runCatching { parseIndex(decode(readBounded(index, MAX_INDEX_BYTES)), sources) }
            if (loaded.isSuccess) return loaded.getOrThrow()
        }
        // This also migrates the original unversioned HUD/main index without modifying .omd files.
        return writeIndex(sources)
    }

    private fun writeIndex(sources: Sources): List<Entry> {
        val rows = JSONArray()
        for (source in sources.files) {
            val record = source.record ?: continue
            val terms = tokenize(record.sections.values.flatMap { it.values }.joinToString(" ")).sorted()
            val chunks = JSONArray()
            record.sections.forEach { (section, values) -> values.forEach { (label, value) ->
                if (value.isNotBlank() && !value.equals("NONE", true)) chunks.put(JSONObject()
                    .put("OBJECT_ID", record.id).put("OBJECT_TYPE", record.type).put("OBJECT_TITLE", record.title)
                    .put("OBJECT_STATUS", record.status).put("SECTION", section).put("LABEL", label)
                    .put("CHUNK_ID", "${record.id}.$section.$label")
                    .put("CHUNK_PRIORITY", record.sections["CONTEXT_INDEX"]?.get("INDEX_PRIORITY") ?: "50")
                    .put("CONTENT", value))
            } }
            rows.put(JSONObject().put("file", source.file.name).put("id", record.id)
                .put("TERMS", JSONArray(terms)).put("CHUNKS", chunks))
        }
        val payload = JSONObject().put("source_hashes", sourceHashes(sources)).put("records", rows)
        val canonical = canonicalJson(payload)
        val text = canonicalJson(JSONObject().put("schema_version", INDEX_VERSION).put("payload", payload)
            .put("payload_sha256", sha256(canonical.toByteArray(Charsets.UTF_8)))) + "\n"
        val bytes = text.toByteArray(Charsets.UTF_8)
        check(bytes.size <= MAX_INDEX_BYTES) { "Retrieval index exceeds 64 MiB; source records are preserved" }
        // Validate what will be written, and only replace the old complete index after fsync.
        val entries = parseIndex(text, sources)
        atomicWrite(index, bytes)
        onChange()
        return entries
    }

    private fun parseIndex(text: String, sources: Sources): List<Entry> {
        val root = JSONObject(text)
        check(root.getInt("schema_version") == INDEX_VERSION) { "Index schema changed" }
        val payload = root.getJSONObject("payload")
        check(root.getString("payload_sha256") == sha256(canonicalJson(payload).toByteArray(Charsets.UTF_8))) { "Index checksum mismatch" }
        check(canonicalJson(payload.getJSONObject("source_hashes")) == canonicalJson(sourceHashes(sources))) { "Index is stale" }
        val valid = sources.files.filter { it.record != null }.associateBy { it.file.name }
        val rows = payload.getJSONArray("records")
        check(rows.length() == valid.size) { "Index record count mismatch" }
        val seen = mutableSetOf<String>()
        return (0 until rows.length()).map { i ->
            val row = rows.getJSONObject(i)
            val name = row.getString("file")
            check(seen.add(name)) { "Duplicate index source" }
            val source = valid[name] ?: error("Index references unknown source")
            val record = source.record!!
            check(row.getString("id") == record.id) { "Index ID mismatch" }
            val terms = row.getJSONArray("TERMS")
            val values = (0 until terms.length()).map { terms.getString(it) }
            check(values == values.distinct().sorted() && values.all { tokenPattern.matches(it) }) { "Invalid indexed terms" }
            val chunks = row.getJSONArray("CHUNKS")
            val labels = mutableSetOf<Pair<String, String>>()
            for (n in 0 until chunks.length()) {
                val chunk = chunks.getJSONObject(n)
                val section = chunk.getString("SECTION"); val label = chunk.getString("LABEL")
                check(labels.add(section to label) && chunk.getString("OBJECT_ID") == record.id &&
                    chunk.getString("OBJECT_TYPE") == record.type && chunk.getString("OBJECT_TITLE") == record.title &&
                    chunk.getString("OBJECT_STATUS") == record.status &&
                    chunk.getString("CHUNK_PRIORITY") == record.sections["CONTEXT_INDEX"]?.get("INDEX_PRIORITY") &&
                    chunk.getString("CHUNK_ID") == "${record.id}.$section.$label" &&
                    record.sections[section]?.get(label) == chunk.getString("CONTENT")) { "Invalid index chunk" }
            }
            check(chunks.length() == record.sections.values.sumOf { it.values.count { value -> value.isNotBlank() && !value.equals("NONE", true) } }) { "Incomplete index chunks" }
            Entry(record, values.toSet(), chunks.length())
        }
    }

    private fun sourceHashes(sources: Sources) = JSONObject().apply {
        sources.files.forEach { put(it.file.name, it.hash) }
    }

    companion object {
        private const val INDEX_VERSION = 2
        private const val MAX_INDEX_BYTES = 64 * 1024 * 1024
        private val tokenPattern = Regex("[a-z0-9._-]{2,}")
        private val recordOrder = compareBy<StrictObject> { it.title.lowercase(Locale.ROOT) }.thenBy { it.id }
        private fun tokenize(value: String) = value.lowercase(Locale.ROOT).split(Regex("[^a-z0-9._-]+"))
            .filter { it.length >= 2 }.toSet()
        private fun failure(message: String) = ValidationResult(false, listOf(message))
        private fun decode(bytes: ByteArray) = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        private fun readBounded(file: File, limit: Int): ByteArray {
            require(file.length() <= limit) { "File exceeds $limit bytes" }
            return file.inputStream().use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer); if (count < 0) break
                    require(out.size() <= limit - count) { "File exceeds $limit bytes" }
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            }
        }
        private fun atomicWrite(target: File, bytes: ByteArray) {
            val parent = target.absoluteFile.parentFile!!
            check(parent.isDirectory || parent.mkdirs()) { "Cannot open storage directory" }
            val staging = File.createTempFile(".openmine-", ".tmp", parent)
            try {
                staging.outputStream().use { stream -> stream.write(bytes); stream.fd.sync() }
                try { Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                catch (_: AtomicMoveNotSupportedException) { Files.move(staging.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            } finally { staging.delete() }
        }
        private fun canonicalJson(value: Any?): String = when (value) {
            null, JSONObject.NULL -> "null"
            is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonicalJson(value.get(it)) }
            is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonicalJson(value.get(it)) }
            is String -> JSONObject.quote(value)
            is Number, is Boolean -> value.toString()
            else -> error("Unsupported index value")
        }
    }
}
