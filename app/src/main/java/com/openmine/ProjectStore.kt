package com.openmine

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class ProjectTaskStatus { TODO, IN_PROGRESS, DONE }
data class ProjectTask(val id: String, val title: String, val notes: String, val status: ProjectTaskStatus, val updated: Long)
data class ProjectFile(val name: String, val bytes: Long, val modified: Long)
data class ProjectDraft(val name: String, val text: String, val originalModified: Long)

/** App-private project files are independent of the strict object library; IDs are never rewritten. */
class ProjectStore(filesDir: File, val projectId: String) {
    private val filesDir = filesDir.canonicalFile
    companion object {
        const val MAX_TEXT_BYTES = 1024 * 1024
        const val MAX_FILE_BYTES = 16L * 1024 * 1024
        const val MAX_PROJECT_BYTES = 128L * 1024 * 1024
        const val MAX_FILES = 256
        private val locks = ConcurrentHashMap<String, Any>()

        fun validateName(name: String): String {
            require(name.isNotBlank() && name == name.trim() && name.length <= 120) { "Use a filename between 1 and 120 characters without surrounding spaces" }
            require(name != "." && name != ".." && !name.startsWith('.')) { "Hidden and relative filenames are not supported" }
            require(name.none { it == '/' || it == '\\' || it == ':' || it.code < 32 || it.code == 127 }) { "Use a filename without slashes, colons or control characters" }
            return name
        }

        fun suggestedName(value: String): String = value.replace(Regex("[/\\\\:\\p{Cntrl}]"), "_")
            .trim().trimStart('.').take(120).ifBlank { "imported-file" }

        fun readBounded(input: InputStream, maximum: Long): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            copyBounded(input, out, maximum)
            return out.toByteArray()
        }

        fun copyBounded(input: InputStream, output: OutputStream, maximum: Long): Long {
            val buffer = ByteArray(32 * 1024)
            var count = 0L
            while (true) {
                if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Operation cancelled")
                val read = input.read(buffer)
                if (read == -1) return count
                if (read == 0) continue
                count += read
                require(count <= maximum) { "File exceeds the ${maximum / 1024} KiB limit" }
                output.write(buffer, 0, read)
            }
        }
    }

    init { require(Regex("^[a-z0-9][a-z0-9._-]{2,100}$").matches(projectId)) { "Invalid project ID" } }
    private val root get() = safeDirectory(File(safeDirectory(File(filesDir, "open_mine_projects")), projectId))
    private val workspace get() = safeDirectory(File(root, "files"))
    private val drafts get() = safeDirectory(File(root, "drafts"))
    private val lock get() = locks.getOrPut(File(filesDir, "open_mine_projects/$projectId").absolutePath) { Any() }

    private fun safeDirectory(file: File): File {
        require(!Files.isSymbolicLink(file.toPath())) { "Workspace contains an unsupported symbolic link" }
        check(file.isDirectory || file.mkdirs()) { "Could not create project workspace" }
        require(file.canonicalFile == file.absoluteFile) { "Workspace path is not canonical" }
        return file
    }

    private fun target(name: String): File = File(workspace, validateName(name)).also {
        require(!Files.isSymbolicLink(it.toPath()) && it.canonicalFile.parentFile == workspace.canonicalFile) { "File is outside this project" }
    }

    fun listFiles(): List<ProjectFile> = synchronized(lock) {
        (workspace.listFiles() ?: error("Could not read project files")).filter { it.isFile && !it.name.startsWith('.') && !Files.isSymbolicLink(it.toPath()) }
            .map { ProjectFile(it.name, it.length(), it.lastModified()) }.sortedBy { it.name.lowercase() }
    }

    fun uniqueName(proposed: String): String = synchronized(lock) {
        val safe = suggestedName(proposed)
        if (!target(safe).exists()) return@synchronized safe
        val dot = safe.lastIndexOf('.').takeIf { it > 0 } ?: safe.length
        val stem = safe.substring(0, dot).take(95)
        val suffix = safe.substring(dot).take(15)
        (2..9999).asSequence().map { "$stem ($it)$suffix" }.firstOrNull { !target(it).exists() }
            ?: error("Choose another filename")
    }

    fun importFile(name: String, input: InputStream): ProjectFile = synchronized(lock) {
        val file = target(name)
        require(!file.exists()) { "A file with this name already exists; choose another name" }
        val files = listFiles()
        require(files.size < MAX_FILES) { "Project already contains $MAX_FILES files" }
        val allowance = minOf(MAX_FILE_BYTES, MAX_PROJECT_BYTES - files.sumOf { it.bytes })
        require(allowance > 0) { "Project storage limit reached" }
        atomicWrite(file) { copyBounded(input, it, allowance) }
        ProjectFile(name, file.length(), file.lastModified())
    }

    fun createText(name: String, text: String = ""): ProjectFile = text.toByteArray(Charsets.UTF_8).let { bytes ->
        require(bytes.size <= MAX_TEXT_BYTES) { "Text exceeds 1 MiB" }
        bytes.inputStream().use { importFile(name, it) }
    }

    fun readText(name: String): String = synchronized(lock) {
        val bytes = target(name).inputStream().use { readBounded(it, MAX_TEXT_BYTES.toLong()) }
        require(bytes.none { it == 0.toByte() }) { "This is a binary file; use Export to open it in another app" }
        try { Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString() }
        catch (_: java.nio.charset.CharacterCodingException) { error("This file is not UTF-8 text; use Export to open it in another app") }
    }

    fun saveText(name: String, text: String, expectedModified: Long): ProjectFile = synchronized(lock) {
        val file = target(name)
        require(file.isFile) { "File no longer exists; create a new file to preserve this draft" }
        require(file.lastModified() == expectedModified) { "File changed since it was opened; reopen it before saving. Your draft is preserved." }
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_TEXT_BYTES) { "Text exceeds 1 MiB" }
        require(listFiles().sumOf { it.bytes } - file.length() + bytes.size <= MAX_PROJECT_BYTES) { "Project storage limit reached" }
        atomicWrite(file) { it.write(bytes) }
        deleteDraft(name)
        ProjectFile(name, file.length(), file.lastModified())
    }

    fun exportFile(name: String, output: OutputStream) = synchronized(lock) {
        target(name).inputStream().use { copyBounded(it, output, MAX_FILE_BYTES) }
    }

    fun deleteFile(name: String) = synchronized(lock) {
        require(target(name).delete()) { "Could not delete file" }
        deleteDraft(name)
    }

    private fun draftFile(name: String): File {
        validateName(name)
        val key = MessageDigest.getInstance("SHA-256").digest(name.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(drafts, "$key.json")
    }

    fun readDraft(name: String): ProjectDraft? = synchronized(lock) {
        val file = draftFile(name)
        if (!file.exists()) return@synchronized null
        require(!Files.isSymbolicLink(file.toPath())) { "Invalid draft storage" }
        val json = JSONObject(file.inputStream().use { readBounded(it, (MAX_TEXT_BYTES * 7L)) }.toString(Charsets.UTF_8))
        require(json.getInt("version") == 1 && json.getString("name") == name) { "Unsupported project draft; existing data was preserved" }
        ProjectDraft(name, json.getString("text"), json.getLong("originalModified"))
    }

    fun saveDraft(draft: ProjectDraft) = synchronized(lock) {
        val bytes = draft.text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_TEXT_BYTES) { "Draft exceeds 1 MiB" }
        val saved = target(draft.name)
        // A lifecycle flush queued just after Save must not resurrect an already-saved draft.
        if (saved.isFile && saved.length() == bytes.size.toLong() && saved.inputStream().use { readBounded(it, MAX_TEXT_BYTES.toLong()) }.contentEquals(bytes)) {
            deleteDraft(draft.name)
            return@synchronized
        }
        val json = JSONObject().put("version", 1).put("name", draft.name).put("text", draft.text).put("originalModified", draft.originalModified)
        atomicWrite(draftFile(draft.name)) { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
    }

    fun deleteDraft(name: String) = synchronized(lock) {
        val file = draftFile(name)
        check(!file.exists() || file.delete()) { "Could not clear saved draft" }
    }

    fun tasks(): List<ProjectTask> = synchronized(lock) {
        val file = File(root, "tasks.json")
        if (!file.exists()) return@synchronized emptyList()
        require(!Files.isSymbolicLink(file.toPath())) { "Invalid task storage" }
        val json = JSONObject(file.inputStream().use { readBounded(it, 8L * 1024 * 1024) }.toString(Charsets.UTF_8))
        require(json.getInt("version") == 1 && json.getString("projectId") == projectId) { "Unsupported project task format; existing data was preserved" }
        val entries = json.getJSONArray("tasks")
        require(entries.length() <= 500) { "Task storage limit exceeded" }
        (0 until entries.length()).map { index -> entries.getJSONObject(index).let {
            ProjectTask(it.getString("id"), it.getString("title"), it.optString("notes"), ProjectTaskStatus.valueOf(it.getString("status")), it.getLong("updated"))
        } }
    }

    fun saveTask(id: String?, title: String, notes: String, status: ProjectTaskStatus): ProjectTask = synchronized(lock) {
        require(title.isNotBlank() && title.length <= 240) { "Task title must contain 1–240 characters" }
        require(notes.length <= 10000) { "Task notes exceed 10,000 characters" }
        val current = tasks()
        require(id == null || current.any { it.id == id }) { "Task no longer exists" }
        require(id != null || current.size < 500) { "Project already has 500 tasks" }
        val task = ProjectTask(id ?: UUID.randomUUID().toString(), title.trim(), notes, status, System.currentTimeMillis())
        writeTasks(if (id == null) current + task else current.map { if (it.id == id) task else it })
        task
    }

    fun deleteTask(id: String) = synchronized(lock) {
        val current = tasks()
        require(current.any { it.id == id }) { "Task no longer exists" }
        writeTasks(current.filterNot { it.id == id })
    }

    private fun writeTasks(tasks: List<ProjectTask>) {
        val entries = JSONArray()
        tasks.forEach { entries.put(JSONObject().put("id", it.id).put("title", it.title).put("notes", it.notes).put("status", it.status.name).put("updated", it.updated)) }
        val json = JSONObject().put("version", 1).put("projectId", projectId).put("tasks", entries)
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 8 * 1024 * 1024) { "Project task storage limit reached" }
        atomicWrite(File(root, "tasks.json")) { it.write(bytes) }
    }

    private fun atomicWrite(file: File, write: (OutputStream) -> Unit) {
        require(!Files.isSymbolicLink(file.toPath())) { "Symbolic links are not writable" }
        val temp = File.createTempFile(".openmine-", ".tmp", file.parentFile)
        try {
            FileOutputStream(temp).use { stream -> write(stream); stream.fd.sync() }
            try { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        } finally { temp.delete() }
    }
}
