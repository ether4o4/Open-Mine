package com.openmine

import com.openmine.sandbox.RuntimeInstallation
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RuntimeInstallationTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun file(base: File, name: String, text: String = "installed") = File(base, name).apply {
        parentFile!!.mkdirs()
        writeText(text)
    }

    @Test fun installationReceiptSurvivesNewInspectionButNeedsItsFiles() {
        val base = temporary.newFolder()
        file(base, "rootfs/bin/bash")
        val busybox = file(base, "rootfs/bin/busybox")
        assertFalse(RuntimeInstallation.inspect(base).shellInstalled)
        file(base, "ready", "ready")
        assertTrue(RuntimeInstallation.inspect(base).shellInstalled)
        assertEquals(RuntimeInstallation.inspect(base), RuntimeInstallation.inspect(base))
        busybox.delete()
        assertFalse(RuntimeInstallation.inspect(base).shellInstalled)
    }

    @Test fun partialOrUnverifiedEngineIsNotShownAsSuccessfullyInstalled() {
        val base = temporary.newFolder()
        val engine = file(base, "home/.morsvitaest/llm/bin/llama-server")
        assertFalse(RuntimeInstallation.inspect(base).engineInstalled)
        file(base, "engine-ready", "execution verified")
        assertTrue(RuntimeInstallation.inspect(base).engineInstalled)
        engine.writeText("")
        assertFalse(RuntimeInstallation.inspect(base).engineInstalled)
        engine.delete()
        assertFalse(RuntimeInstallation.inspect(base).engineInstalled)
    }

    @Test fun shellAndEngineInstallationAreIndependent() {
        val base = temporary.newFolder()
        file(base, "ready")
        file(base, "rootfs/bin/bash")
        file(base, "rootfs/bin/busybox")
        assertEquals(RuntimeInstallation(true, false), RuntimeInstallation.inspect(base))
    }
}
