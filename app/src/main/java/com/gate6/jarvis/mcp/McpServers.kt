package com.gate6.jarvis.mcp

import android.content.Context
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** A configured MCP server. The access token is kept separately in [SecretStore]. */
data class McpServer(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    /** Treat tools the server does not annotate as read-only (no spoken yes needed). */
    val trustUnmarked: Boolean = false,
)

/** Non-secret server list in SharedPreferences; tokens go through [SecretStore]. */
class McpServerStore(context: Context) {
    val context: Context = context.applicationContext
    private val prefs = context.getSharedPreferences("jarvis_mcp", Context.MODE_PRIVATE)
    private val secrets = SecretStore(context)

    @Synchronized
    fun all(): List<McpServer> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching { Json.parseToJsonElement(raw) as JsonArray }.getOrNull().orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            McpServer(
                id = o.str("id") ?: return@mapNotNull null,
                name = o.str("name").orEmpty(),
                url = o.str("url") ?: return@mapNotNull null,
                enabled = (o["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true,
                trustUnmarked = (o["trustUnmarked"] as? JsonPrimitive)?.booleanOrNull ?: false,
            )
        }
    }

    fun enabled(): List<McpServer> = all().filter { it.enabled }

    /** Inserts or replaces by id. [token]: null keeps the stored token, "" removes it. */
    @Synchronized
    fun save(server: McpServer, token: String?) {
        val list = all().filterNot { it.id == server.id } + server
        write(list.sortedBy { it.name.lowercase() })
        if (token != null) secrets.put(tokenKey(server.id), token.trim())
    }

    @Synchronized
    fun delete(id: String) {
        write(all().filterNot { it.id == id })
        secrets.put(tokenKey(id), null)
        com.gate6.jarvis.mcp.oauth.McpOAuth.signOut(context, id)
    }

    fun token(id: String): String? = secrets.get(tokenKey(id))

    fun hasToken(id: String): Boolean = secrets.has(tokenKey(id))

    private fun write(list: List<McpServer>) {
        val arr = buildJsonArray {
            list.forEach { s ->
                add(buildJsonObject {
                    put("id", s.id)
                    put("name", s.name)
                    put("url", s.url)
                    put("enabled", s.enabled)
                    put("trustUnmarked", s.trustUnmarked)
                })
            }
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

    companion object {
        private const val KEY = "servers"
        private fun tokenKey(id: String) = "mcp_token_$id"

        /** Remote servers must use HTTPS: tokens and tool data cross the network. */
        fun validUrl(url: String): Boolean =
            url.startsWith("https://", ignoreCase = true) && url.length > "https://".length + 2
    }
}
