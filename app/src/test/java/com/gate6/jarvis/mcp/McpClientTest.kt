package com.gate6.jarvis.mcp

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** Runs [McpClient] against a tiny in-process Streamable HTTP MCP server. */
class McpClientTest {
    private lateinit var server: HttpServer
    private lateinit var url: String
    private val seen = CopyOnWriteArrayList<Pair<String, Map<String, String?>>>() // method to headers

    /** Behaviour switches for the fake server. */
    @Volatile private var useSse = false
    @Volatile private var expireSessionOnce = false
    @Volatile private var requiredToken: String? = null
    private var sessions = 0

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/mcp") { ex -> handle(ex) }
        server.start()
        url = "http://127.0.0.1:${server.address.port}/mcp"
    }

    @After
    fun tearDown() = server.stop(0)

    private fun handle(ex: HttpExchange) {
        ex.use {
            if (requiredToken != null && ex.requestHeaders.getFirst("Authorization") != "Bearer $requiredToken") {
                return reply(ex, 401, "unauthorized")
            }
            if (ex.requestMethod == "DELETE") return reply(ex, 200, "")
            val msg = Json.parseToJsonElement(ex.requestBody.bufferedReader().readText()).jsonObject
            val method = (msg["method"] as JsonPrimitive).content
            val sid = ex.requestHeaders.getFirst("Mcp-Session-Id")
            seen += method to mapOf(
                "session" to sid,
                "version" to ex.requestHeaders.getFirst("MCP-Protocol-Version"),
            )
            if (method != "initialize" && sid == null) return reply(ex, 400, "missing session")
            if (method == "tools/list" && expireSessionOnce) {
                expireSessionOnce = false
                return reply(ex, 404, "session gone")
            }
            if (msg["id"] == null) return reply(ex, 202, "")

            val result = when (method) {
                "initialize" -> {
                    ex.responseHeaders.add("Mcp-Session-Id", "s${++sessions}")
                    buildJsonObject { put("protocolVersion", "2025-03-26") }
                }
                "tools/list" -> {
                    val cursor = ((msg["params"] as JsonObject)["cursor"] as? JsonPrimitive)?.contentOrNull
                    if (cursor == null) Json.parseToJsonElement(
                        """{"tools":[{"name":"echo","inputSchema":{"type":"object"},"annotations":{"readOnlyHint":true}}],"nextCursor":"p2"}"""
                    ) else Json.parseToJsonElement("""{"tools":[{"name":"create","description":"makes things"}]}""")
                }
                "tools/call" -> {
                    val args = (msg["params"] as JsonObject)["arguments"].toString()
                    if (((msg["params"] as JsonObject)["name"] as JsonPrimitive).content == "fail") {
                        return reply(ex, 200, """{"jsonrpc":"2.0","id":${msg["id"]},"error":{"code":-32602,"message":"bad args"}}""")
                    }
                    Json.parseToJsonElement("""{"content":[{"type":"text","text":${JsonPrimitive("got $args")}}]}""")
                }
                else -> return reply(ex, 200, """{"jsonrpc":"2.0","id":${msg["id"]},"error":{"code":-32601,"message":"nope"}}""")
            }
            val response = """{"jsonrpc":"2.0","id":${msg["id"]},"result":$result}"""
            if (useSse) {
                // A progress notification first, then the response, the way real servers stream.
                val body = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}\n\n" +
                    "event: message\ndata: $response\n\n"
                ex.responseHeaders.add("Content-Type", "text/event-stream")
                reply(ex, 200, body)
            } else {
                ex.responseHeaders.add("Content-Type", "application/json")
                reply(ex, 200, response)
            }
        }
    }

    private fun reply(ex: HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray()
        ex.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) }
    }

    @Test
    fun handshakeListsAllPagesAndSendsHeaders() = runBlocking {
        val tools = McpClient(url).listTools()
        assertEquals(listOf("echo", "create"), tools.map { it.name })
        assertTrue(tools[0].readOnly)
        assertEquals(listOf("initialize", "notifications/initialized", "tools/list", "tools/list"), seen.map { it.first })
        val list = seen.last().second
        assertEquals("s1", list["session"])
        assertEquals("2025-03-26", list["version"]) // negotiated version is echoed back
    }

    @Test
    fun parsesSseResponsesAndSkipsNotifications() = runBlocking {
        useSse = true
        val c = McpClient(url)
        assertEquals(2, c.listTools().size)
        assertEquals("""got {"x":1}""", c.callTool("echo", buildJsonObject { put("x", 1) }))
    }

    @Test
    fun reinitializesWhenSessionExpires() = runBlocking {
        val c = McpClient(url)
        c.listTools()
        expireSessionOnce = true
        assertEquals(2, c.listTools().size)
        assertEquals(2, seen.count { it.first == "initialize" })
        assertEquals("s2", seen.last().second["session"])
    }

    @Test
    fun sendsBearerTokenAndReportsAuthFailure() = runBlocking {
        requiredToken = "secret"
        assertEquals(2, McpClient(url, StaticToken("secret")).listTools().size)
        try {
            McpClient(url, StaticToken("wrong")).listTools()
            fail("expected auth error")
        } catch (e: McpAuthException) {
            assertTrue(e.message!!.contains("401"))
        }
    }

    @Test
    fun surfacesJsonRpcErrors() = runBlocking {
        try {
            McpClient(url).callTool("fail", JsonObject(emptyMap()))
            fail("expected error")
        } catch (e: McpException) {
            assertEquals("bad args", e.message)
        }
    }

    /** Real-world interop check; runs only with MCP_LIVE_TEST=1 (needs internet). */
    @Test
    fun liveDeepWiki() = runBlocking {
        assumeTrue(System.getenv("MCP_LIVE_TEST") == "1")
        val c = McpClient("https://mcp.deepwiki.com/mcp")
        val tools = c.listTools()
        println("DeepWiki tools: " + tools.joinToString { "${it.name}(readOnly=${it.readOnly})" })
        assertTrue(tools.isNotEmpty())
        val structure = c.callTool("read_wiki_structure", buildJsonObject { put("repoName", "square/okhttp") })
        println("read_wiki_structure → " + structure.take(300))
        assertTrue(!structure.startsWith("Error"))
    }
}
