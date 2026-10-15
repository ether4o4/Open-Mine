package com.openmine

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Model tools can address only the conversation's explicitly selected project. */
object ProjectModelTools {
    val names = setOf("list_project_files", "read_project_file", "create_project_file")

    fun definitions(): List<JSONObject> {
        fun tool(name: String, description: String, properties: JSONObject, required: List<String>) =
            JSONObject().put("type", "function").put("function", JSONObject().put("name", name)
                .put("description", description).put("parameters", JSONObject().put("type", "object")
                    .put("properties", properties).put("required", JSONArray(required)).put("additionalProperties", false)))
        fun type(value: String) = JSONObject().put("type", value)
        return listOf(
            tool("list_project_files", "List up to 50 filenames in the selected project after user approval; never scan phone storage.", JSONObject(), emptyList()),
            tool("read_project_file", "Read up to 6000 characters of a UTF-8 project file after approval. Use next_offset for more. Binary/model files are rejected.",
                JSONObject().put("name", type("string")).put("offset", type("integer")), listOf("name", "offset")),
            tool("create_project_file", "Create a new UTF-8 code or text file in the selected project after approval of its exact contents. Never overwrite an existing file.",
                JSONObject().put("name", type("string")).put("content", type("string")), listOf("name", "content")),
        )
    }

    fun validate(name: String, args: JSONObject): String {
        val keys = args.keys().asSequence().toSet()
        when (name) {
            "list_project_files" -> require(keys.isEmpty()) { "Project listing accepts no arguments" }
            "read_project_file" -> {
                require(keys == setOf("name", "offset") && args.get("name") is String) { "Provide only a filename and integer offset" }
                ProjectStore.validateName(args.getString("name"))
                val offset = args.get("offset")
                require((offset is Int || offset is Long) && args.getLong("offset") in 0..ProjectStore.MAX_TEXT_BYTES.toLong()) { "Invalid file offset" }
            }
            "create_project_file" -> {
                require(keys == setOf("name", "content") && args.get("name") is String && args.get("content") is String) { "Provide only a filename and text content" }
                ProjectStore.validateName(args.getString("name"))
                require(args.getString("content").length <= 12000 && '\u0000' !in args.getString("content")) { "New text file exceeds the tool limit or contains binary data" }
            }
            else -> error("Unknown project tool")
        }
        return when (name) {
            "list_project_files" -> "Send up to 50 filenames from this conversation's selected project to the selected model server."
            "read_project_file" -> "Send up to 6000 characters of the named file from this conversation's selected project to the selected model server."
            else -> "Create the named file with the exact content below in this conversation's selected project. Existing files and phone originals cannot be overwritten. The generated code is saved, not executed."
        }
    }

    fun execute(filesDir: File, records: List<StrictObject>, scope: RetrievalScope, proposal: ModelToolProposal, checkCancelled: () -> Unit): String {
        checkCancelled()
        require(proposal.name in names) { "Unknown project tool" }
        val args = JSONObject(proposal.arguments)
        validate(proposal.name, args)
        val projectId = requireNotNull(scope.projectId) { "Select a project in this conversation before using file tools" }
        RetrievalPolicy.requireActiveProject(records, scope)
        val project = records.single { it.id == projectId && it.type == "PROJECT" }
        require(projectId !in scope.excludedIds && RetrievalPolicy.indexable(project)) { "This project's source access is excluded" }
        val store = ProjectStore(filesDir, projectId)
        checkCancelled()
        val result = JSONObject().put("project_id", projectId)
        when (proposal.name) {
            "list_project_files" -> {
                val files = store.listFiles()
                result.put("files", JSONArray(files.take(50).map { JSONObject().put("name", it.name).put("bytes", it.bytes) }))
                    .put("total", files.size).put("truncated", files.size > 50)
            }
            "read_project_file" -> {
                val content = store.readText(args.getString("name"))
                val offset = args.getInt("offset")
                require(offset <= content.length) { "Offset is beyond the end of the file" }
                val end = minOf(content.length, offset + 6000)
                result.put("name", args.getString("name")).put("content", content.substring(offset, end))
                    .put("offset", offset).put("next_offset", if (end < content.length) end else JSONObject.NULL)
                    .put("total_characters", content.length)
            }
            "create_project_file" -> {
                val created = store.createText(args.getString("name"), args.getString("content"))
                result.put("created", true).put("name", created.name).put("bytes", created.bytes)
            }
        }
        return result.toString()
    }
}
