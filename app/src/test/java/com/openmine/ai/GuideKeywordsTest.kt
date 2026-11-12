package com.openmine.ai

import org.junit.Assert.*
import org.junit.Test

class GuideKeywordsTest {
    @Test fun setupStepsAreMatched() {
        assertEquals(GuideTool.install_runtime, GuideKeywords.suggest("set up the terminal for me")?.first)
        assertEquals(GuideTool.install_runtime, GuideKeywords.suggest("could you get linux going on here")?.first)
        assertEquals(GuideTool.download_chat_model, GuideKeywords.suggest("download the AI model")?.first)
        assertEquals(GuideTool.download_chat_model, GuideKeywords.suggest("I want to chat offline without internet")?.first)
        assertEquals(GuideTool.import_model_file, GuideKeywords.suggest("I already have a gguf file in my downloads, add it")?.first)
        assertEquals(GuideTool.run_diagnostics, GuideKeywords.suggest("it crashed")?.first)
    }
    @Test fun folderRequestsCarryTheNamedFolder() {
        val (tool, args) = GuideKeywords.suggest("let it use my Downloads folder for projects")!!
        assertEquals(GuideTool.request_folder_access, tool); assertEquals("Downloads", args["folder"])
        assertEquals("Documents", GuideKeywords.suggest("pick a folder for my work")!!.second["folder"])
    }
    @Test fun chatAndCommandsAreNotGuessed() {
        for (text in listOf("hello how are you", "thanks!", "what is a GGUF format?".replace("GGUF", "file"), "tell me a joke", "install git and python", "remember my editor is vim"))
            assertNull(text, GuideKeywords.suggest(text))
    }
    @Test fun everySuggestionValidates() {
        listOf("set up the terminal", "download lfm3", "use my Documents folder", "why isn't it working", "import a gguf").forEach { t ->
            val (tool, args) = GuideKeywords.suggest(t)!!
            GuideActions.validate(tool.name, org.json.JSONObject(args))
        }
    }
}
