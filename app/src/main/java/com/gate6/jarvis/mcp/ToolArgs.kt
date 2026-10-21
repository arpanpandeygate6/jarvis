package com.gate6.jarvis.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Undoes the lossy parts of the Gemini schema conversion before arguments go to the MCP server:
 * values Gemini was told to send as "JSON-encoded strings" are parsed back, and whole-number
 * doubles are turned into integers where the original schema wants an integer.
 */
object ToolArgs {
    private val json = Json { ignoreUnknownKeys = true }

    fun decode(args: JsonObject, schema: JsonObject): JsonObject = fix(args, schema) as JsonObject

    private fun fix(value: JsonElement, rawSchema: JsonObject?): JsonElement {
        val schema = rawSchema?.let(::firstBranch)
        val type = schema?.let(::typeOf)

        if (value is JsonPrimitive && value.isString && type != "string") {
            val text = value.content.trim()
            val wantsStructured = type == "object" || type == "array" ||
                (type == null && (text.startsWith("{") || text.startsWith("[")))
            if (wantsStructured || type == "integer" || type == "number" || type == "boolean") {
                val parsed = runCatching { json.parseToJsonElement(text) }.getOrNull()
                if (parsed != null && matches(parsed, type)) return fix(parsed, schema)
            }
            return value
        }
        if (value is JsonPrimitive && !value.isString && type == "integer") {
            val d = value.doubleOrNull
            if (d != null && d % 1.0 == 0.0 && d >= Long.MIN_VALUE && d <= Long.MAX_VALUE) return JsonPrimitive(d.toLong())
            return value
        }
        if (value is JsonObject) {
            val props = schema?.get("properties") as? JsonObject
            val additional = schema?.get("additionalProperties") as? JsonObject
            return JsonObject(value.mapValues { (k, v) -> fix(v, props?.get(k) as? JsonObject ?: additional) })
        }
        if (value is JsonArray) {
            val items = schema?.get("items") as? JsonObject
            return JsonArray(value.map { fix(it, items) })
        }
        return value
    }

    private fun matches(el: JsonElement, type: String?): Boolean = when (type) {
        "object" -> el is JsonObject
        "array" -> el is JsonArray
        "integer", "number" -> el is JsonPrimitive && !el.isString && el.doubleOrNull != null
        "boolean" -> el is JsonPrimitive && !el.isString && el.content in setOf("true", "false")
        else -> el is JsonObject || el is JsonArray
    }

    private fun firstBranch(schema: JsonObject): JsonObject {
        for (key in listOf("anyOf", "oneOf", "allOf")) {
            val branches = schema[key] as? JsonArray ?: continue
            val pick = branches.filterIsInstance<JsonObject>().firstOrNull { typeOf(it) != "null" } ?: continue
            return firstBranch(pick)
        }
        return schema
    }

    private fun typeOf(schema: JsonObject): String? = when (val t = schema["type"]) {
        is JsonPrimitive -> t.contentOrNull
        is JsonArray -> t.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.firstOrNull { it != "null" }
        else -> if (schema["properties"] is JsonObject) "object" else null
    }
}
