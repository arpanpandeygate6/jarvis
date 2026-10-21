package com.gate6.jarvis.mcp

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException

/**
 * Process-wide MCP state: one [McpClient] per configured server and a short-lived cache of each
 * server's tool list, so starting a conversation does not wait on every server every time.
 */
object McpRegistry {
    private const val TAG = "McpRegistry"
    private const val CACHE_TTL_MS = 10 * 60_000L

    private data class Entry(val url: String, val token: String?, val client: McpClient)
    private data class Cached(val tools: List<McpTool>, val at: Long)

    private val clients = ConcurrentHashMap<String, Entry>()
    private val cache = ConcurrentHashMap<String, Cached>()

    data class LoadResult(
        val catalog: ToolCatalog,
        val serverCount: Int,
        /** Server name to error message, for servers that gave no tools this time. */
        val failures: Map<String, String>,
    )

    /** Loads tools from every enabled server in parallel. A failing server is skipped, never fatal. */
    suspend fun load(context: Context, timeoutMs: Long): LoadResult = withContext(Dispatchers.IO) {
        val store = McpServerStore(context.applicationContext)
        val servers = store.enabled()
        val results = coroutineScope {
            servers.map { s -> async { s to fetch(store, s, timeoutMs, useCache = true) } }.awaitAll()
        }
        val failures = mutableMapOf<String, String>()
        val toolsets = results.mapNotNull { (s, r) ->
            r.fold(
                onSuccess = { ServerTools(s.id, s.name, it, s.trustUnmarked) },
                onFailure = { failures[s.name] = it.message ?: it.javaClass.simpleName; null },
            )
        }
        val catalog = ToolCatalog(toolsets)
        if (catalog.skipped > 0) Log.w(TAG, "Tool limit reached; ${catalog.skipped} tools not offered to the model")
        LoadResult(catalog, servers.size, failures)
    }

    /** Fresh connection test for the settings screen. */
    suspend fun test(context: Context, server: McpServer): Result<List<McpTool>> = withContext(Dispatchers.IO) {
        forget(server.id)
        fetch(McpServerStore(context.applicationContext), server, timeoutMs = 20_000, useCache = false)
    }

    fun clientFor(serverId: String): McpClient? = clients[serverId]?.client

    /** Drops the client and cached tools, e.g. after the server was edited or deleted. */
    fun forget(serverId: String) {
        cache.remove(serverId)
        clients.remove(serverId)
    }

    private suspend fun fetch(store: McpServerStore, s: McpServer, timeoutMs: Long, useCache: Boolean): Result<List<McpTool>> {
        val cached = cache[s.id]
        if (useCache && cached != null && SystemClock.elapsedRealtime() - cached.at < CACHE_TTL_MS) {
            return Result.success(cached.tools)
        }
        return try {
            val tools = withTimeout(timeoutMs) { client(store, s).listTools() }
            cache[s.id] = Cached(tools, SystemClock.elapsedRealtime())
            Log.i(TAG, "${s.name}: ${tools.size} tools")
            Result.success(tools)
        } catch (e: TimeoutCancellationException) {
            fallback(s, cached, McpException("timed out"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fallback(s, cached, e)
        }
    }

    private fun fallback(s: McpServer, cached: Cached?, e: Exception): Result<List<McpTool>> {
        Log.w(TAG, "Could not list tools from ${s.name}: ${e.message}")
        // A stale list beats no tools: calls will still fail loudly if the server is really down.
        return if (cached != null) Result.success(cached.tools) else Result.failure(e)
    }

    private fun client(store: McpServerStore, s: McpServer): McpClient {
        val token = store.token(s.id)
        clients[s.id]?.let { if (it.url == s.url && it.token == token) return it.client }
        return McpClient(s.url, token).also { clients[s.id] = Entry(s.url, token, it) }
    }
}
