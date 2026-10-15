package com.openmine

import org.junit.Assert.*
import org.junit.Test

class ShellCommandHistoryTest {
    @Test fun recallRestoresUnsubmittedDraftAndStopsAtEnds() {
        val history = ShellCommandHistory(listOf("pwd", "ls"))
        assertEquals("ls", history.previous("unfinished"))
        assertEquals("pwd", history.previous("ls"))
        assertEquals("pwd", history.previous("pwd"))
        assertEquals("ls", history.next("pwd"))
        assertEquals("unfinished", history.next("ls"))
        assertEquals("unfinished", history.next("unfinished"))
    }
    @Test fun submittingRecalledCommandReturnsCursorToNewestAndBoundsStorage() {
        val history = ShellCommandHistory((0..120).map { "echo $it" })
        assertEquals(100, history.entries.size)
        history.previous("")
        history.record("pwd")
        assertEquals(100, history.position)
        assertEquals("pwd", history.previous(""))
        assertEquals("", history.next("pwd"))
        history.record("pwd")
        assertEquals(1, history.entries.count { it == "pwd" })
        val restored = ShellCommandHistory(history.entries)
        assertEquals("pwd", restored.previous(""))
    }
}
