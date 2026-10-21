package com.gate6.jarvis.mcp

import android.util.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * A tool as listed by an MCP server. [readOnlyHint] is the server's annotation, null when absent
 * (the spec then says to assume the tool may change data).
 */
data class McpTool(
    val name: String,
    val title: String?,
    val description: String,
    val inputSchema: JsonObject,
    val readOnlyHint: Boolean?,
) {
    val readOnly: Boolean get() = readOnlyHint == true
}

open class McpException(message: String) : IOException(message)
class McpAuthException(message: String) : McpException(message)

/**
 * MCP client over the Streamable HTTP transport (JSON-RPC over POST; replies as JSON or SSE).
 * Auth is an optional static bearer token. One instance per server; safe to share between coroutines.
 */
class McpClient(private val url: String, private val token: String?) {
    private val ids = AtomicLong(1)
    private val initLock = Mutex()
    @Volatile private var sessionId: String? = null
    @Volatile private var protocolVersion: String? = null
    @Volatile private var initialized = false

    suspend fun listTools(): List<McpTool> {
        val tools = mutableListOf<McpTool>()
        var cursor: String? = null
        var pages = 0
        do {
            val result = request("tools/list", buildJsonObject { cursor?.let { put("cursor", it) } })
            (result["tools"] as? JsonArray)?.forEach { el -> parseTool(el)?.let(tools::add) }
            cursor = (result["nextCursor"] as? JsonPrimitive)?.contentOrNull
        } while (cursor != null && ++pages < MAX_PAGES)
        return tools
    }

    /** Calls a tool and returns its result as short plain text for speech. Tool-level errors start with "Error:". */
    suspend fun callTool(name: String, arguments: JsonObject): String {
        val result = request(
            "tools/call",
            buildJsonObject {
                put("name", name)
                put("arguments", arguments)
            },
            readTimeoutMs = CALL_READ_TIMEOUT_MS,
        )
        return McpResults.toText(result)
    }

    /** Best-effort session teardown. */
    suspend fun close() {
        val sid = sessionId ?: return
        sessionId = null
        initialized = false
        runCatching {
            runInterruptible(Dispatchers.IO) {
                val conn = open("DELETE", sid, CONNECT_TIMEOUT_MS)
                conn.responseCode
                conn.disconnect()
            }
        }
    }

    private suspend fun ensureInitialized() {
        if (initialized) return
        initLock.withLock {
            if (initialized) return
            val result = post(
                rpc("initialize", buildJsonObject {
                    put("protocolVersion", PROTOCOL_VERSION)
                    putJsonObject("capabilities") {}
                    putJsonObject("clientInfo") {
                        put("name", "jarvis-voice")
                        put("version", "0.2.0")
                    }
                }),
                READ_TIMEOUT_MS,
                captureSession = true,
            ) ?: throw McpException("Server sent no reply to initialize")
            protocolVersion = (result["protocolVersion"] as? JsonPrimitive)?.contentOrNull ?: PROTOCOL_VERSION
            post(notification("notifications/initialized"), READ_TIMEOUT_MS)
            initialized = true
        }
    }

    private suspend fun request(method: String, params: JsonObject, readTimeoutMs: Int = READ_TIMEOUT_MS): JsonObject {
        ensureInitialized()
        return try {
            post(rpc(method, params), readTimeoutMs) ?: throw McpException("No reply to $method")
        } catch (e: SessionExpiredException) {
            // Server dropped our session (restart, expiry): start a fresh one and retry once.
            Log.i(TAG, "MCP session expired at $url; reinitializing")
            sessionId = null
            initialized = false
            ensureInitialized()
            post(rpc(method, params), readTimeoutMs) ?: throw McpException("No reply to $method")
        }
    }

    private fun rpc(method: String, params: JsonObject) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", ids.getAndIncrement())
        put("method", method)
        put("params", params)
    }

    private fun notification(method: String) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("method", method)
    }

    /** POSTs one message. Returns the JSON-RPC `result` for requests, null for notifications. */
    private suspend fun post(message: JsonObject, readTimeoutMs: Int, captureSession: Boolean = false): JsonObject? =
        runInterruptible(Dispatchers.IO) {
            val conn = open("POST", sessionId, readTimeoutMs)
            try {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(message.toString().toByteArray()) }

                val code = conn.responseCode
                if (captureSession) conn.getHeaderField(SESSION_HEADER)?.let { sessionId = it }
                when {
                    code == 404 && sessionId != null && !captureSession -> throw SessionExpiredException()
                    code == 401 || code == 403 -> throw McpAuthException(
                        "Server refused access (HTTP $code). Check the access token."
                    )
                    code !in 200..299 -> throw McpException("HTTP $code from MCP server: ${errorBody(conn)}")
                }
                val id = (message["id"] as? JsonPrimitive)?.contentOrNull ?: return@runInterruptible null
                if (code == 202) throw McpException("Server accepted the request but sent no reply")

                val type = conn.contentType.orEmpty().lowercase()
                val reply = if (type.startsWith("text/event-stream")) {
                    conn.inputStream.bufferedReader().use { r ->
                        val sse = SseReader(r)
                        generateSequence { sse.next() }
                            .mapNotNull { parseOrNull(it) }
                            .flatMap { if (it is JsonArray) it.asSequence() else sequenceOf(it) }
                            .filterIsInstance<JsonObject>()
                            .firstOrNull { it.isResponseTo(id) }
                    }
                } else {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    when (val parsed = parseOrNull(body)) {
                        is JsonObject -> parsed.takeIf { it.isResponseTo(id) }
                        is JsonArray -> parsed.filterIsInstance<JsonObject>().firstOrNull { it.isResponseTo(id) }
                        else -> null
                    }
                } ?: throw McpException("Server closed the stream without replying")

                (reply["error"] as? JsonObject)?.let { err ->
                    val msg = (err["message"] as? JsonPrimitive)?.contentOrNull ?: err.toString()
                    throw McpException(msg)
                }
                reply["result"] as? JsonObject ?: JsonObject(emptyMap())
            } finally {
                conn.disconnect()
            }
        }

    private fun open(method: String, sid: String?, readTimeoutMs: Int): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = readTimeoutMs
        conn.setRequestProperty("Accept", "application/json, text/event-stream")
        token?.takeIf { it.isNotBlank() }?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
        sid?.let { conn.setRequestProperty(SESSION_HEADER, it) }
        protocolVersion?.let { conn.setRequestProperty("MCP-Protocol-Version", it) }
        return conn
    }

    private fun errorBody(conn: HttpURLConnection): String =
        runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty().take(200)

    private class SessionExpiredException : McpException("MCP session expired")

    companion object {
        private const val TAG = "McpClient"
        const val PROTOCOL_VERSION = "2025-06-18"
        private const val SESSION_HEADER = "Mcp-Session-Id"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 20_000
        private const val CALL_READ_TIMEOUT_MS = 45_000
        private const val MAX_PAGES = 10

        private val json = Json { ignoreUnknownKeys = true }

        private fun parseOrNull(text: String): JsonElement? = runCatching { json.parseToJsonElement(text) }.getOrNull()

        private fun JsonObject.isResponseTo(id: String): Boolean =
            (this["id"] as? JsonPrimitive)?.contentOrNull == id && ("result" in this || "error" in this)

        fun parseTool(el: JsonElement): McpTool? {
            val o = el as? JsonObject ?: return null
            val name = (o["name"] as? JsonPrimitive)?.contentOrNull ?: return null
            val annotations = o["annotations"] as? JsonObject
            return McpTool(
                name = name,
                title = (o["title"] as? JsonPrimitive)?.contentOrNull
                    ?: (annotations?.get("title") as? JsonPrimitive)?.contentOrNull,
                description = (o["description"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                inputSchema = o["inputSchema"] as? JsonObject ?: JsonObject(mapOf("type" to JsonPrimitive("object"))),
                readOnlyHint = (annotations?.get("readOnlyHint") as? JsonPrimitive)?.booleanOrNull,
            )
        }
    }
}
