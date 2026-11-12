package com.openmine.ai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GuideToolsTest {
    @Test fun schemasExportForBothRuntimes() {
        val cactus = JSONArray(GuideTool.cactusJson())
        assertEquals(GuideTool.entries.size, cactus.length())
        assertEquals("command", cactus.getJSONObject(GuideTool.run_command.ordinal).getJSONObject("parameters").getJSONArray("required").getString(0))
        val openAi = GuideTool.openAiJson()
        assertEquals("function", openAi.getJSONObject(0).getString("type"))
    }
    @Test fun validationRejectsUnknownToolsExtraArgumentsAndWrongTypes() {
        assertThrows(IllegalArgumentException::class.java) { GuideActions.validate("shell", JSONObject()) }
        assertThrows(IllegalArgumentException::class.java) { GuideActions.validate("run_command", JSONObject().put("command", "ls").put("sudo", "yes")) }
        assertThrows(IllegalArgumentException::class.java) { GuideActions.validate("run_command", JSONObject().put("command", 5)) }
        assertThrows(IllegalArgumentException::class.java) { GuideActions.validate("run_command", JSONObject()) }
        assertThrows(IllegalArgumentException::class.java) { GuideActions.validate("run_command", JSONObject().put("command", "echo \u0007")) }
    }
    @Test fun summaryShowsTheExactCommand() {
        val a = GuideActions.validate("run_command", JSONObject().put("command", "apk add git python3"), "call-1")
        assertTrue(a.summary.endsWith("apk add git python3")); assertEquals("call-1", a.callId)
    }
    private fun action(cmd: String) = GuideActions.validate("run_command", JSONObject().put("command", cmd))

    @Test fun tutorialAlwaysAsksAndReadOnlyNeverAsks() {
        assertTrue(ApprovalPolicy.needsApproval(action("apk add git"), AutonomyMode.FULL_AUTONOMY, tutorialComplete = false))
        assertFalse(ApprovalPolicy.needsApproval(GuideActions.validate("run_diagnostics", JSONObject()), AutonomyMode.ASK_EVERY_TIME, false))
        assertFalse(ApprovalPolicy.needsApproval(GuideActions.validate("search_knowledge", JSONObject().put("query", "x")), AutonomyMode.ASK_EVERY_TIME, false))
    }
    @Test fun autonomyRunsOrdinaryCommandsButDestructiveOnesStillAsk() {
        assertFalse(ApprovalPolicy.needsApproval(action("apk add git python3"), AutonomyMode.FULL_AUTONOMY, true))
        assertTrue(ApprovalPolicy.needsApproval(action("apk add git"), AutonomyMode.ASK_EVERY_TIME, true))
        for (bad in listOf("rm -rf /", "rm -rf ~", "rm -fr \$HOME", "mkfs.ext4 /dev/x", "dd if=/dev/zero of=/root/x", ":(){ :|:& };:", "reboot"))
            assertTrue(bad, ApprovalPolicy.needsApproval(action(bad), AutonomyMode.FULL_AUTONOMY, true))
        assertFalse(ApprovalPolicy.isDestructive("rm -rf build/"))
        assertTrue(ApprovalPolicy.needsApproval(GuideActions.validate("save_memory", JSONObject().put("title", "t").put("text", "x")), AutonomyMode.FULL_AUTONOMY, true))
    }
    @Test fun pickerToolsWaitForTheUser() {
        assertTrue(GuideTool.request_folder_access.waitsForPicker); assertTrue(GuideTool.import_model_file.waitsForPicker)
        assertFalse(GuideTool.run_command.waitsForPicker)
    }
}
