package com.openmine

/** Local permission policy, evaluated before indexing and again before sending data.
 * Global HUD selection is deliberately not an assistant permission grant. */
data class RetrievalScope(
    val conversationId: String,
    val projectId: String? = null,
    val selectedIds: Set<String> = emptySet(),
    val sharedMemory: Boolean = false,
    val excludedIds: Set<String> = emptySet(),
)

object RetrievalPolicy {
    fun requireActiveProject(records: List<StrictObject>, scope: RetrievalScope) {
        if (scope.projectId == null) return
        require(records.any { it.id == scope.projectId && it.type == "PROJECT" && it.status != "ARCHIVED" }) {
            "This conversation's project is missing or archived. Select an active project or no project before sending sources."
        }
    }
    private fun tokens(value: String?) = value.orEmpty().split(Regex("[,;\\s]+"))
        .filter { it.isNotBlank() && !it.equals("NONE", true) }.toSet()

    fun indexable(record: StrictObject): Boolean = record.status != "ARCHIVED" &&
        record.sections["RETRIEVAL"]?.get("RETRIEVAL_EXCLUDE").let { it.isNullOrBlank() || it.equals("NONE", true) }

    fun allowed(record: StrictObject, scope: RetrievalScope): Boolean {
        if (!indexable(record) || record.id in scope.excludedIds) return false
        val indexScope = record.sections["CONTEXT_INDEX"]?.get("INDEX_SCOPE").orEmpty()
        val projects = tokens(record.sections["RELATIONSHIPS"]?.get("REL_PROJECTS"))
        // Project ownership is restrictive even when a stale selection names this ID.
        if (projects.isNotEmpty() && scope.projectId !in projects) return false
        if (indexScope.startsWith("project:")) return indexScope.removePrefix("project:") == scope.projectId
        if (indexScope.startsWith("conversation:")) return indexScope.removePrefix("conversation:") == scope.conversationId
        if (projects.isNotEmpty()) return true
        if (indexScope == "shared") return scope.sharedMemory
        // Legacy workspace/global records require explicit per-conversation selection.
        return record.id in scope.selectedIds
    }

    fun filter(records: List<StrictObject>, scope: RetrievalScope) = records.filter { allowed(it, scope) }
}
