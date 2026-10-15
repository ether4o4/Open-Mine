package com.openmine

import java.net.URI
import java.util.Locale

object ModelEndpoint {
    const val BUILT_IN = "http://127.0.0.1:8080/v1"
    const val OLLAMA = "http://127.0.0.1:11434/v1"

    fun chatUri(input: String): URI {
        val text=input.trim()
        require(text.isNotEmpty()) { "Choose a connection preset or enter an API base URL" }
        require(!text.contains(',')) { "URL contains a comma. Use dots in 127.0.0.1 and a colon before the port; Ollama base is $OLLAMA" }
        val parsed=runCatching{URI(text)}.getOrElse{throw IllegalArgumentException("Malformed URL. Enter an API base such as $OLLAMA")}
        val scheme=parsed.scheme?.lowercase(Locale.ROOT)
        require(scheme in setOf("http","https")) { "URL must begin with https://, or http:// for this phone's local server" }
        require(parsed.rawUserInfo==null) { "Remove credentials from the URL; use the API key field" }
        require(parsed.rawQuery==null && parsed.rawFragment==null) { "API base URL cannot contain a query or fragment" }
        val host=parsed.host?.lowercase(Locale.ROOT)
        require(!host.isNullOrBlank()) { "Invalid server host. Use 127.0.0.1 or localhost for a server on this phone" }
        require(parsed.port==-1 || parsed.port in 1..65535) { "Port must be between 1 and 65535" }
        require(scheme=="https" || host in setOf("127.0.0.1","localhost")) { "HTTP is allowed only for exact 127.0.0.1 or localhost. Remote servers require HTTPS" }
        var path=parsed.rawPath.orEmpty().trimEnd('/')
        require(!path.endsWith("/chat/completions") && !path.startsWith("/api/")) { "Enter the OpenAI-compatible base URL ending /v1, not /api/chat or /chat/completions" }
        require(!path.contains('%') && !path.split('/').any{it=="." || it==".."}) { "API base path cannot contain escaped or relative segments" }
        if(scheme=="http"){
            if(path.isEmpty())path="/v1"
            require(path=="/v1") { "Local servers use the OpenAI-compatible /v1 base. Ollama: $OLLAMA; built-in: $BUILT_IN" }
        }
        return URI(scheme,null,host,parsed.port,path+"/chat/completions",null,null)
    }
}
