package com.openmine.sandbox

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream

private const val ALPINE_VERSION = "3.21.3"
private const val ALPINE_BRANCH = "v3.21"
private const val BUFFER_SIZE = 8192

private val ALPINE_MIRRORS = listOf(
    "https://dl-cdn.alpinelinux.org/alpine",
    "https://mirrors.edge.kernel.org/alpine",
    "https://ftp.halifax.rwth-aachen.de/alpine",
    "https://alpine.ethz.ch/alpine",
    "https://mirror.csclub.uwaterloo.ca/alpine",
    "https://mirrors.tuna.tsinghua.edu.cn/alpine",
)
private const val TAR_BLOCK_SIZE = 512
private const val TAR_NAME_OFFSET = 0
private const val TAR_MODE_OFFSET = 100
private const val TAR_SIZE_OFFSET = 124
private const val TAR_TYPE_OFFSET = 156
private const val TAR_LINK_OFFSET = 157
private const val TAR_PREFIX_OFFSET = 345

class RootfsDownloader {
    private val activeConnections = java.util.concurrent.ConcurrentHashMap.newKeySet<java.net.HttpURLConnection>()
    fun cancel() { activeConnections.forEach { it.disconnect() } }

    val mirrors: List<String> = ALPINE_MIRRORS

    fun getDownloadUrls(arch: String): List<String> = ALPINE_MIRRORS.map { base ->
        "$base/$ALPINE_BRANCH/releases/$arch/alpine-minirootfs-$ALPINE_VERSION-$arch.tar.gz"
    }

    suspend fun download(
        arch: String,
        targetFile: File,
        onProgress: (Float) -> Unit,
    ) {
        val urls = getDownloadUrls(arch)
        var lastError: Exception? = null
        for ((index, url) in urls.withIndex()) {
            currentCoroutineContext().ensureActive()
            try {
                downloadFrom(url, targetFile, onProgress)
                val checksumConnection = java.net.URL("$url.sha256").openConnection() as java.net.HttpURLConnection
                activeConnections.add(checksumConnection)
                currentCoroutineContext().ensureActive()
                checksumConnection.connectTimeout = 15000
                checksumConnection.readTimeout = 30000
                val expected = try {
                    check(checksumConnection.responseCode == 200) { "Rootfs checksum unavailable" }
                    checksumConnection.inputStream.bufferedReader().use { it.readLine().trim().split(Regex("\\s+")).first() }
                } finally { activeConnections.remove(checksumConnection); checksumConnection.disconnect() }
                check(Regex("[a-fA-F0-9]{64}").matches(expected)) { "Invalid rootfs checksum" }
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                targetFile.inputStream().use { input -> val buffer = ByteArray(65536); while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) } }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                check(actual.equals(expected, ignoreCase = true)) { "Rootfs SHA-256 mismatch" }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                if (targetFile.exists()) targetFile.delete()
                if (index < urls.lastIndex) onProgress(0f)
            }
        }
        throw IOException("All Alpine mirrors failed", lastError)
    }

    private suspend fun downloadFrom(
        url: String,
        targetFile: File,
        onProgress: (Float) -> Unit,
    ) {
        val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        activeConnections.add(connection)
        connection.connectTimeout = 15000
        connection.readTimeout = 60000
        try {
            currentCoroutineContext().ensureActive()
            check(connection.responseCode in 200..299) { "Rootfs HTTP ${connection.responseCode}" }
            val total = connection.contentLengthLong
            var received = 0L
            connection.inputStream.use { input ->
                targetFile.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        received += count
                        check(received <= 50L * 1024 * 1024) { "Rootfs download exceeds limit" }
                        output.write(buffer, 0, count)
                        onProgress(if (total > 0) received.toFloat() / total else 0f)
                    }
                }
            }
            check(total < 0 || received == total) { "Incomplete rootfs download" }
        } finally { activeConnections.remove(connection); connection.disconnect() }
    }
    fun extractTarGz(tarGzFile: File, targetDir: File) {
        targetDir.mkdirs()
        GZIPInputStream(BufferedInputStream(FileInputStream(tarGzFile))).use { gzipStream ->
            extractTar(gzipStream, targetDir)
        }
    }

    private fun extractTar(inputStream: java.io.InputStream, targetDir: File) {
        val headerBuffer = ByteArray(TAR_BLOCK_SIZE)
        val dataBuffer = ByteArray(BUFFER_SIZE)
        var extractedSize = 0L

        while (true) {
            val headerBytesRead = readFully(inputStream, headerBuffer)
            if (headerBytesRead == 0) break
            if (headerBytesRead < TAR_BLOCK_SIZE) throw IOException("Truncated rootfs header")

            val name = readTarString(headerBuffer, TAR_NAME_OFFSET, 100)
            if (name.isEmpty()) break

            val prefix = readTarString(headerBuffer, TAR_PREFIX_OFFSET, 155)
            val fullName = if (prefix.isNotEmpty()) "$prefix/$name" else name

            val sizeStr = readTarString(headerBuffer, TAR_SIZE_OFFSET, 12)
            val size = if (sizeStr.isNotEmpty()) sizeStr.toLong(8) else 0L
            extractedSize += size
            if (size < 0 || size > 128L * 1024 * 1024 || extractedSize > 512L * 1024 * 1024) throw IOException("Rootfs archive exceeds extraction limits")

            val modeStr = readTarString(headerBuffer, TAR_MODE_OFFSET, 8)
            val mode = if (modeStr.isNotEmpty()) modeStr.toInt(8) else 0
            val typeFlag = headerBuffer[TAR_TYPE_OFFSET]
            val linkName = readTarString(headerBuffer, TAR_LINK_OFFSET, 100)

            val outFile = File(targetDir, fullName)

            val root = targetDir.canonicalPath + File.separator
            if (outFile.canonicalPath != targetDir.canonicalPath && !outFile.canonicalPath.startsWith(root)) {
                skipBytes(inputStream, alignToBlock(size))
                continue
            }

            when (typeFlag.toInt().toChar()) {
                '5', 'D' -> outFile.mkdirs()

                '2' -> {
                    outFile.parentFile?.mkdirs()
                    try {
                        if (outFile.exists()) outFile.delete()
                        java.nio.file.Files.createSymbolicLink(
                            outFile.toPath(),
                            java.nio.file.Paths.get(linkName),
                        )
                    } catch (_: Exception) {
                    }
                }

                '1' -> {
                    val linkTarget = File(targetDir, linkName)
                    if (!linkTarget.canonicalPath.startsWith(root)) throw IOException("Unsafe archive hardlink")
                    outFile.parentFile?.mkdirs()
                    if (linkTarget.exists()) {
                        linkTarget.copyTo(outFile, overwrite = true)
                    }
                }

                '0', '\u0000' -> {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { output ->
                        var remaining = size
                        while (remaining > 0) {
                            val toRead = minOf(remaining, dataBuffer.size.toLong()).toInt()
                            val bytesRead = inputStream.read(dataBuffer, 0, toRead)
                            if (bytesRead <= 0) throw IOException("Truncated rootfs archive")
                            output.write(dataBuffer, 0, bytesRead)
                            remaining -= bytesRead
                        }
                    }
                    if (mode and 0b001_001_001 != 0) {
                        outFile.setExecutable(true, false)
                    }
                    val padding = alignToBlock(size) - size
                    if (padding > 0) skipBytes(inputStream, padding)
                    continue
                }

                else -> {}
            }

            if (size > 0 && typeFlag.toInt().toChar() != '0' && typeFlag.toInt().toChar() != '\u0000') {
                skipBytes(inputStream, alignToBlock(size))
            }
        }
    }

    private fun readTarString(buffer: ByteArray, offset: Int, length: Int): String {
        val end = minOf(offset + length, buffer.size)
        val nullIndex = (offset until end).firstOrNull { buffer[it] == 0.toByte() } ?: end
        return String(buffer, offset, nullIndex - offset, Charsets.US_ASCII).trim()
    }

    private fun readFully(inputStream: java.io.InputStream, buffer: ByteArray): Int {
        var totalRead = 0
        while (totalRead < buffer.size) {
            val bytesRead = inputStream.read(buffer, totalRead, buffer.size - totalRead)
            if (bytesRead <= 0) break
            totalRead += bytesRead
        }
        return totalRead
    }

    private fun skipBytes(inputStream: java.io.InputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = inputStream.skip(remaining)
            if (skipped <= 0) {
                if (inputStream.read() < 0) throw IOException("Truncated rootfs entry padding")
                remaining -= 1
            } else {
                remaining -= skipped
            }
        }
    }

    private fun alignToBlock(size: Long): Long {
        val remainder = size % TAR_BLOCK_SIZE
        return if (remainder == 0L) size else size + (TAR_BLOCK_SIZE - remainder)
    }

    fun makeWritable(rootfsDir: File) {
        rootfsDir.walkTopDown().forEach { file ->
            if (file.isDirectory && !file.canWrite()) {
                file.setWritable(true, true)
            }
        }
    }

    fun writeResolvConf(rootfsDir: File) {
        val etcDir = File(rootfsDir, "etc")
        etcDir.mkdirs()
        File(etcDir, "resolv.conf").writeText(
            "nameserver 8.8.8.8\nnameserver 8.8.4.4\n",
        )
    }

    fun writeRepositories(rootfsDir: File, mirrorBase: String) {
        val apkDir = File(rootfsDir, "etc/apk")
        apkDir.mkdirs()
        File(apkDir, "repositories").writeText(
            "$mirrorBase/$ALPINE_BRANCH/main\n$mirrorBase/$ALPINE_BRANCH/community\n",
        )
    }
}
