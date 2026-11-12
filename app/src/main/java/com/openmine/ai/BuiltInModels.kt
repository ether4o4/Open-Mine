package com.openmine.ai

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** A model Open Mine can provide itself, with an immutable source revision and digest. */
data class BuiltInModel(
    val id: String, val displayName: String, val fileName: String, val url: String,
    val sizeBytes: Long, val sha256: String, val license: String,
)

object BuiltInModels {
    /** LFM3 1.7B Uncensored (AtomicChat), downloaded on first run. Revision 3ad7125 of the GGUF repo. */
    val LFM3 = BuiltInModel("lfm3-1.7b-uncensored", "LFM3 1.7B Uncensored", "LFM3-1.7B-Uncensored-AD-Q4_K_M.gguf",
        "https://huggingface.co/AtomicChat/LFM3-1.7B-Uncensored-GGUF/resolve/3ad712542eb9de349dbd28f2739b69c925cdec7d/LFM3-1.7B-Uncensored-AD-Q4_K_M.gguf",
        1_132_208_000, "b22df65673b54ed326f18f74826d76f3b65dadc237ffd6306cd52be04544827d", "LFM Open License v1.0")

    fun directory(filesDir: File) = File(filesDir, "ai/models").apply { mkdirs() }
    fun file(filesDir: File, model: BuiltInModel) = File(directory(filesDir), model.fileName)
    /** A model counts as installed only after its verified receipt exists and the size still matches. */
    fun installed(filesDir: File, model: BuiltInModel): Boolean {
        val f = file(filesDir, model); val receipt = File(directory(filesDir), model.fileName + ".verified")
        return f.isFile && f.length() == model.sizeBytes && receipt.isFile && receipt.readText().trim() == model.sha256
    }

    fun sha256(file: File, ensureActive: () -> Unit = {}): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(1 shl 20)
            while (true) { ensureActive(); val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Resumable download into a .part file, verified by size and SHA-256 before it is published.
     * A digest mismatch deletes only the partial file, never an installed model.
     */
    fun download(filesDir: File, model: BuiltInModel, onProgress: (Long, Long) -> Unit, ensureActive: () -> Unit): File {
        val target = file(filesDir, model)
        if (installed(filesDir, model)) return target
        val partial = File(target.parentFile, target.name + ".part")
        val needed = model.sizeBytes - partial.length() + 64L * 1024 * 1024
        if (target.parentFile!!.usableSpace < needed) throw IOException("Not enough free storage: about ${needed / (1024 * 1024)} MiB is needed.")
        var attempt = 0
        while (partial.length() < model.sizeBytes) {
            ensureActive()
            try { fetch(model, partial, onProgress, ensureActive) }
            catch (e: IOException) { if (++attempt >= 5) throw IOException("Download interrupted: ${e.message}. Progress is kept; retry to resume.", e); Thread.sleep(2000L * attempt) }
        }
        check(partial.length() == model.sizeBytes) { "Downloaded size does not match; retry the download." }
        val digest = sha256(partial, ensureActive)
        if (digest != model.sha256) { partial.delete(); throw IOException("Downloaded file failed SHA-256 verification and was discarded. Retry the download.") }
        publish(partial, target, model)
        return target
    }

    private fun fetch(model: BuiltInModel, partial: File, onProgress: (Long, Long) -> Unit, ensureActive: () -> Unit) {
        val start = partial.length()
        val connection = URL(model.url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 20_000; connection.readTimeout = 60_000; connection.instanceFollowRedirects = true
            if (start > 0) connection.setRequestProperty("Range", "bytes=$start-")
            val code = connection.responseCode
            val append = code == 206
            if (code != 200 && code != 206) throw IOException("Server returned HTTP $code")
            if (!append && start > 0) partial.delete()
            var received = if (append) start else 0L
            java.io.FileOutputStream(partial, append).use { out -> connection.inputStream.use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    ensureActive(); val n = input.read(buffer); if (n < 0) break
                    received += n
                    if (received > model.sizeBytes) throw IOException("Server sent more data than expected")
                    out.write(buffer, 0, n); onProgress(received, model.sizeBytes)
                }
                out.fd.sync()
            } }
        } finally { connection.disconnect() }
    }

    private fun publish(partial: File, target: File, model: BuiltInModel) {
        if (!partial.renameTo(target)) throw IOException("Could not save ${model.displayName}.")
        File(target.parentFile, target.name + ".verified").writeText(model.sha256)
    }
}
