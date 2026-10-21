package com.gate6.jarvis.mcp

import com.gate6.jarvis.voice.ToolSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The tools one server offered, plus whether the user trusts its unannotated tools as read-only. */
data class ServerTools(
    val serverId: String,
    val serverName: String,
    val tools: List<McpTool>,
    val trustUnmarked: Boolean = false,
)

/** One MCP tool as exposed to the voice model. */
data class ToolRoute(
    val exposedName: String,
    val serverId: String,
    val serverName: String,
    val tool: McpTool,
    val needsConfirmation: Boolean,
) {
    val label: String get() = tool.title ?: tool.name.replace('_', ' ')
}

/**
 * Maps MCP tools from several servers into one flat list of uniquely named tools.
 * Names are `<server>__<tool>` cut to Gemini's 64-char limit; tools that change data get a
 * required `user_confirmed` boolean (see [Confirmation]).
 */
class ToolCatalog(servers: List<ServerTools>, maxTools: Int = MAX_TOOLS) {
    private val routes = LinkedHashMap<String, ToolRoute>()
    val skipped: Int

    init {
        var dropped = 0
        for (s in servers) {
            val prefix = slug(s.serverName)
            for (tool in s.tools) {
                if (routes.size >= maxTools) { dropped++; continue }
                val name = uniqueName("${prefix}__${tool.name}")
                routes[name] = ToolRoute(name, s.serverId, s.serverName, tool, needsConfirmation(tool, s.trustUnmarked))
            }
        }
        skipped = dropped
    }

    val size: Int get() = routes.size

    fun route(exposedName: String): ToolRoute? = routes[exposedName]

    fun specs(): List<ToolSpec> = routes.values.map { r ->
        val description = buildString {
            append("[").append(r.serverName).append("] ")
            append(r.tool.description.ifBlank { r.label })
            if (r.needsConfirmation) append(" This changes data: confirm with the user first.")
        }
        ToolSpec(r.exposedName, description, if (r.needsConfirmation) withConfirmParam(r.tool.inputSchema) else r.tool.inputSchema)
    }

    private fun uniqueName(raw: String): String {
        val base = sanitize(raw)
        if (base !in routes && base != RESERVED) return base
        var i = 2
        while (true) {
            val suffix = "_$i"
            val candidate = base.take(MAX_NAME - suffix.length) + suffix
            if (candidate !in routes) return candidate
            i++
        }
    }

    companion object {
        const val MAX_TOOLS = 80
        private const val MAX_NAME = 64
        private const val RESERVED = "end_conversation"

        /** Same rules as Gemini function names: [A-Za-z0-9_.-], starts with a letter or underscore, ≤ 64. */
        fun sanitize(name: String): String {
            val cleaned = name.replace(Regex("[^A-Za-z0-9_.-]"), "_")
            val started = if (cleaned.firstOrNull()?.let { it.isLetter() || it == '_' } == true) cleaned else "_$cleaned"
            return started.take(MAX_NAME)
        }

        /** Explicit `readOnlyHint: false` always needs a yes; unannotated tools only if the server is not trusted. */
        fun needsConfirmation(tool: McpTool, trustUnmarked: Boolean): Boolean = when (tool.readOnlyHint) {
            true -> false
            false -> true
            null -> !trustUnmarked
        }

        fun slug(serverName: String): String =
            serverName.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(16).ifEmpty { "mcp" }

        fun withConfirmParam(schema: JsonObject): JsonObject {
            val props = (schema["properties"] as? JsonObject).orEmpty() + (Confirmation.PARAM to buildJsonObject {
                put("type", "boolean")
                put("description", "Set true only after reading the action back and the user clearly said yes.")
            })
            val required = (schema["required"] as? JsonArray).orEmpty()
                .filterNot { (it as? JsonPrimitive)?.content == Confirmation.PARAM } + JsonPrimitive(Confirmation.PARAM)
            return JsonObject(schema + mapOf("type" to JsonPrimitive("object"), "properties" to JsonObject(props), "required" to JsonArray(required)))
        }
    }
}
