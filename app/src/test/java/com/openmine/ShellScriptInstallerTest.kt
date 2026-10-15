package com.openmine

import com.openmine.sandbox.ShellScriptInstaller
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ShellScriptInstallerTest {
    @Test fun normalizesEntireRealRunnerWithWindowsLineEndings() {
        val source=listOf(java.io.File("src/main/assets/sandbox/morsllm.sh"),java.io.File("app/src/main/assets/sandbox/morsllm.sh")).first{it.isFile}.readBytes()
        assertFalse(source.contains(13.toByte()))
        val contaminated=("\uFEFF"+String(source,Charsets.UTF_8).replace("\n","\r\n")).toByteArray(Charsets.UTF_8)
        assertArrayEquals(source,ShellScriptInstaller.normalize(contaminated))
    }
    @Test fun stripsBomAndCrLfWithoutChangingCommands() {
        val lf="#!/usr/bin/env bash\nset -eu\nprintf 'hello\\n'\n"
        val windows="\uFEFF"+lf.replace("\n","\r\n")
        assertArrayEquals(lf.toByteArray(), ShellScriptInstaller.normalize(windows.toByteArray()))
        assertArrayEquals(lf.toByteArray(), ShellScriptInstaller.normalize(lf.toByteArray()))
    }
    @Test fun migrationPreservesModelsRootfsLibraryAndExistingScriptOnInvalidInput() {
        val root=Files.createTempDirectory("openmine-migration-").toFile()
        try {
            val target=root.resolve("home/morsllm.sh");target.parentFile!!.mkdirs()
            target.writeText("#!/bin/sh\r\necho broken\r\n")
            val sentinels=listOf("home/.morsvitaest/llm/models/model.gguf","rootfs/bin/bash","open_mine_objects/skill.omd")
                .map{root.resolve(it).apply{parentFile!!.mkdirs();writeBytes(byteArrayOf(1,2,3,4))}}
            val lf="#!/bin/sh\necho repaired\n"
            ShellScriptInstaller.install(target,("\uFEFF"+lf.replace("\n","\r\n")).toByteArray())
            assertEquals(lf,target.readText())
            assertTrue(runCatching{ShellScriptInstaller.install(target,byteArrayOf(0xff.toByte()))}.isFailure)
            assertEquals(lf,target.readText())
            sentinels.forEach{assertArrayEquals(byteArrayOf(1,2,3,4),it.readBytes())}
            assertFalse(target.parentFile!!.listFiles()!!.any{it.name.startsWith("runner-")})
        } finally { root.deleteRecursively() }
    }
    @Test fun rejectsBinaryOversizedAndAmbiguousBareCarriageReturn() {
        for(bytes in listOf("#!/bin/sh\n\u0000".toByteArray(),"#!/bin/sh\recho bad".toByteArray(),ByteArray(1024*1024+1))) {
            assertTrue(runCatching{ShellScriptInstaller.normalize(bytes)}.isFailure)
        }
    }
}
