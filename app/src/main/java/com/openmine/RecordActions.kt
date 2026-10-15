package com.openmine

/** Importing a record never calls either of these actions. */
object RecordActions {
    fun toolCommand(record: StrictObject): String {
        require(record.type == "TOOL") { "Only TOOL records can run commands" }
        val command = record.sections["CONTENT"]?.get("CONTENT_PROCEDURE").orEmpty().trim()
        require(command.isNotBlank() && command != "NONE") { "Add a shell command in CONTENT_PROCEDURE first" }
        require(command.length <= 8192) { "Tool command exceeds 8 KiB" }
        require(command.none { it.code < 32 && it != '\n' && it != '\t' }) { "Tool command contains unsupported control characters" }
        return command
    }

    fun activatedSkills(records: List<StrictObject>, selected: Set<String>): List<StrictObject> =
        records.filter { it.type == "SKILL" && it.id in selected }.take(8)
}
