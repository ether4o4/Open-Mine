package com.openmine

import com.openmine.sandbox.PersistentSandboxShell
import com.openmine.sandbox.ProotExecutor
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Real local Bash subprocess tests of framing/lifecycle, NOT proof of Android PRoot execution. */
class PersistentSandboxShellTest {
    private fun withShell(delayStopDelivery: Boolean = false, test: suspend (PersistentSandboxShell, File) -> Unit) = runBlocking {
        val directory = Files.createTempDirectory("openmine-shell-").toFile()
        // Model a tracer that has accepted STOP but has not stopped the tracee yet.
        // The pause after KILL gives a surviving parent time to resume after wait().
        val signalWrapper = if (delayStopDelivery) """
            kill() {
                if [ "${'$'}1" = -STOP ]; then return 0; fi
                builtin kill "${'$'}@"
                result=${'$'}?
                if [ "${'$'}1" = -KILL ]; then sleep 0.05; fi
                return "${'$'}result"
            }
        """.trimIndent() + "\n" else ""
        val executor = ProotExecutor("unused", "unused", directory.path, directory.path, directory.path,
            processStarter = { args, _, _ -> ProcessBuilder("/bin/bash", "--noprofile", "--norc", "-c", signalWrapper + args.last()).directory(directory).start() })
        val shell = PersistentSandboxShell(executor, directory.path, directory.path, directory.path)
        try { withTimeout(15000) { test(shell, directory) } }
        finally { shell.reset()?.join(); directory.deleteRecursively() }
    }

    @Test fun capturesOutputWithoutNewlineBeforeCommandCompletes() = withShell { shell, _ ->
        repeat(12) {
            val result = shell.run("printf stdout-$it; printf stderr-$it >&2", 3)
            assertEquals(true, result["success"])
            assertEquals("stdout-$it", result["stdout"])
            assertEquals("stderr-$it", result["stderr"])
        }
    }

    @Test fun preservesDirectoryAndExportsButReportsNonzeroExit() = withShell { shell, directory ->
        assertEquals(true, shell.run("mkdir -p child; cd child; export OPENMINE_TEST=works", 3)["success"])
        val result = shell.run("printf '%s:%s' \"\$PWD\" \"\$OPENMINE_TEST\"; false", 3)
        assertEquals("${directory.path}/child:works", result["stdout"])
        assertEquals("${directory.path}/child", result["cwd"])
        assertEquals(1, result["exit_code"])
        assertEquals(false, result["success"])
        assertFalse(directory.listFiles()!!.any { it.name.startsWith(".openmine_cmd_") })
    }

    @Test fun completionSurvivesUserOutputRedirection() = withShell { shell, directory ->
        val result = shell.run("exec > '${directory.path}/out' 2> '${directory.path}/err'; printf redirected", 3)
        assertEquals(true, result["success"])
        assertEquals("redirected", File(directory, "out").readText())
        assertEquals(true, shell.run("true", 3)["success"])
    }

    @Test fun boundsSingleHugeOutputLineWithoutLosingCompletion() = withShell { shell, _ ->
        val result = shell.run("head -c 120000 /dev/zero | tr '\\000' x", 3)
        assertEquals(true, result["success"])
        assertEquals(15000, (result["stdout"] as String).length)
        assertEquals("next", shell.run("printf next", 3)["stdout"])
    }

    @Test fun cancelledRunCleansStagingAndCannotContaminateReplacementSession() = withShell { shell, directory ->
        val started = CompletableDeferred<Unit>()
        val job = CoroutineScope(currentCoroutineContext()).launch {
            shell.run("printf 'started\\n'; sleep 20; printf obsolete", 30, onStdout = { started.complete(Unit) })
        }
        started.await()
        job.cancelAndJoin()
        assertFalse(directory.listFiles()!!.any { it.name.startsWith(".openmine_cmd_") })
        repeat(3) { assertEquals("fresh-$it", shell.run("printf fresh-$it", 3)["stdout"]) }
    }

    @Test fun timeoutIsFailureAndNextCommandWorks() = withShell { shell, _ ->
        val result = shell.run("sleep 20", 1)
        assertEquals(false, result["success"])
        assertEquals(true, result["timed_out"])
        assertEquals(124, result["exit_code"])
        assertEquals("recovered", shell.run("printf recovered", 3)["stdout"])
    }

    @Test fun cancellingStopsLaterStatementsAndGrandchildrenButNotOtherJobs() = withShell { shell, directory ->
        val unrelated = ProcessBuilder("/bin/bash", "-c", "sleep 20").start()
        val ready = CompletableDeferred<Unit>()
        shell.run("export OPENMINE_KEEP='café 日本語'; mkdir -p kept; cd kept", 3)
        val job = CoroutineScope(currentCoroutineContext()).async {
            shell.run("(sleep 2; touch '${directory.path}/grandchild') & printf 'ready\\n'; sleep 20; touch '${directory.path}/later'; printf forbidden", 30,
                onStdout = { if (it == "ready") ready.complete(Unit) })
        }
        try {
            ready.await()
            shell.cancelForeground()
            val result = job.await()
            assertEquals(false, result["success"])
            assertEquals(true, result["cancelled"])
            delay(2500)
            assertFalse(File(directory, "later").exists())
            assertFalse(File(directory, "grandchild").exists())
            assertFalse(result["stdout"].toString().contains("forbidden"))
            assertTrue("Cancellation killed unrelated work", unrelated.isAlive)
            val recovery = shell.run("printf '%s:%s' \"\$PWD\" \"\$OPENMINE_KEEP\"", 3)
            assertEquals("${directory.path}/kept:café 日本語", recovery["stdout"])
        } finally { unrelated.destroyForcibly() }
    }

    @Test fun timeoutPreventsLaterFilesystemSideEffects() = withShell { shell, directory ->
        val result = shell.run("sleep 20; touch '${directory.path}/forbidden'", 1)
        assertEquals(124, result["exit_code"])
        assertFalse(File(directory, "forbidden").exists())
    }

    @Test fun cancellationCannotResumeParentsWhenStopDeliveryIsDelayed() = withShell(delayStopDelivery = true) { shell, directory ->
        val ready = CompletableDeferred<Unit>()
        val job = CoroutineScope(currentCoroutineContext()).async {
            shell.run("(sleep 20; printf escaped > '${directory.path}/descendant') & " +
                "printf 'ready\\n'; sleep 20; printf escaped > '${directory.path}/later'", 30,
                onStdout = { if (it == "ready") ready.complete(Unit) })
        }
        ready.await()
        shell.cancelForeground()
        assertEquals(true, job.await()["cancelled"])
        assertFalse("Killing the foreground child resumed its parent", File(directory, "later").exists())
        assertFalse("Killing a grandchild resumed its parent", File(directory, "descendant").exists())
        assertEquals("recovered", shell.run("printf recovered", 3)["stdout"])
    }

    @Test fun explicitExitStartsFreshSessionAndRestoresLastKnownCwd() = withShell { shell, directory ->
        shell.run("mkdir -p child; cd child", 3)
        assertEquals(true, shell.run("exit 9", 3)["shell_died"])
        assertEquals("${directory.path}/child", shell.run("pwd", 3)["stdout"])
    }
    @Test fun backgroundChildSurvivesCompletedAndSubsequentCommands() = withShell { shell, _ ->
        val result = shell.run("sleep 30 > /dev/null 2>&1 & echo \$!", 3)
        val pid = (result["stdout"] as String).trim().toInt()
        try {
            assertEquals(true, result["success"])
            repeat(3) { assertEquals(true, shell.run("kill -0 $pid; printf alive", 3)["success"]) }
        } finally { shell.run("kill $pid; wait $pid 2>/dev/null; true", 3) }
    }


    @Test fun nohupCompletesAfterLaunchingCommandReturns() = withShell { shell, directory ->
        val marker = File(directory, "background-result")
        assertEquals(true, shell.run("nohup sh -c 'sleep 1; printf PERSISTENCE_OK > \"${marker.path}\"' >/dev/null 2>&1 </dev/null &", 3)["success"])
        repeat(3) { assertEquals("foreground", shell.run("printf foreground", 3)["stdout"]) }
        withTimeout(5000) { while (!marker.exists()) delay(50) }
        assertEquals("PERSISTENCE_OK", marker.readText())
    }

    @Test fun cancelStopsJobControlChildrenBeforeLaterStatements() = withShell { shell, directory ->
        val ready = CompletableDeferred<Unit>()
        val job = CoroutineScope(currentCoroutineContext()).async {
            shell.run("set -m; (sleep 2; touch '${directory.path}/escaped') & printf 'ready\\n'; sleep 20; touch '${directory.path}/later'", 30,
                onStdout = { if (it == "ready") ready.complete(Unit) })
        }
        ready.await()
        shell.cancelForeground()
        assertEquals(true, job.await()["cancelled"])
        delay(2500)
        assertFalse(File(directory, "escaped").exists())
        assertFalse(File(directory, "later").exists())
    }

}
