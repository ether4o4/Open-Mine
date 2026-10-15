package com.openmine

import com.openmine.sandbox.TerminalResultFormatter
import org.junit.Assert.*
import org.junit.Test

class TerminalResultFormatterTest {
    @Test fun rendersUnicodeAndExitStatusWithoutMapSerialization() {
        val text = TerminalResultFormatter.format(mapOf(
            "success" to true, "stdout" to "Hello 世界 👋", "stderr" to "", "exit_code" to 0, "cwd" to "/root/My project",
        ))
        assertEquals("Hello 世界 👋\n\nCompleted · exit 0 · /root/My project\n", text)
        assertFalse(text.contains("success="))
    }

    @Test fun keepsErrorAndActualFailureStatusVisible() {
        val text = TerminalResultFormatter.format(mapOf(
            "success" to false, "stdout" to "Installing…\n", "stderr" to "Permission denied", "exit_code" to 3, "cwd" to "/root",
        ))
        assertTrue(text.contains("Installing…\n"))
        assertTrue(text.contains("Standard error\nPermission denied\n"))
        assertTrue(text.contains("Failed · exit 3 · /root"))
    }

    @Test fun distinguishesTimeoutCancellationAndSessionDeath() {
        val timeout = TerminalResultFormatter.format(mapOf("timed_out" to true, "cancelled" to true, "success" to false, "exit_code" to 124))
        assertTrue(timeout.contains("Time limit reached · exit 124"))
        val cancelled = TerminalResultFormatter.format(mapOf("cancelled" to true, "shell_died" to true, "exit_code" to 130))
        assertTrue(cancelled.contains("Cancelled · exit 130"))
        assertTrue(cancelled.contains("the next command starts a new session"))
    }
}
