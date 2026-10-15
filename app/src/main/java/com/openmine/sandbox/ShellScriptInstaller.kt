package com.openmine.sandbox

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Replaces only the runner script; installed Linux files and model weights are untouched. */
object ShellScriptInstaller {
    fun normalize(bytes: ByteArray): ByteArray {
        require(bytes.size <= 1024 * 1024) { "Shell script exceeds 1 MiB" }
        val text = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
            .replace("\r\n", "\n")
        require(!text.contains('\r')) { "Shell script contains an isolated carriage return" }
        require(!text.contains('\u0000')) { "Shell script contains a NUL byte" }
        require(text.startsWith("#!")) { "Shell script must begin with a shebang" }
        return text.toByteArray(Charsets.UTF_8)
    }

    fun install(target: File, bytes: ByteArray) {
        val normalized = normalize(bytes) // Validate before touching the existing deployment.
        val parent = target.absoluteFile.parentFile!!
        check(parent.isDirectory || parent.mkdirs()) { "Cannot create script directory" }
        val staging = File.createTempFile("runner-", ".tmp", parent)
        try {
            staging.outputStream().use { it.write(normalized); it.flush() }
            try {
                Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(staging.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { staging.delete() }
    }
}
