package com.gate6.jarvis.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** Turns a `tools/call` result into compact text the voice model can speak from. */
object McpResults {
    const val MAX_CHARS = 4000

    fun toText(result: JsonObject): String {
        val isError = (result["isError"] as? JsonPrimitive)?.booleanOrNull == true
        val parts = (result["content"] as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            when ((o["type"] as? JsonPrimitive)?.contentOrNull) {
                "text" -> (o["text"] as? JsonPrimitive)?.contentOrNull
                "image" -> "[image]"
                "audio" -> "[audio]"
                "resource_link" -> (o["name"] as? JsonPrimitive)?.contentOrNull?.let { "[link: $it]" } ?: "[link]"
                "resource" -> ((o["resource"] as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull
                    ?: "[resource]"
                else -> null
            }
        }
        var text = parts.joinToString("\n").trim()
        if (text.isEmpty()) text = result["structuredContent"]?.toString().orEmpty()
        if (text.isEmpty()) text = if (isError) "the tool failed without details" else "Done."
        if (text.length > MAX_CHARS) text = text.take(MAX_CHARS) + " …(truncated)"
        return if (isError) "Error: $text" else text
    }
}
