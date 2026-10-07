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
    private fun withShell(test: suspend (PersistentSandboxShell, File) -> Unit) = runBlocking {
        val directory = Files.createTempDirectory("openmine-shell-").toFile()
        val executor = ProotExecutor("unused", "unused", directory.path, directory.path, directory.path,
            processStarter = { args, _, _ -> ProcessBuilder("/bin/bash", "--noprofile", "--norc", "-c", args.last()).directory(directory).start() })
        val shell = PersistentSandboxShell(executor, directory.path, directory.path, directory.path)
        try { withTimeout(15000) { test(shell, directory) } }
        finally { shell.reset(); directory.deleteRecursively() }
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

    @Test fun explicitExitStartsFreshSessionAndRestoresLastKnownCwd() = withShell { shell, directory ->
        shell.run("mkdir -p child; cd child", 3)
        assertEquals(true, shell.run("exit 9", 3)["shell_died"])
        assertEquals("${directory.path}/child", shell.run("pwd", 3)["stdout"])
    }
}
