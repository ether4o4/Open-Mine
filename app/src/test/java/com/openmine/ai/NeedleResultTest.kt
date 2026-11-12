package com.openmine.ai

import org.junit.Assert.*
import org.junit.Test

class NeedleResultTest {
    private val tools = GuideTool.names
    // Verbatim Cactus 4.1.0 output shapes recorded from the real Needle 3 run on this commit's tool set.
    private val confident = """{"type":"call","success":true,"error":null,"function_calls":[{"name":"install_runtime","arguments":{}}],"reasoning":"'set up the terminal' -> install_runtime","confidence":0.6980}"""
    private val withArgs = """{"type":"call","success":true,"function_calls":[{"name":"request_folder_access","arguments":{"folder":"Downloads","purpose":"projects"}}],"confidence":0.34}"""
    private val unsure = """{"type":"call","success":true,"function_calls":[{"name":"run_diagnostics","arguments":{}}],"reasoning":"'hello' is conversational","confidence":0.2769}"""
    private val none = """{"type":"call","success":true,"function_calls":[],"confidence":0.08}"""

    @Test fun confidentCallsAreAccepted() {
        val r = NeedleResult.parse(confident, tools)
        assertEquals("install_runtime", r.tool); assertTrue(NeedleResult.isConfident(r))
    }
    @Test fun argumentsArePreserved() {
        val r = NeedleResult.parse(withArgs, tools)
        assertEquals("Downloads", r.arguments.getString("folder")); assertTrue(NeedleResult.isConfident(r))
    }
    @Test fun lowConfidenceAndEmptyCallsAreHandedOff() {
        assertFalse(NeedleResult.isConfident(NeedleResult.parse(unsure, tools)))
        assertNull(NeedleResult.parse(none, tools).tool)
    }
    @Test fun unknownToolsErrorsAndGarbageNeverBecomeActions() {
        assertNull(NeedleResult.parse("""{"function_calls":[{"name":"rm_everything","arguments":{}}],"confidence":0.99}""", tools).tool)
        assertNull(NeedleResult.parse("ERROR: output buffer is too small", tools).tool)
        assertNull(NeedleResult.parse("not json", tools).tool)
    }
}
