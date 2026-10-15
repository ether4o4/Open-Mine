package com.openmine

/** HUD content is a projection of persisted records, never an installed-capability catalogue. */
internal val HudRecordTypes = listOf("MODEL", "PROJECT", "CONNECTOR", "KNOWLEDGE")

internal data class HudRecordContent(
    val id: String,
    val title: String,
    val subtitle: String,
    val summary: String,
    val tags: List<String>,
    val metrics: List<Pair<String, String>>,
    val tabs: Map<String, String>,
)

internal fun hudRecordContent(record: StrictObject): HudRecordContent {
    fun clean(value: String?) = value?.takeUnless { it.isBlank() || it.equals("NONE", true) }
    fun section(name: String): String = record.sections[name].orEmpty().entries
        .mapNotNull { (key, value) -> clean(value)?.let { "${key.substringAfter('_').replace('_', ' ').lowercase().replaceFirstChar(Char::uppercase)}: $it" } }
        .joinToString("\n").ifBlank { "No ${name.lowercase()} recorded." }
    val content = record.sections["CONTENT"].orEmpty()
    val summary = clean(record.fields["OBJECT_SUMMARY"]) ?: "No summary recorded. Open the record to add one."
    return HudRecordContent(
        record.id, record.title, "${record.type.lowercase().replaceFirstChar(Char::uppercase)} · ${record.status.lowercase()}",
        summary,
        clean(record.fields["OBJECT_TAGS"])?.split(',')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty(),
        listOf("Record state" to record.status, "Format" to (record.fields["OBJECT_VERSION"] ?: "—"),
            "Source" to (clean(record.fields["OBJECT_SOURCE"]) ?: "Not recorded"),
            "Updated" to (clean(record.fields["OBJECT_UPDATED"])?.take(10) ?: "Not recorded")),
        approvedTabs(record, linkedMapOf("OVERVIEW" to summary,
            "CONTENT" to listOfNotNull(clean(content["CONTENT_PURPOSE"]), clean(content["CONTENT_FACTS"])).joinToString("\n\n").ifBlank { "No purpose or facts recorded." },
            "PROCEDURE" to (clean(content["CONTENT_PROCEDURE"]) ?: "No procedure recorded. Saved procedures do not run automatically."),
            "CONTEXT" to section("CONTEXT_INDEX"),
            "RELATED" to section("RELATIONSHIPS"))),
    )
}

private fun approvedTabs(record: StrictObject, content: Map<String,String>): Map<String,String> {
    val names=when(record.type) {
        "MODEL"->listOf("OVERVIEW","PARAMETERS","CONTEXT","TOOLS","RELATED")
        "PROJECT"->listOf("OVERVIEW","FILES","TASKS","AGENTS","CONTEXT")
        "CONNECTOR"->listOf("OVERVIEW","PERMISSIONS","TOOLS","CONTEXT","LOGS")
        else->listOf("OVERVIEW","DOCUMENTS","CHUNKS","CONTEXT","RELATED")
    }
    return names.associateWith { tab-> content[tab] ?: when(tab) {
        "PARAMETERS","DOCUMENTS"->content.getValue("CONTENT")
        "TOOLS"->content.getValue("PROCEDURE")
        "FILES","TASKS"->"Open this project to view and manage its actual ${tab.lowercase()}."
        "AGENTS"->"No autonomous agent is running. Open AI Chat to make individually reviewed tool requests."
        "PERMISSIONS"->"A connector record grants no phone or external access. Each implemented tool requires explicit review."
        "LOGS"->"No live connector session is established by this record."
        "CHUNKS"->"Open Knowledge to inspect validated source fields. Project document ingestion extracts text separately."
        else->"No saved content."
    }}
}
