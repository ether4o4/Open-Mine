package com.openmine.sandbox

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/** Device-local consent and credentials. Android excludes noBackupFilesDir from backup/transfer. */
class RuntimeBridgeSettings(context: Context) {
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, "runtime-control.json"))

    val enabled: Boolean get() = synchronized(lock) { read().optBoolean("enabled", false) }

    fun token(): String = synchronized(lock) {
        val settings = read()
        settings.optString("token").takeIf { it.matches(tokenPattern) } ?: newToken().also {
            settings.put("token", it)
            write(settings)
        }
    }

    fun setEnabled(enabled: Boolean) = synchronized(lock) {
        val settings = read().put("enabled", enabled)
        if (enabled && !settings.optString("token").matches(tokenPattern)) settings.put("token", newToken())
        write(settings)
    }

    fun authenticates(candidate: String?): Boolean = synchronized(lock) {
        if (candidate == null || !candidate.matches(tokenPattern)) return@synchronized false
        val settings = read()
        val expected = settings.optString("token")
        settings.optBoolean("enabled", false) && expected.matches(tokenPattern) &&
            MessageDigest.isEqual(expected.toByteArray(Charsets.US_ASCII), candidate.toByteArray(Charsets.US_ASCII))
    }

    internal val runtimeRequested: Boolean
        get() = synchronized(lock) { read().optBoolean("runtimeRequested", false) }

    internal fun setRuntimeRequested(requested: Boolean) = synchronized(lock) {
        write(read().put("runtimeRequested", requested))
    }

    private fun read(): JSONObject = try {
        file.openRead().use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
    } catch (_: Exception) {
        // Missing/corrupt settings always disable external access.
        JSONObject()
    }

    private fun write(settings: JSONObject) {
        val output = file.startWrite()
        try {
            output.write(settings.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    private fun newToken(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        private val lock = Any()
        private val tokenPattern = Regex("[a-f0-9]{64}")
    }
}
