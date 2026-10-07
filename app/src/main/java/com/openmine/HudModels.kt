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
        linkedMapOf("OVERVIEW" to summary,
            "CONTENT" to listOfNotNull(clean(content["CONTENT_PURPOSE"]), clean(content["CONTENT_FACTS"])).joinToString("\n\n").ifBlank { "No purpose or facts recorded." },
            "PROCEDURE" to (clean(content["CONTENT_PROCEDURE"]) ?: "No procedure recorded. Saved procedures do not run automatically."),
            "CONTEXT" to section("CONTEXT_INDEX"),
            "RELATED" to section("RELATIONSHIPS")),
    )
}
