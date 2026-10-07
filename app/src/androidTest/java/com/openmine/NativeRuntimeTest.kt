package com.openmine

import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.openmine.sandbox.OpenMineRuntime
import com.openmine.sandbox.PersistentSandboxShell
import com.openmine.sandbox.ProotExecutor
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
        fun command(value: String): String {
            idle()
            runtime.command(value)
            idle()
            check(runtime.status.value == "Command finished") { "${runtime.status.value}\n${runtime.output.value}" }
            return runtime.output.value
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
                assertTrue(persistent, persistent.contains("stdout=$cwd|works"))
                record("cwd_export_and_no_newline_output", persistent)

                val pipes = command("printf 'out-$nonce'; printf 'err-$nonce' >&2")
                assertTrue(pipes, pipes.contains("stdout=out-$nonce"))
                assertTrue(pipes, pipes.contains("stderr=err-$nonce"))
                record("both_output_pipes_drained", pipes)

                runtime.command("false")
                idle()
                assertTrue(runtime.status.value, runtime.status.value.startsWith("Command failed"))
                assertTrue(runtime.output.value, runtime.output.value.contains("exit_code=1"))
                record("nonzero_exit_reported", runtime.output.value)

                runtime.command("printf 'START_$nonce\\n'; sleep 45; printf 'SHOULD_NOT_RUN_$nonce'")
                val startDeadline = SystemClock.elapsedRealtime() + 15_000
                while (!runtime.terminalOutput.value.contains("\nSTART_$nonce\n") && runtime.busy.value && SystemClock.elapsedRealtime() < startDeadline) Thread.sleep(50)
                assertTrue("Long-running command never emitted actual output", runtime.terminalOutput.value.contains("\nSTART_$nonce\n"))
                val cancelled = SystemClock.elapsedRealtime()
                runtime.cancel()
                idle(15_000)
                assertEquals("Cancelled", runtime.status.value)
                assertTrue("Cancelled command reached its final printf", !runtime.terminalOutput.value.contains("\nSHOULD_NOT_RUN_$nonce\n"))
                evidence.put("cancelElapsedMillis", SystemClock.elapsedRealtime() - cancelled)
                record("actual_foreground_cancel", runtime.terminalOutput.value)
                val recovered = command("printf 'RECOVERED_$nonce'")
                assertTrue(recovered, recovered.contains("stdout=RECOVERED_$nonce"))
                record("command_after_cancel", recovered)

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
                    assertTrue(runtime.output.value, runtime.output.value.contains("stdout=TOOL_DONE_$nonce"))
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
