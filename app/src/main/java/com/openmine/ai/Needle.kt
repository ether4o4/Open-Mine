package com.openmine.ai

import org.json.JSONObject

/** One routing decision from Needle 3. Never executed directly: it becomes a proposal the user reviews. */
data class NeedleRoute(val tool: String?, val arguments: JSONObject, val confidence: Double, val reasoning: String)

object NeedleResult {
    /** Below this, Needle's choice is treated as "unsure" and LFM3 (or the quick-action list) decides. */
    const val CONFIDENCE_THRESHOLD = 0.3

    /** Parses Cactus's completion JSON. Unknown or malformed output yields a no-tool route. */
    fun parse(raw: String, allowedTools: Set<String>): NeedleRoute {
        if (raw.startsWith("ERROR:")) return NeedleRoute(null, JSONObject(), 0.0, raw)
        val json = runCatching { JSONObject(raw.substring(raw.indexOf('{').coerceAtLeast(0))) }.getOrNull()
            ?: return NeedleRoute(null, JSONObject(), 0.0, "Unreadable router output")
        val confidence = json.optDouble("confidence", 0.0).takeIf { !it.isNaN() } ?: 0.0
        val reasoning = json.optString("reasoning").take(300)
        val call = json.optJSONArray("function_calls")?.optJSONObject(0)
        val name = call?.optString("name")?.takeIf { it in allowedTools }
        val args = call?.optJSONObject("arguments") ?: JSONObject()
        return NeedleRoute(name, if (name == null) JSONObject() else args, confidence, reasoning)
    }

    fun isConfident(route: NeedleRoute) = route.tool != null && route.confidence >= CONFIDENCE_THRESHOLD
}

/** Needle 3 through the Cactus runtime. Thread-confined by the native mutex; one tool set at a time. */
class NeedleEngine(private val weights: java.io.File) {
    private var configuredTools: String? = null
    @Synchronized fun ensureLoaded(): String? {
        if (!NativeAi.available) return NativeAi.loadError
        if (!NativeAi.cactusAvailable()) return "Needle 3 needs an ARM processor; this device can still use LFM3."
        return NativeAi.cactusLoad(weights.absolutePath)
    }
    @Synchronized fun route(text: String, toolsJson: String, allowedTools: Set<String>, system: String): NeedleRoute {
        ensureLoaded()?.let { return NeedleRoute(null, JSONObject(), 0.0, it) }
        if (configuredTools != toolsJson) {
            NativeAi.cactusInit(system, toolsJson, 4)?.let { return NeedleRoute(null, JSONObject(), 0.0, it) }
            configuredTools = toolsJson
        }
        return NeedleResult.parse(NativeAi.cactusComplete(text.take(2000), 128), allowedTools)
    }
}
