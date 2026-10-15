package com.openmine

import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.openmine.sandbox.OpenMineRuntime
import com.openmine.sandbox.PersistentSandboxShell
import com.openmine.sandbox.ProotExecutor
import com.openmine.sandbox.RuntimeService
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Requires a deliberately selected emulator/device and real network/bootstrap; never silently skips. */
@RunWith(AndroidJUnit4::class)
@LargeTest
class NativeRuntimeTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test(timeout = 900_000)
    fun actualAndroidShellSetupPersistenceCancellationAndRecovery() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        check(args.getString("nativeRuntime") == "true") {
            "Native runtime verification requires explicit -e nativeRuntime true and real bootstrap networking."
        }
        val context = instrumentation.targetContext
        val evidenceFile = File(context.getExternalFilesDir(null), "native-runtime-evidence.json")
        val evidence = JSONObject()
            .put("status", "running")
            .put("commit", args.getString("openMineTestedCommit").also {
                require(it != null && it.matches(Regex("[a-f0-9]{40}"))) { "Exact tested source commit is required" }
            })
            .put("package", context.packageName)
            .put("api", Build.VERSION.SDK_INT)
            .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("fingerprint", Build.FINGERPRINT)
            .put("startedAtEpochMillis", System.currentTimeMillis())
            .put("conditions", "Real bundled PRoot and downloaded Alpine in Android app storage. No mocked shell. This test does not verify GGUF inference.")
        val runtime = OpenMineRuntime.get(context)
        val steps = JSONArray()
        evidence.put("steps", steps)
        fun record(name: String, output: String) {
            steps.put(JSONObject().put("name", name).put("status", "passed").put("actualOutput", output.takeLast(20000)))
            evidenceFile.writeText(evidence.toString(2) + "\n")
        }
        fun idle(timeoutMs: Long = 45_000) {
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            while (runtime.busy.value && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
            check(!runtime.busy.value) { "Operation did not finish: ${runtime.status.value}\n${runtime.output.value}" }
        }
        fun latestResult(value: String): String {
            // Match only this submission: echoed commands and earlier results must
            // not satisfy output assertions when the current command emitted nothing.
            val transcript = runtime.output.value
            val prompt = "\n$ $value\n"
            val start = transcript.lastIndexOf(prompt)
            check(start >= 0) { "Command is missing from the terminal transcript: $transcript" }
            return transcript.substring(start + prompt.length)
        }
        fun command(value: String): String {
            idle()
            check(runtime.command(value)) { "Runtime rejected the test command: ${runtime.status.value}" }
            idle()
            check(runtime.status.value == "Command finished") { "${runtime.status.value}\n${runtime.output.value}" }
            return latestResult(value)
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                idle()
                val started = SystemClock.elapsedRealtime()
                runtime.setup()
                idle(600_000)
                assertTrue("Actual bootstrap/probe failed: ${runtime.status.value}\n${runtime.output.value}", runtime.shellHealthy.value)
                assertTrue(runtime.ready)
                val base = File(context.filesDir, "linux-sandbox")
                evidence.put("setupElapsedMillis", SystemClock.elapsedRealtime() - started)
                    .put("rootfsRelease", File(base, "rootfs/etc/alpine-release").readText().trim())
                    .put("bundledProotSha256", MessageDigest.getInstance("SHA-256").digest(File(context.applicationInfo.nativeLibraryDir, "libproot.so").readBytes()).joinToString("") { "%02x".format(it) })
                record("real_bootstrap_and_bash_readiness_probe", runtime.status.value + "\n" + runtime.output.value)

                val nonce = UUID.randomUUID().toString().replace("-", "")
                val cwd = "/root/openmine-runtime-check-$nonce"
                command("mkdir -p '$cwd'; cd '$cwd'; export OPENMINE_NATIVE_TEST=works; printf retained > continuity.txt")
                val persistent = command("printf '%s|%s' \"\$PWD\" \"\$OPENMINE_NATIVE_TEST\"")
                assertEquals("$cwd|works\n\nCompleted · exit 0 · $cwd\n", persistent)
                record("cwd_export_and_no_newline_output", persistent)

                val pipes = command("printf 'out-$nonce'; printf 'err-$nonce' >&2")
                assertEquals("out-$nonce\n\nStandard error\nerr-$nonce\n\nCompleted · exit 0 · $cwd\n", pipes)
                record("both_output_pipes_drained", pipes)

                assertTrue("Runtime rejected the nonzero-exit test", runtime.command("false"))
                idle()
                assertTrue(runtime.status.value, runtime.status.value.startsWith("Command failed"))
                assertEquals("\nFailed · exit 1 · $cwd\n", latestResult("false"))
                record("nonzero_exit_reported", runtime.output.value)

                val laterStatement=File(base,"home/openmine-runtime-check-$nonce/later.txt")
                val descendant=File(base,"home/openmine-runtime-check-$nonce/descendant.txt")
                runtime.command("(sleep 4; printf escaped > '$cwd/descendant.txt') & printf 'START_$nonce\\n'; sleep 45; printf escaped > '$cwd/later.txt'; printf 'SHOULD_NOT_RUN_$nonce'")
                val startDeadline = SystemClock.elapsedRealtime() + 15_000
                while (!runtime.terminalOutput.value.contains("\nSTART_$nonce\n") && runtime.busy.value && SystemClock.elapsedRealtime() < startDeadline) Thread.sleep(50)
                assertTrue("Long-running command never emitted actual output", runtime.terminalOutput.value.contains("\nSTART_$nonce\n"))
                val cancelled = SystemClock.elapsedRealtime()
                runtime.cancel()
                idle(15_000)
                assertEquals("Cancelled", runtime.status.value)
                assertTrue("Cancelled command reached its final printf", !runtime.terminalOutput.value.contains("\nSHOULD_NOT_RUN_$nonce\n"))
                Thread.sleep(4500)
                assertFalse("Cancelled submission executed a later statement",laterStatement.exists())
                assertFalse("Cancelled submission left a descendant running",descendant.exists())
                evidence.put("cancelElapsedMillis", SystemClock.elapsedRealtime() - cancelled)
                record("actual_foreground_cancel", runtime.terminalOutput.value)
                val recovered = command("printf 'RECOVERED_$nonce'")
                assertEquals("RECOVERED_$nonce\n\nCompleted · exit 0 · $cwd\n", recovered)
                record("command_after_cancel", recovered)
                val unicode=command("export OPENMINE_UNICODE='日本語 café'; printf '%s' \"\$OPENMINE_UNICODE\"")
                assertEquals("日本語 café\n\nCompleted · exit 0 · $cwd\n", unicode)
                assertEquals("日本語 café\n\nCompleted · exit 0 · $cwd\n", command("printf '%s' \"\$OPENMINE_UNICODE\""))
                record("unicode_and_export_persistence",unicode)

                assertEquals(cwd, File(base, "terminal-cwd.txt").readText())
                assertTrue(File(base, "terminal-transcript.txt").readText().contains("RECOVERED_$nonce"))
                // Construct another real PRoot shell using persisted cwd exactly as
                // a process recreation does. This is a shell restart, not an app-restart claim.
                val executor = ProotExecutor(File(context.applicationInfo.nativeLibraryDir, "libproot.so").path,
                    base.path, File(base, "rootfs").path, File(base, "home").path, File(base, "tmp").path)
                val recreated = PersistentSandboxShell(executor, File(base, "tmp").path, File(base, "terminal-cwd.txt").readText())
                val restart = try { runBlocking { recreated.run("printf '%s|' \"\$PWD\"; cat continuity.txt", 10) } }
                    finally { recreated.reset() }
                assertEquals(true, restart["success"])
                assertEquals("$cwd|retained", restart["stdout"])
                record("persisted_cwd_and_file_used_by_new_shell_process", restart.toString())

                runtime.verifyShell(); idle()
                assertTrue(runtime.status.value, runtime.shellHealthy.value)
                record("explicit_shell_reprobe", runtime.status.value)

                // Exercise the accepted IME path against the installed, real Linux
                // runtime. Use semantic actions so font scaling and panel size do not
                // turn this into a coordinate-dependent keyboard test.
                run {
                    val navigation = context.getSharedPreferences("open_mine", Context.MODE_PRIVATE)
                    val runtimeUi = context.getSharedPreferences("open_mine_runtime_ui", Context.MODE_PRIVATE)
                    val originalScreen = navigation.all["screen"] as? Int
                    val originalRoute = navigation.getString("route", null)
                    val originalAnimations = navigation.all["animations"] as? Boolean
                    val originalDraft = runtimeUi.getString("command_draft", null)
                    val originalHistory = runtimeUi.getString("command_history", null)
                    val keyboardCommand = "printf 'IME_$nonce'"
                    fun awaitCommandField() {
                        compose.waitUntil(15_000) {
                            compose.onAllNodesWithTag("utility-panel-9").fetchSemanticsNodes().isNotEmpty()
                        }
                        // The input can initially be outside the LazyColumn viewport.
                        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("shell-command"))
                    }
                    fun assertCommandField(value: String) {
                        compose.onNodeWithTag("shell-command").assert(
                            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(value)))
                    }
                    try {
                        navigation.edit().putInt("screen", 9).putString("route", "").putBoolean("animations", false).commit()
                        scenario.recreate()
                        awaitCommandField()
                        idle()
                        compose.onNodeWithTag("shell-command").performScrollTo().assertIsEnabled()
                            .performTextReplacement(keyboardCommand)
                        compose.onNodeWithTag("shell-command").performImeAction()
                        idle()
                        assertEquals(runtime.output.value, "Command finished", runtime.status.value)
                        assertEquals("IME_$nonce\n\nCompleted · exit 0 · $cwd\n", latestResult(keyboardCommand))
                        compose.waitForIdle()
                        assertCommandField("")
                        assertEquals("Accepted submission did not persist the cleared draft", "", runtimeUi.getString("command_draft", null))
                        val savedHistory = JSONArray(runtimeUi.getString("command_history", "[]"))
                        assertEquals(keyboardCommand, savedHistory.getString(savedHistory.length() - 1))
                        compose.onNodeWithTag("shell-history-up").performScrollTo().assertIsEnabled().performClick()
                        assertCommandField(keyboardCommand)
                        compose.onNodeWithTag("shell-history-down").performScrollTo().assertIsEnabled().performClick()
                        assertCommandField("")

                        scenario.recreate()
                        awaitCommandField()
                        assertCommandField("")
                        compose.onNodeWithTag("shell-history-up").performScrollTo().assertIsEnabled().performClick()
                        assertCommandField(keyboardCommand)
                        compose.onNodeWithTag("shell-history-down").performScrollTo().assertIsEnabled().performClick()
                        assertCommandField("")
                        record("keyboard_submit_executes_clears_and_recalls_persisted_history", latestResult(keyboardCommand))
                    } finally {
                        try {
                            if (runtime.busy.value) { runtime.cancel(); idle(15_000) }
                        } finally {
                            runtimeUi.edit().apply {
                                if (originalDraft == null) remove("command_draft") else putString("command_draft", originalDraft)
                                if (originalHistory == null) remove("command_history") else putString("command_history", originalHistory)
                            }.commit()
                            navigation.edit().apply {
                                if (originalScreen == null) remove("screen") else putInt("screen", originalScreen)
                                if (originalRoute == null) remove("route") else putString("route", originalRoute)
                                if (originalAnimations == null) remove("animations") else putBoolean("animations", originalAnimations)
                            }.commit()
                            scenario.recreate()
                        }
                    }
                }

                // This is a real guest PID, not just the manager's remembered state.
                // The managed shell becomes sleep, retaining its PID and process group.
                val managedPidFile = File(base, "home/openmine-runtime-check-$nonce/managed.pid")
                var managedId: String? = null
                try {
                    val managed = runBlocking {
                        withTimeout(15_000) {
                            runtime.startManagedProcess("printf '%s\\n' \"\$\$\" > '$cwd/managed.pid'; exec sleep 300")
                        }
                    }
                    managedId = managed.id
                    val pidDeadline = SystemClock.elapsedRealtime() + 10_000
                    var managedPid: Int? = null
                    while (managedPid == null && SystemClock.elapsedRealtime() < pidDeadline) {
                        managedPid = runCatching { managedPidFile.readText().trim().toIntOrNull() }.getOrNull()
                        if (managedPid == null) Thread.sleep(50)
                    }
                    val pid = checkNotNull(managedPid) { "Managed command did not write its actual PID: ${runtime.managedProcess(managed.id)}" }
                    check(pid > 1) { "Unexpected managed PID: $pid" }
                    fun assertManagedAlive(stage: String): String {
                        assertEquals("Managed PID changed during $stage", pid.toString(), managedPidFile.readText().trim())
                        val probe = executor.execute("kill -0 $pid 2>/dev/null && printf 'ALIVE_$pid'", 5)
                        assertEquals("Managed process died during $stage: $probe", true, probe["success"])
                        assertEquals("ALIVE_$pid", probe["stdout"])
                        assertTrue("Foreground service stopped during $stage", RuntimeService.running.value)
                        return probe.toString()
                    }
                    assertManagedAlive("startup")
                    repeat(3) { command("printf 'UNRELATED_${nonce}_$it'") }
                    record("managed_pid_survives_unrelated_terminal_commands", assertManagedAlive("unrelated commands"))

                    scenario.moveToState(Lifecycle.State.CREATED)
                    repeat(3) {
                        Thread.sleep(250)
                        assertManagedAlive("Activity stopped, probe $it")
                    }
                    record("managed_pid_and_service_survive_activity_stop", assertManagedAlive("Activity stopped"))

                    val foregroundMarker = "MANAGED_CANCEL_$nonce"
                    runtime.command("printf '$foregroundMarker\\n'; sleep 45; printf 'UNEXPECTED_${foregroundMarker}'")
                    val foregroundDeadline = SystemClock.elapsedRealtime() + 15_000
                    while (!runtime.terminalOutput.value.contains("\n$foregroundMarker\n") && runtime.busy.value && SystemClock.elapsedRealtime() < foregroundDeadline) Thread.sleep(50)
                    assertTrue("Separate terminal command did not start while Activity was stopped", runtime.terminalOutput.value.contains("\n$foregroundMarker\n"))
                    runtime.cancel()
                    idle(15_000)
                    assertEquals("Cancelled", runtime.status.value)
                    assertFalse("Cancelled terminal command completed unexpectedly", runtime.terminalOutput.value.contains("\nUNEXPECTED_${foregroundMarker}"))
                    record("managed_pid_survives_other_command_cancellation", assertManagedAlive("terminal cancellation"))

                    assertTrue("Managed process was not available to stop", runtime.stopManagedProcess(managed.id))
                    val stopDeadline = SystemClock.elapsedRealtime() + 10_000
                    var stoppedProbe: Map<String, Any>
                    do {
                        stoppedProbe = executor.execute("kill -0 $pid 2>/dev/null", 3)
                        if (stoppedProbe["success"] != true) break
                        Thread.sleep(50)
                    } while (SystemClock.elapsedRealtime() < stopDeadline)
                    assertEquals("Managed Stop left the actual PID alive: $pid, $stoppedProbe", false, stoppedProbe["success"])
                    assertEquals("A failed probe must not count as process termination", 1, stoppedProbe["exit_code"])
                    record("managed_stop_terminates_actual_pid", "pid=$pid; $stoppedProbe")
                } finally {
                    try {
                        scenario.moveToState(Lifecycle.State.RESUMED)
                    } finally {
                        try {
                            if (runtime.busy.value) { runtime.cancel(); idle(15_000) }
                        } finally {
                            try { managedId?.let { runtime.stopManagedProcess(it) } }
                            finally { managedPidFile.delete() }
                        }
                    }
                }

                val reviewedFile = File(base, "home/openmine-runtime-check-$nonce/reviewed.txt")
                val reviewedCommand = "printf 'APPROVED_$nonce' > '$cwd/reviewed.txt'; printf 'TOOL_DONE_$nonce'"
                val tool = OpenMineObjectFormat.template(
                    type = "TOOL", title = "Runtime approval $nonce",
                    summary = "Test-only procedure writes one nonce file after explicit review.", tags = "test, runtime",
                    source = "Android native runtime integration test", purpose = "Verify user-reviewed tool execution",
                    facts = "NONE", procedure = reviewedCommand, constraints = "Only this test-owned directory",
                    examples = "NONE", keywords = "runtime, review", aliases = "NONE", triggers = "NONE",
                    project = "NONE", model = "NONE", skill = "NONE", tool = "NONE", mission = "NONE",
                    query = "runtime review", whenText = "Explicit Android test run", exclude = "NONE",
                    verification = "DRAFT", verificationSource = "Android test", notes = "Not an inference assertion",
                )
                val preferences = context.getSharedPreferences("open_mine", Context.MODE_PRIVATE)
                val originalRoute = preferences.getString("route", null)
                val originalOpenedRecord = preferences.getString("opened_record", null)
                var savedTool: StrictObject? = null
                try {
                    val saved = OpenMineObjectStore.create(context, tool)
                    assertTrue(saved.errors.joinToString(), saved.valid)
                    savedTool = saved.normalized!!
                    assertFalse("Importing a tool must not execute it", reviewedFile.exists())
                    preferences.edit().putString("route", "tool").putString("opened_record", savedTool.id).commit()
                    scenario.recreate()
                    compose.waitUntil(15_000) {
                        compose.onAllNodesWithText("Review exact command").fetchSemanticsNodes().isNotEmpty()
                    }
                    compose.onNodeWithText("Review exact command").performScrollTo().assertIsEnabled()
                    assertFalse("Opening a tool must not execute it", reviewedFile.exists())
                    compose.onNodeWithText("Review exact command").performClick()
                    compose.onNode(hasText(reviewedCommand) and hasAnyAncestor(isDialog())).assertExists()
                    assertFalse("Opening the review must not execute the command", reviewedFile.exists())
                    compose.onNode(hasText("Cancel") and hasAnyAncestor(isDialog())).performClick()
                    compose.waitForIdle()
                    assertFalse("Denying review must not execute the procedure", reviewedFile.exists())
                    assertFalse("Denying review must not start a runtime operation", runtime.busy.value)
                    record("reviewed_tool_import_open_and_deny_do_not_execute", "No nonce file exists after import, opening, exact-command review, and Cancel.")

                    compose.onNodeWithText("Review exact command").performScrollTo().performClick()
                    compose.onNode(hasText(reviewedCommand) and hasAnyAncestor(isDialog())).assertExists()
                    compose.onNode(hasText("Run reviewed command") and hasAnyAncestor(isDialog())).performClick()
                    idle()
                    assertEquals(runtime.output.value, "Command finished", runtime.status.value)
                    assertTrue("Approved real command did not produce its file", reviewedFile.isFile)
                    assertEquals("APPROVED_$nonce", reviewedFile.readText())
                    assertEquals("TOOL_DONE_$nonce\n\nCompleted · exit 0 · $cwd\n", latestResult(reviewedCommand))
                    compose.onNodeWithText("Command submitted. Inspect its exit code and output below.").assertExists()
                    record("reviewed_tool_approval_executes_real_shell_procedure", runtime.output.value + "\nActual file: " + reviewedFile.readText())
                    compose.onNodeWithText("Back to workspace").performScrollTo().performClick()
                    compose.waitForIdle()
                } finally {
                    if (runtime.busy.value) { runtime.cancel(); idle(15_000) }
                    savedTool?.let { OpenMineObjectStore.delete(context, it) }
                    reviewedFile.delete()
                    preferences.edit().apply {
                        if (originalRoute == null) remove("route") else putString("route", originalRoute)
                        if (originalOpenedRecord == null) remove("opened_record") else putString("opened_record", originalOpenedRecord)
                    }.commit()
                }
                command("rm -f '$cwd/continuity.txt'; cd /root; rmdir '$cwd'")
            }
            evidence.put("status", "passed")
        } catch (failure: Throwable) {
            evidence.put("status", "failed")
                .put("failure", "${failure.javaClass.name}: ${failure.message}")
                .put("runtimeStatus", runtime.status.value)
                .put("runtimeOutput", runtime.output.value.takeLast(32000))
            throw failure
        } finally {
            if (runtime.busy.value) runtime.cancel()
            evidence.put("finishedAtEpochMillis", System.currentTimeMillis())
            evidenceFile.writeText(evidence.toString(2) + "\n")
        }
    }
}
