package com.gate6.jarvis.mcp.oauth

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * OAuth 2.1 for MCP servers, following the MCP authorization spec: protected resource metadata
 * (RFC 9728) → authorization server metadata (RFC 8414 / OpenID discovery) → dynamic client
 * registration (RFC 7591) → authorization code with PKCE and the `resource` indicator (RFC 8707).
 *
 * Plain JVM code with blocking HTTP: call from a background thread.
 */
object OAuth {
    class OAuthException(message: String) : IOException(message)

    /** Everything needed to run the flow for one MCP server. */
    data class ServerAuth(
        val issuer: String,
        val authorizationEndpoint: String,
        val tokenEndpoint: String,
        val registrationEndpoint: String?,
        /** Value for the `resource` parameter: the MCP server's canonical URI. */
        val resource: String,
        val scope: String?,
        /** RFC 9207: the server adds `iss` to the redirect and we must check it. */
        val issInResponse: Boolean,
    )

    data class Client(
        val clientId: String,
        val clientSecret: String? = null,
        /** "none", "client_secret_basic" or "client_secret_post". */
        val authMethod: String = "none",
    )

    data class Tokens(
        val accessToken: String,
        val refreshToken: String?,
        /** Epoch millis, or null when the server did not say. */
        val expiresAt: Long?,
    )

    data class Pkce(val verifier: String, val challenge: String)

    private val json = Json { ignoreUnknownKeys = true }
    private val random = SecureRandom()

    // ---------------------------------------------------------------- discovery

    /**
     * Finds where to sign in for [mcpUrl]. [wwwAuthenticate] is the header from the server's 401,
     * if any; it usually points straight at the resource metadata.
     */
    fun discover(mcpUrl: String, wwwAuthenticate: String?): ServerAuth {
        val challenge = wwwAuthenticate?.let(::parseChallenge).orEmpty()
        val prm = resourceMetadata(mcpUrl, challenge["resource_metadata"])
        val issuer = (prm?.get("authorization_servers") as? JsonArray)?.firstNotNullOfOrNull { (it as? JsonPrimitive)?.contentOrNull }
            ?: origin(mcpUrl) // 2025-03-26 servers: the MCP server is its own authorization server
        val asMeta = authServerMetadata(issuer)
        val resource = prm?.str("resource") ?: canonical(mcpUrl)
        val scope = challenge["scope"]
            ?: (prm?.get("scopes_supported") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?.takeIf { it.isNotEmpty() }?.joinToString(" ")

        if (asMeta == null) {
            if (prm != null) throw OAuthException("Sign-in server $issuer publishes no metadata")
            // Last resort from the 2025-03-26 spec: default endpoint paths on the server's origin.
            return ServerAuth(issuer, "$issuer/authorize", "$issuer/token", "$issuer/register", resource, scope, false)
        }
        val methods = (asMeta["code_challenge_methods_supported"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        if (methods != null && "S256" !in methods) throw OAuthException("Sign-in server does not support PKCE (S256)")
        return ServerAuth(
            issuer = asMeta.str("issuer") ?: issuer,
            authorizationEndpoint = asMeta.str("authorization_endpoint") ?: throw OAuthException("No authorization endpoint"),
            tokenEndpoint = asMeta.str("token_endpoint") ?: throw OAuthException("No token endpoint"),
            registrationEndpoint = asMeta.str("registration_endpoint"),
            resource = resource,
            scope = scope,
            issInResponse = (asMeta["authorization_response_iss_parameter_supported"] as? JsonPrimitive)?.contentOrNull == "true",
        )
    }

    private fun resourceMetadata(mcpUrl: String, fromHeader: String?): JsonObject? {
        val u = URI(mcpUrl)
        val path = u.rawPath.orEmpty().trimEnd('/')
        val candidates = listOfNotNull(
            fromHeader,
            if (path.isNotEmpty()) "${origin(mcpUrl)}/.well-known/oauth-protected-resource$path" else null,
            "${origin(mcpUrl)}/.well-known/oauth-protected-resource",
        ).distinct()
        return candidates.firstNotNullOfOrNull { getJsonOrNull(it) }?.takeIf { it["authorization_servers"] is JsonArray }
    }

    private fun authServerMetadata(issuer: String): JsonObject? {
        val o = origin(issuer)
        val path = URI(issuer).rawPath.orEmpty().trimEnd('/')
        val candidates = if (path.isEmpty()) {
            listOf("$o/.well-known/oauth-authorization-server", "$o/.well-known/openid-configuration")
        } else {
            listOf(
                "$o/.well-known/oauth-authorization-server$path",
                "$o/.well-known/openid-configuration$path",
                "$o$path/.well-known/openid-configuration",
            )
        }
        return candidates.firstNotNullOfOrNull { getJsonOrNull(it) }?.takeIf { it["token_endpoint"] != null }
    }

    /** Parses `Bearer realm="x", resource_metadata="y", scope="a b"` into a map. */
    fun parseChallenge(header: String): Map<String, String> {
        val params = header.substringAfter(' ', "")
        return Regex("""([A-Za-z_]+)\s*=\s*(?:"((?:[^"\\]|\\.)*)"|([^\s,]+))""").findAll(params).associate { m ->
            m.groupValues[1].lowercase() to (m.groups[2]?.value?.replace("\\\"", "\"") ?: m.groupValues[3])
        }
    }

    // ---------------------------------------------------------------- registration + flow

    fun register(auth: ServerAuth, clientName: String, redirectUri: String): Client {
        val endpoint = auth.registrationEndpoint
            ?: throw OAuthException("This server does not support automatic registration")
        val body = buildJsonObject {
            put("client_name", clientName)
            put("redirect_uris", buildJsonArray { add(JsonPrimitive(redirectUri)) })
            put("grant_types", buildJsonArray { add(JsonPrimitive("authorization_code")); add(JsonPrimitive("refresh_token")) })
            put("response_types", buildJsonArray { add(JsonPrimitive("code")) })
            put("token_endpoint_auth_method", "none")
            auth.scope?.let { put("scope", it) }
        }
        val reply = postJson(endpoint, body)
        return Client(
            clientId = reply.str("client_id") ?: throw OAuthException("Registration returned no client_id"),
            clientSecret = reply.str("client_secret"),
            authMethod = reply.str("token_endpoint_auth_method") ?: if (reply.str("client_secret") != null) "client_secret_basic" else "none",
        )
    }

    fun newPkce(): Pkce {
        val bytes = ByteArray(48).also(random::nextBytes)
        val verifier = b64url(bytes)
        val challenge = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        return Pkce(verifier, challenge)
    }

    fun newState(): String = b64url(ByteArray(24).also(random::nextBytes))

    fun authorizeUrl(auth: ServerAuth, client: Client, redirectUri: String, pkce: Pkce, state: String): String {
        val params = linkedMapOf(
            "response_type" to "code",
            "client_id" to client.clientId,
            "redirect_uri" to redirectUri,
            "code_challenge" to pkce.challenge,
            "code_challenge_method" to "S256",
            "state" to state,
            "resource" to auth.resource,
        )
        auth.scope?.let { params["scope"] = it }
        val sep = if ('?' in auth.authorizationEndpoint) "&" else "?"
        // %20 rather than "+" for spaces: some servers read the query with a strict URI decoder.
        return auth.authorizationEndpoint + sep + form(params).replace("+", "%20")
    }

    fun exchangeCode(auth: ServerAuth, client: Client, code: String, redirectUri: String, verifier: String): Tokens =
        token(auth, client, linkedMapOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to redirectUri,
            "code_verifier" to verifier,
            "resource" to auth.resource,
        ), previousRefresh = null)

    fun refresh(auth: ServerAuth, client: Client, refreshToken: String): Tokens =
        token(auth, client, linkedMapOf(
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken,
            "resource" to auth.resource,
        ), previousRefresh = refreshToken)

    private fun token(auth: ServerAuth, client: Client, params: LinkedHashMap<String, String>, previousRefresh: String?): Tokens {
        val headers = mutableMapOf<String, String>()
        when (client.authMethod) {
            "client_secret_basic" -> {
                val secret = client.clientSecret ?: throw OAuthException("Missing client secret")
                val pair = enc(client.clientId) + ":" + enc(secret)
                headers["Authorization"] = "Basic " + Base64.getEncoder().encodeToString(pair.toByteArray())
            }
            "client_secret_post" -> {
                params["client_id"] = client.clientId
                params["client_secret"] = client.clientSecret ?: throw OAuthException("Missing client secret")
            }
            else -> params["client_id"] = client.clientId
        }
        val reply = postForm(auth.tokenEndpoint, params, headers)
        val access = reply.str("access_token") ?: throw OAuthException("Token response has no access_token")
        val expiresIn = (reply["expires_in"] as? JsonPrimitive)?.longOrNull
        return Tokens(
            accessToken = access,
            // Servers may rotate refresh tokens; keep the old one when they don't send a new one.
            refreshToken = reply.str("refresh_token") ?: previousRefresh,
            expiresAt = expiresIn?.let { System.currentTimeMillis() + it * 1000 },
        )
    }

    // ---------------------------------------------------------------- serialization

    fun ServerAuth.toJson() = buildJsonObject {
        put("issuer", issuer); put("authorization_endpoint", authorizationEndpoint); put("token_endpoint", tokenEndpoint)
        registrationEndpoint?.let { put("registration_endpoint", it) }
        put("resource", resource); scope?.let { put("scope", it) }; put("iss_in_response", issInResponse)
    }

    fun serverAuthFrom(o: JsonObject) = ServerAuth(
        issuer = o.str("issuer")!!, authorizationEndpoint = o.str("authorization_endpoint")!!,
        tokenEndpoint = o.str("token_endpoint")!!, registrationEndpoint = o.str("registration_endpoint"),
        resource = o.str("resource")!!, scope = o.str("scope"), issInResponse = o.str("iss_in_response") == "true",
    )

    fun Client.toJson() = buildJsonObject {
        put("client_id", clientId); clientSecret?.let { put("client_secret", it) }; put("auth_method", authMethod)
    }

    fun clientFrom(o: JsonObject) = Client(o.str("client_id")!!, o.str("client_secret"), o.str("auth_method") ?: "none")

    fun Tokens.toJson() = buildJsonObject {
        put("access_token", accessToken); refreshToken?.let { put("refresh_token", it) }; expiresAt?.let { put("expires_at", it) }
    }

    fun tokensFrom(o: JsonObject) = Tokens(
        o.str("access_token")!!, o.str("refresh_token"), (o["expires_at"] as? JsonPrimitive)?.longOrNull,
    )

    fun parse(text: String): JsonObject = json.parseToJsonElement(text) as JsonObject

    // ---------------------------------------------------------------- http

    private fun getJsonOrNull(url: String): JsonObject? = runCatching {
        val c = open(url, "GET")
        try {
            if (c.responseCode != 200) null else parse(c.inputStream.bufferedReader().use { it.readText() })
        } finally {
            c.disconnect()
        }
    }.getOrNull()

    private fun postJson(url: String, body: JsonObject): JsonObject {
        val c = open(url, "POST")
        try {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            return readReply(c, "Registration")
        } finally {
            c.disconnect()
        }
    }

    private fun postForm(url: String, params: Map<String, String>, headers: Map<String, String>): JsonObject {
        val c = open(url, "POST")
        try {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            c.outputStream.use { it.write(form(params).toByteArray()) }
            return readReply(c, "Token request")
        } finally {
            c.disconnect()
        }
    }

    private fun readReply(c: HttpURLConnection, what: String): JsonObject {
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            val err = runCatching { parse(text) }.getOrNull()
            val detail = listOfNotNull(err?.str("error"), err?.str("error_description")).joinToString(": ").ifEmpty { text.take(200) }
            throw OAuthException("$what failed (HTTP $code): $detail")
        }
        return runCatching { parse(text) }.getOrElse { throw OAuthException("$what returned invalid JSON") }
    }

    private fun open(url: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
        }

    // ---------------------------------------------------------------- helpers

    fun origin(url: String): String {
        val u = URI(url)
        val port = if (u.port == -1) "" else ":${u.port}"
        return "${u.scheme.lowercase()}://${u.host.lowercase()}$port"
    }

    /** RFC 8707 canonical URI: lowercase scheme and host, no fragment, no trailing slash. */
    fun canonical(url: String): String {
        val u = URI(url)
        val path = u.rawPath.orEmpty().trimEnd('/')
        val query = u.rawQuery?.let { "?$it" }.orEmpty()
        return origin(url) + path + query
    }

    private fun form(params: Map<String, String>) = params.entries.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun b64url(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
}
