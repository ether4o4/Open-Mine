package com.openmine

import org.json.JSONObject
import java.net.HttpURLConnection

object ModelApi {
    fun errorMessage(status: Int) = when(status) {
        401 -> "Authentication failed (HTTP 401). Enter a valid API key for this endpoint."
        403 -> "Access denied (HTTP 403). Check this key's provider and model permissions."
        404 -> "API or model not found (HTTP 404). Check the compatible API base and installed model ID."
        429 -> "Provider rate or quota limit (HTTP 429). Check your account and retry later."
        in 500..599 -> "Model server failed (HTTP $status). Inspect the server and retry."
        else -> "Model request failed (HTTP $status). Check the API base, model and request settings."
    }
    fun models(endpoint: String, key: String, control: ModelRequestControl): List<String> {
        val uri = ModelEndpoint.chatUri(endpoint).toString().removeSuffix("chat/completions") + "models"
        val connection = java.net.URL(uri).openConnection() as HttpURLConnection
        control.attach(connection)
        try {
            connection.instanceFollowRedirects=false; connection.connectTimeout=15000; connection.readTimeout=15000
            if(key.isNotBlank()) connection.setRequestProperty("Authorization","Bearer $key")
            control.checkActive()
            check(connection.responseCode in 200..299) { errorMessage(connection.responseCode) }
            val bytes=connection.inputStream.use { ProjectStore.readBounded(it,1024*1024) }
            control.checkActive()
            val rows=JSONObject(String(bytes,Charsets.UTF_8)).getJSONArray("data")
            return (0 until rows.length()).map { rows.getJSONObject(it).getString("id") }.distinct().sorted()
        } finally { control.detach(connection); connection.disconnect() }
    }
}
