package com.openmine.ai

import java.util.Locale

/**
 * Used only before LFM3 is downloaded. Matches typed requests to the setup steps with fixed patterns.
 * Measured on unseen phrasings it is precise but misses most wordings, so a match is only offered for
 * approval, and anything else is pointed to the step buttons and the LFM3 download.
 */
object GuideKeywords {
    private val rules = listOf(
        GuideTool.import_model_file to Regex("""\b(gguf|model file|own model)\b|\b(add|import|load|use)\b.*\bmodel i (already )?downloaded"""),
        GuideTool.download_chat_model to Regex("""\b(download|get|grab|fetch)\b.*\b(lfm3?|chat model|ai model|offline (assistant|model)|local (chat )?model|model)\b|\boffline\b.*\b(chat|assistant)|\b(chat|talk)\b.*\b(offline|without (internet|data|wifi))"""),
        GuideTool.request_folder_access to Regex("""\b(access|use|pick|choose|save|store)\b.*\b(folder|downloads?|documents|storage)\b|\bfolder for\b"""),
        GuideTool.install_runtime to Regex("""\b(terminal|shell|linux|alpine|command line|sandbox|bash)\b.*\b(set ?up|install|working|prepare|going|usable)\b|\b(set ?up|install|need|prepare|get)\b.*\b(terminal|shell|linux|alpine|command line|sandbox)\b"""),
        GuideTool.run_diagnostics to Regex("""\b(broken|not working|isn't working|failing|fails?|error|missing|crash(ed)?|check (my )?setup|command not found|won't|doesn't work|still needs)\b|\bwhy\b.*(\bnot\b|n't\b).*\b(work|load|start|run)"""),
    )

    /** Suggests one setup tool, or null. Folder requests default to Documents unless the text names one. */
    fun suggest(text: String): Pair<GuideTool, Map<String, String>>? {
        val t = text.lowercase(Locale.ROOT).trim()
        val tool = rules.firstOrNull { it.second.containsMatchIn(t) }?.first ?: return null
        val args = if (tool == GuideTool.request_folder_access)
            mapOf("folder" to (Regex("""\b(downloads?|documents|pictures|music|movies|dcim)\b""").find(t)?.value?.replaceFirstChar { it.titlecase(Locale.ROOT) } ?: "Documents"))
        else emptyMap()
        return tool to args
    }
}
