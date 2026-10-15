package com.openmine

import java.net.URI
import java.util.Locale

/** Navigation policy shared by typed URLs, redirects, new windows and external-open. */
object BrowserPolicy {
    fun address(input: String): String {
        val text = input.trim()
        require(text.isNotEmpty()) { "Enter a web address." }
        require(text.none { it.isWhitespace() || it.code < 32 || it.code == 127 }) {
            "The address contains spaces or control characters. Enter a complete web address."
        }
        val hasScheme = text.contains("://") || text.startsWith("about:", true) ||
            text.startsWith("javascript:", true) || text.startsWith("data:", true) ||
            text.startsWith("intent:", true) || text.startsWith("file:", true) ||
            text.startsWith("content:", true) || text.startsWith("mailto:", true) ||
            text.startsWith("tel:", true)
        return navigation(if (hasScheme) text else "https://$text")
    }

    fun navigation(input: String): String {
        require(input.length <= 16_384) { "This web address is too long." }
        require(input.none { it.code < 32 || it.code == 127 }) { "Invalid web address." }
        val uri = runCatching { URI(input) }.getOrElse {
            throw IllegalArgumentException("Invalid web address. Use https:// followed by a host name.")
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        require(scheme == "https" || scheme == "http") { "Only web pages using HTTPS or local HTTP can be opened." }
        require(uri.rawUserInfo == null) { "Credentials in web addresses are not allowed. Sign in on the website instead." }
        val host = uri.host?.lowercase(Locale.ROOT)
        require(!host.isNullOrBlank()) { "The web address needs a valid host name." }
        require(uri.port == -1 || uri.port in 1..65535) { "The port must be between 1 and 65535." }
        require(scheme == "https" || host == "127.0.0.1" || host == "localhost") {
            "Remote websites require HTTPS. HTTP is allowed only for localhost or 127.0.0.1."
        }
        // Preserve encoded path/query bytes. Rebuilding URI components would double-encode them.
        val port = if (uri.port == -1) "" else ":${uri.port}"
        return "$scheme://$host$port${uri.rawPath.orEmpty()}" +
            (uri.rawQuery?.let { "?$it" } ?: "") + (uri.rawFragment?.let { "#$it" } ?: "")
    }
}
