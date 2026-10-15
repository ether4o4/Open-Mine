package com.openmine.sandbox

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Install an app-owned executable without touching a user's working engine or models. */
object BundledEngine {
    const val ARM64_SHA256 = "f2454a01c217b9f10ee82550b136885f46ae85152c8be8d21617c8f77f6929de"
    const val ARM64_BYTES = 9380464L

    fun install(input: InputStream, target: File, checkCancelled: () -> Unit = {}) {
        target.parentFile!!.mkdirs()
        val partial = File.createTempFile("engine-", ".part", target.parentFile)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var count = 0L
            input.use { source -> partial.outputStream().use { output ->
                val buffer = ByteArray(65536)
                while (true) {
                    checkCancelled()
                    val size = source.read(buffer)
                    if (size < 0) break
                    count += size
                    check(count <= ARM64_BYTES) { "Bundled engine exceeds its expected size" }
                    digest.update(buffer, 0, size)
                    output.write(buffer, 0, size)
                }
                output.fd.sync()
            } }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            check(count == ARM64_BYTES && actual == ARM64_SHA256) { "Bundled engine integrity check failed" }
            check(partial.setExecutable(true, true)) { "Cannot make the bundled engine executable" }
            checkCancelled()
            Files.move(partial.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { partial.delete() }
    }
}
