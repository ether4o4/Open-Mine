package com.openmine

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONObject

data class LibrarySession(
    val query: String = "",
    val selectedId: String? = null,
    val editorActive: Boolean = false,
    val editId: String? = null,
    val rawDraft: String = "",
    val creating: Boolean = false,
    val createType: String = "KNOWLEDGE",
    val createTitle: String = "",
    val createSummary: String = "",
    val exportId: String? = null,
    val editBaseRaw: String = "",
)

/** Uncommitted text is separate from authoritative .omd sources and their retrieval index. */
class LibrarySessionStore(private val file: File) {
    @Synchronized fun load(): LibrarySession {
        if (!file.exists()) {
            val pending = File(file.parentFile, file.name + ".pending")
            if (!pending.exists()) return LibrarySession()
            require(pending.length() <= MAX_BYTES) { "Interrupted library draft exceeds its safe limit; existing bytes were preserved." }
            val restored = decode(Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(pending.readBytes())).toString())
            try { Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(pending.toPath(), file.toPath()) }
            return restored
        }
        require(file.length() <= MAX_BYTES) { "Library session is too large; the existing file was preserved." }
        val bytes = file.readBytes()
        require(bytes.size <= MAX_BYTES) { "Library session grew beyond its safe limit; existing bytes were preserved." }
        return decode(Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString())
    }
    @Synchronized fun save(session: LibrarySession) {
        val bytes = encode(session).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "Library draft is too large to save; shorten it before leaving." }
        file.parentFile?.let { check(it.isDirectory || it.mkdirs()) { "Cannot open library draft storage" } }
        val temporary = File(file.parentFile, file.name + ".pending")
        try {
            temporary.outputStream().use { it.write(bytes); it.fd.sync() }
            try { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        } finally { temporary.delete() }
    }
    companion object {
        const val MAX_BYTES = 6 * 1024 * 1024L
        fun encode(session: LibrarySession): String = JSONObject().put("schema", 1)
            .put("query", session.query).put("selectedId", session.selectedId ?: JSONObject.NULL)
            .put("editorActive", session.editorActive).put("editId", session.editId ?: JSONObject.NULL)
            .put("rawDraft", session.rawDraft).put("creating", session.creating)
            .put("createType", session.createType).put("createTitle", session.createTitle)
            .put("createSummary", session.createSummary).put("exportId", session.exportId ?: JSONObject.NULL)
            .put("editBaseRaw", session.editBaseRaw).toString()
        fun decode(text: String): LibrarySession {
            val json = JSONObject(text)
            require(json.getInt("schema") == 1) { "Unsupported library session version; draft preserved." }
            fun optional(key: String) = if (json.isNull(key)) null else json.getString(key)
            return LibrarySession(json.getString("query"), optional("selectedId"), json.getBoolean("editorActive"),
                optional("editId"), json.getString("rawDraft"), json.getBoolean("creating"),
                json.getString("createType"), json.getString("createTitle"), json.getString("createSummary"), optional("exportId"), json.optString("editBaseRaw", ""))
                .also {
                    require(it.query.length <= 8192 && it.rawDraft.length <= OpenMineObjectFormat.MAX_RECORD_BYTES && it.editBaseRaw.length <= OpenMineObjectFormat.MAX_RECORD_BYTES && it.createTitle.length <= 200 && it.createSummary.length <= 8000) {
                        "Library draft limits exceeded; existing data preserved."
                    }
                }
        }
    }
}
