package com.gate6.jarvis.mcp

import com.gate6.jarvis.mcp.oauth.OAuth
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** End-to-end OAuth for MCP against an in-process fake authorization server + MCP server. */
class OAuthTest {
    private lateinit var server: HttpServer
    private lateinit var base: String
    private val redirect = "com.gate6.jarvis://oauth/callback"

    // Fake server state.
    private var mode = "modern" // "modern" (PRM + RFC 8414), "legacy" (AS metadata on origin only), "bare" (nothing)
    private var sendResourceMetadataHeader = true
    private val codes = mutableMapOf<String, Pair<String, String>>() // code -> (challenge, client)
    private var validAccess = mutableSetOf<String>()
    private val issued = AtomicInteger()
    private val refreshes = AtomicInteger()
    private var registeredRedirect: String? = null

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex -> ex.use { handle(ex) } }
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun tearDown() = server.stop(0)

    private fun handle(ex: HttpExchange) {
        val path = ex.requestURI.path
        val body = ex.requestBody.bufferedReader().readText()
        when {
            path == "/.well-known/oauth-protected-resource/mcp" && mode == "modern" ->
                json(ex, """{"resource":"$base/mcp","authorization_servers":["$base/auth"],"scopes_supported":["read","write"]}""")
            path == "/.well-known/oauth-authorization-server/auth" && mode == "modern" ->
                json(ex, """{"issuer":"$base/auth","authorization_endpoint":"$base/auth/authorize","token_endpoint":"$base/auth/token","registration_endpoint":"$base/auth/register","code_challenge_methods_supported":["S256"],"authorization_response_iss_parameter_supported":true}""")
            path == "/.well-known/oauth-authorization-server" && mode == "legacy" ->
                json(ex, """{"issuer":"$base","authorization_endpoint":"$base/authorize","token_endpoint":"$base/token","registration_endpoint":"$base/register"}""")
            path.endsWith("/register") -> {
                val req = Json.parseToJsonElement(body).jsonObject
                registeredRedirect = req["redirect_uris"].toString()
                assertEquals(JsonPrimitive("none"), req["token_endpoint_auth_method"])
                json(ex, """{"client_id":"client-123"}""", 201)
            }
            path.endsWith("/token") -> token(ex, form(body))
            path == "/mcp" -> mcp(ex, body)
            else -> reply(ex, 404, "")
        }
    }

    private fun token(ex: HttpExchange, p: Map<String, String>) {
        if (p["client_id"] != "client-123") return json(ex, """{"error":"invalid_client"}""", 401)
        if (p["resource"] != "$base/mcp") return json(ex, """{"error":"invalid_target"}""", 400)
        when (p["grant_type"]) {
            "authorization_code" -> {
                val (challenge, _) = codes.remove(p["code"]) ?: return json(ex, """{"error":"invalid_grant"}""", 400)
                val expected = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(MessageDigest.getInstance("SHA-256").digest(p["code_verifier"]!!.toByteArray()))
                if (expected != challenge || p["redirect_uri"] != redirect) return json(ex, """{"error":"invalid_grant"}""", 400)
            }
            "refresh_token" -> {
                if (p["refresh_token"] != "refresh-1") return json(ex, """{"error":"invalid_grant"}""", 400)
                refreshes.incrementAndGet()
            }
            else -> return json(ex, """{"error":"unsupported_grant_type"}""", 400)
        }
        val access = "access-${issued.incrementAndGet()}"
        validAccess = mutableSetOf(access)
        // No refresh_token on refresh: the client must keep the old one.
        val refresh = if (p["grant_type"] == "authorization_code") ""","refresh_token":"refresh-1"""" else ""
        json(ex, """{"access_token":"$access","token_type":"Bearer","expires_in":3600$refresh}""")
    }

    private fun mcp(ex: HttpExchange, body: String) {
        val auth = ex.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ")
        if (auth == null || auth !in validAccess) {
            if (sendResourceMetadataHeader) {
                ex.responseHeaders.add("WWW-Authenticate", """Bearer realm="OAuth", resource_metadata="$base/.well-known/oauth-protected-resource/mcp", scope="read write"""")
            } else {
                ex.responseHeaders.add("WWW-Authenticate", "Bearer")
            }
            return reply(ex, 401, "unauthorized")
        }
        val msg = Json.parseToJsonElement(body).jsonObject
        val id = msg["id"] ?: return reply(ex, 202, "")
        val result = when ((msg["method"] as JsonPrimitive).content) {
            "initialize" -> """{"protocolVersion":"2025-06-18"}"""
            "tools/list" -> """{"tools":[{"name":"whoami"}]}"""
            else -> """{"content":[{"type":"text","text":"you are $auth"}]}"""
        }
        ex.responseHeaders.add("Mcp-Session-Id", "s1")
        json(ex, """{"jsonrpc":"2.0","id":$id,"result":$result}""")
    }

    /** Plays the user approving in the browser: returns the redirect the server would send. */
    private fun approve(authorizeUrl: String): URI {
        val q = form(URI(authorizeUrl).rawQuery)
        assertEquals("S256", q["code_challenge_method"])
        assertEquals(redirect, q["redirect_uri"])
        val code = "code-${codes.size + 1}"
        codes[code] = q["code_challenge"]!! to q["client_id"]!!
        return URI("$redirect?code=$code&state=${q["state"]}&iss=$base/auth")
    }

    @Test
    fun fullFlowFromA401() = runBlocking {
        // 1. Unauthenticated call fails with a challenge pointing at the resource metadata.
        val challenge = try {
            McpClient("$base/mcp").listTools()
            fail("expected 401"); return@runBlocking
        } catch (e: McpAuthException) {
            assertEquals(401, e.status)
            e.wwwAuthenticate
        }
        // 2. Discovery.
        val auth = OAuth.discover("$base/mcp", challenge)
        assertEquals("$base/auth", auth.issuer)
        assertEquals("$base/auth/token", auth.tokenEndpoint)
        assertEquals("$base/mcp", auth.resource)
        assertEquals("read write", auth.scope)
        assertTrue(auth.issInResponse)
        // 3. Registration with our redirect URI.
        val client = OAuth.register(auth, "Jarvis", redirect)
        assertEquals("client-123", client.clientId)
        assertTrue(registeredRedirect!!.contains(redirect))
        // 4. Authorize URL carries PKCE, state and the resource indicator.
        val pkce = OAuth.newPkce()
        val state = OAuth.newState()
        val url = OAuth.authorizeUrl(auth, client, redirect, pkce, state)
        val q = form(URI(url).rawQuery)
        assertEquals(state, q["state"])
        assertEquals("$base/mcp", q["resource"])
        assertEquals("read write", q["scope"])
        assertTrue("spaces must be %20", url.contains("scope=read%20write"))
        // 5. Code exchange (server verifies the PKCE verifier).
        val back = approve(url)
        val code = form(back.rawQuery)["code"]!!
        val tokens = OAuth.exchangeCode(auth, client, code, redirect, pkce.verifier)
        assertEquals("access-1", tokens.accessToken)
        assertEquals("refresh-1", tokens.refreshToken)
        assertNotNull(tokens.expiresAt)
        // A wrong verifier is rejected.
        val back2 = approve(OAuth.authorizeUrl(auth, client, redirect, OAuth.newPkce(), state))
        try {
            OAuth.exchangeCode(auth, client, form(back2.rawQuery)["code"]!!, redirect, pkce.verifier)
            fail("wrong verifier accepted")
        } catch (e: OAuth.OAuthException) {
            assertTrue(e.message!!.contains("invalid_grant"))
        }

        // 6. MCP calls with the token; after the server revokes it, the client refreshes and retries.
        var current = tokens
        val provider = object : McpAuth {
            override suspend fun token() = current.accessToken
            override suspend fun refresh(): Boolean {
                current = OAuth.refresh(auth, client, current.refreshToken!!)
                return true
            }
        }
        val mcp = McpClient("$base/mcp", provider)
        assertEquals(listOf("whoami"), mcp.listTools().map { it.name })
        validAccess.clear() // token expired server-side
        assertEquals("you are access-2", mcp.callTool("whoami", kotlinx.serialization.json.JsonObject(emptyMap())))
        assertEquals(1, refreshes.get())
        assertEquals("refresh-1", current.refreshToken) // kept when the server didn't rotate it
    }

    @Test
    fun discoveryWithoutHeaderUsesWellKnownPath() {
        sendResourceMetadataHeader = false
        val auth = OAuth.discover("$base/mcp", "Bearer")
        assertEquals("$base/auth/authorize", auth.authorizationEndpoint)
        assertEquals("read write", auth.scope) // from scopes_supported
    }

    @Test
    fun legacyServerWithoutResourceMetadata() {
        mode = "legacy"
        val auth = OAuth.discover("$base/mcp", null)
        assertEquals(base, auth.issuer)
        assertEquals("$base/token", auth.tokenEndpoint)
        assertEquals("$base/mcp", auth.resource)
        assertNull(auth.scope)
    }

    @Test
    fun bareServerFallsBackToDefaultPaths() {
        mode = "bare"
        val auth = OAuth.discover("$base/mcp", null)
        assertEquals("$base/authorize", auth.authorizationEndpoint)
        assertEquals("$base/register", auth.registrationEndpoint)
    }

    @Test
    fun parsesChallengeHeaders() {
        val c = OAuth.parseChallenge("""Bearer realm="OAuth", resource_metadata="https://x/y", scope="read write", error=invalid_token""")
        assertEquals("https://x/y", c["resource_metadata"])
        assertEquals("read write", c["scope"])
        assertEquals("invalid_token", c["error"])
        assertTrue(OAuth.parseChallenge("Bearer").isEmpty())
    }

    @Test
    fun canonicalResource() {
        assertEquals("https://mcp.example.com/mcp", OAuth.canonical("HTTPS://MCP.Example.com/mcp/"))
        assertEquals("https://mcp.example.com:8443", OAuth.canonical("https://mcp.example.com:8443/"))
    }

    @Test
    fun pkceIsS256OfVerifier() {
        val p = OAuth.newPkce()
        assertTrue(p.verifier.length in 43..128)
        val expected = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(p.verifier.toByteArray()))
        assertEquals(expected, p.challenge)
    }

    /** Real servers: discovery only (no registration, nothing created). Runs with MCP_LIVE_TEST=1. */
    @Test
    fun liveDiscovery() = runBlocking {
        assumeTrue(System.getenv("MCP_LIVE_TEST") == "1")
        for (url in listOf("https://mcp.notion.com/mcp", "https://mcp.linear.app/mcp")) {
            val header = try {
                McpClient(url).listTools(); null
            } catch (e: McpAuthException) {
                e.wwwAuthenticate
            }
            val auth = OAuth.discover(url, header)
            println("$url -> issuer=${auth.issuer} token=${auth.tokenEndpoint} register=${auth.registrationEndpoint} resource=${auth.resource} scope=${auth.scope}")
            assertNotNull(auth.registrationEndpoint)
            assertEquals(url, auth.resource)
        }
    }

    private fun form(s: String?): Map<String, String> = s.orEmpty().split("&").filter { it.isNotEmpty() }.associate {
        val (k, v) = (it.split("=", limit = 2) + "").take(2)
        URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
    }

    private fun json(ex: HttpExchange, body: String, code: Int = 200) {
        ex.responseHeaders.add("Content-Type", "application/json")
        reply(ex, code, body)
    }

    private fun reply(ex: HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray()
        ex.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) }
    }
}
