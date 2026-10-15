package com.openmine

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Endpoint-bound secrets; ciphertext is in noBackupFilesDir, never in portable data. */
class ProviderCredentials(context: Context) {
    private val directory = File(context.noBackupFilesDir, "provider-credentials").apply { mkdirs() }
    private fun identity(endpoint: String) = ModelEndpoint.chatUri(endpoint).toASCIIString()
    private fun file(endpoint: String) = File(directory, MessageDigest.getInstance("SHA-256")
        .digest(identity(endpoint).toByteArray()).joinToString("") { "%02x".format(it) })
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun save(endpoint: String, value: String) {
        require(value.length <= 8192) { "API key is too long" }
        if (value.isBlank()) { forget(endpoint); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,key()); updateAAD(identity(endpoint).toByteArray()) }
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val atomic = android.util.AtomicFile(file(endpoint))
        val out = atomic.startWrite()
        try { out.write(byteArrayOf(1,cipher.iv.size.toByte())); out.write(cipher.iv); out.write(encrypted); atomic.finishWrite(out) }
        catch (e: Exception) { atomic.failWrite(out); throw e }
    }
    @Synchronized fun load(endpoint: String): String {
        if (endpoint.isBlank()) return ""
        val target = file(endpoint)
        if (!target.exists()) return ""
        require(target.length() in 30..16384) { "Stored credential is damaged; re-enter it" }
        val bytes = android.util.AtomicFile(target).readFully()
        require(bytes[0] == 1.toByte() && bytes[1] == 12.toByte()) { "Unsupported stored credential" }
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(2,14)))
            updateAAD(identity(endpoint).toByteArray())
            String(doFinal(bytes.copyOfRange(14,bytes.size)),Charsets.UTF_8)
        }
    }
    @Synchronized fun forget(endpoint: String) { android.util.AtomicFile(file(endpoint)).delete() }
    companion object { private const val ALIAS = "open-mine-provider-v1" }
}
