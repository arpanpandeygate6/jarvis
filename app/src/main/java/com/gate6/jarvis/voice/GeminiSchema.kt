package com.gate6.jarvis.voice

import android.util.Log
import com.google.firebase.ai.type.FunctionDeclaration
import com.google.firebase.ai.type.Schema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Converts plain JSON Schema (MCP tool inputSchema) to the OpenAPI subset Gemini accepts.
 * Unsupported constructs are flattened: union types and anyOf/oneOf/allOf pick the first concrete
 * branch, empty or too-deep objects become a JSON-encoded string, and $ref/patterns/defaults are dropped.
 */
object GeminiSchema {
    private const val TAG = "GeminiSchema"
    private const val MAX_DEPTH = 5

    /** Returns null (and logs) when the tool cannot be represented. */
    fun toDeclaration(tool: ToolSpec): FunctionDeclaration? = try {
        val props = tool.parameters["properties"] as? JsonObject ?: JsonObject(emptyMap())
        val required = stringList(tool.parameters["required"])
        val params = props.mapValues { (_, v) -> convert(v as JsonObject, 1) }
        FunctionDeclaration(
            name = sanitizeName(tool.name),
            description = tool.description.take(1000),
            parameters = params,
            optionalParameters = params.keys.filter { it !in required },
        )
    } catch (e: Exception) {
        Log.w(TAG, "Skipping tool ${tool.name}: schema not convertible (${e.message})")
        null
    }

    /** Gemini function names: [a-zA-Z0-9_.-], max 64 chars, must start with a letter or underscore. */
    fun sanitizeName(name: String): String {
        val cleaned = name.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(64)
        return if (cleaned.firstOrNull()?.let { it.isLetter() || it == '_' } == true) cleaned else "_$cleaned".take(64)
    }

    private fun convert(node: JsonObject, depth: Int, inheritedDescription: String? = null): Schema {
        val description = node.str("description") ?: inheritedDescription
        node.firstBranch()?.let { return convert(it, depth, description) }

        val (type, nullable) = node.typeAndNullable()
        if (depth > MAX_DEPTH) return jsonString(description, nullable)

        val enumValues = (node["enum"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        if (!enumValues.isNullOrEmpty() && (type == null || type == "string")) {
            return Schema.enumeration(enumValues, description, nullable)
        }
        return when (type) {
            "string" -> Schema.string(description, nullable)
            "integer" -> Schema.integer(description, nullable)
            "number" -> Schema.double(description, nullable)
            "boolean" -> Schema.boolean(description, nullable)
            "array" -> {
                val items = node["items"] as? JsonObject
                Schema.array(items?.let { convert(it, depth + 1) } ?: Schema.string(), description, nullable)
            }
            "object", null -> {
                val props = node["properties"] as? JsonObject
                if (props.isNullOrEmpty()) return jsonString(description, nullable)
                val required = stringList(node["required"])
                Schema.obj(
                    properties = props.mapValues { (_, v) -> convert(v.jsonObject, depth + 1) },
                    optionalProperties = props.keys.filter { it !in required },
                    description = description,
                    nullable = nullable,
                )
            }
            else -> jsonString(description, nullable)
        }
    }

    private fun JsonObject.firstBranch(): JsonObject? =
        listOf("anyOf", "oneOf", "allOf").firstNotNullOfOrNull { key ->
            (this[key] as? JsonArray)?.map { it.jsonObject }?.firstOrNull { it.str("type") != "null" }
        }

    private fun JsonObject.typeAndNullable(): Pair<String?, Boolean> = when (val t = this["type"]) {
        is JsonPrimitive -> t.contentOrNull to (this["nullable"]?.jsonPrimitive?.contentOrNull == "true")
        is JsonArray -> {
            val names = t.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            names.firstOrNull { it != "null" } to ("null" in names)
        }
        else -> null to false
    }

    private fun jsonString(description: String?, nullable: Boolean) =
        Schema.string(listOfNotNull(description, "(JSON-encoded value)").joinToString(" "), nullable)

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun stringList(element: Any?): List<String> =
        (element as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
}
