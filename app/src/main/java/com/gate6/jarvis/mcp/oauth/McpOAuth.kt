package com.gate6.jarvis.mcp.oauth

import android.content.Context
import android.net.Uri
import android.util.Log
import com.gate6.jarvis.mcp.McpAuth
import com.gate6.jarvis.mcp.McpServer
import com.gate6.jarvis.mcp.SecretStore
import com.gate6.jarvis.mcp.oauth.OAuth.toJson
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Android side of MCP sign-in: runs discovery and registration, keeps the in-flight PKCE state
 * across the trip to the browser, finishes the code exchange from the redirect, and stores and
 * refreshes tokens. Everything is kept in [SecretStore] (Keystore-encrypted).
 */
object McpOAuth {
    private const val TAG = "McpOAuth"
    const val REDIRECT_URI = "com.gate6.jarvis://oauth/callback"
    private const val PENDING = "oauth_pending"
    private const val REFRESH_MARGIN_MS = 60_000L

    private val locks = ConcurrentHashMap<String, Mutex>()

    private fun key(serverId: String) = "oauth_$serverId"

    private data class Stored(val auth: OAuth.ServerAuth, val client: OAuth.Client, val tokens: OAuth.Tokens?)

    fun isSignedIn(context: Context, serverId: String): Boolean = load(context, serverId)?.tokens != null

    fun signOut(context: Context, serverId: String) = SecretStore(context).put(key(serverId), null)

    /** Discovers the sign-in server, registers Jarvis if needed, and returns the URL to open in a browser. */
    suspend fun begin(context: Context, server: McpServer, wwwAuthenticate: String?): String =
        runInterruptible(Dispatchers.IO) {
            val auth = OAuth.discover(server.url, wwwAuthenticate)
            val previous = load(context, server.id)
            val client = previous?.client?.takeIf { previous.auth.issuer == auth.issuer && previous.auth.tokenEndpoint == auth.tokenEndpoint }
                ?: OAuth.register(auth, "Jarvis voice assistant", REDIRECT_URI)
            // Remember the client even before sign-in finishes, so a retry does not register again.
            save(context, server.id, Stored(auth, client, previous?.tokens))
            val pkce = OAuth.newPkce()
            val state = OAuth.newState()
            SecretStore(context).put(PENDING, buildJsonObject {
                put("server_id", server.id)
                put("state", state)
                put("verifier", pkce.verifier)
                put("created", System.currentTimeMillis())
            }.toString())
            OAuth.authorizeUrl(auth, client, REDIRECT_URI, pkce, state)
        }

    /** Handles the browser redirect. Returns the server id that is now signed in. */
    suspend fun complete(context: Context, redirect: Uri): String = runInterruptible(Dispatchers.IO) {
        val secrets = SecretStore(context)
        val pending = secrets.get(PENDING)?.let(OAuth::parse) ?: throw OAuth.OAuthException("No sign-in in progress")
        // Check state before consuming: a stray or forged redirect must not cancel the real sign-in.
        if (redirect.getQueryParameter("state") != pending.string("state")) {
            throw OAuth.OAuthException("Sign-in response did not match the request (state)")
        }
        secrets.put(PENDING, null) // single use
        val serverId = pending.string("server_id")
        if (System.currentTimeMillis() - pending.string("created").toLong() > PENDING_MAX_AGE_MS) {
            throw OAuth.OAuthException("Sign-in took too long; please try again")
        }
        redirect.getQueryParameter("error")?.let { err ->
            val desc = redirect.getQueryParameter("error_description")
            throw OAuth.OAuthException(if (err == "access_denied") "Sign-in was cancelled" else listOfNotNull(err, desc).joinToString(": "))
        }
        val stored = load(context, serverId) ?: throw OAuth.OAuthException("Server was removed during sign-in")
        if (stored.auth.issInResponse && redirect.getQueryParameter("iss") != stored.auth.issuer) {
            throw OAuth.OAuthException("Sign-in response came from an unexpected server")
        }
        val code = redirect.getQueryParameter("code") ?: throw OAuth.OAuthException("Sign-in response had no code")
        val tokens = OAuth.exchangeCode(stored.auth, stored.client, code, REDIRECT_URI, pending.string("verifier"))
        save(context, serverId, stored.copy(tokens = tokens))
        Log.i(TAG, "Signed in to ${stored.auth.issuer}")
        serverId
    }

    /** Token source for [com.gate6.jarvis.mcp.McpClient]; refreshes ahead of expiry and after a 401. */
    class TokenProvider(context: Context, private val serverId: String) : McpAuth {
        private val app = context.applicationContext

        override suspend fun token(): String? = lock().withLock {
            val s = load(app, serverId)?.tokens ?: return@withLock null
            val exp = s.expiresAt
            if (exp != null && exp - REFRESH_MARGIN_MS < System.currentTimeMillis() && s.refreshToken != null) {
                refreshLocked()?.accessToken ?: s.accessToken
            } else {
                s.accessToken
            }
        }

        override suspend fun refresh(): Boolean = lock().withLock { refreshLocked() != null }

        private suspend fun refreshLocked(): OAuth.Tokens? {
            val stored = load(app, serverId) ?: return null
            val refreshToken = stored.tokens?.refreshToken ?: return null
            return try {
                val fresh = runInterruptible(Dispatchers.IO) { OAuth.refresh(stored.auth, stored.client, refreshToken) }
                save(app, serverId, stored.copy(tokens = fresh))
                fresh
            } catch (e: OAuth.OAuthException) {
                if (e.grantRejected) {
                    Log.w(TAG, "Refresh token rejected; sign-in needed", e)
                    save(app, serverId, stored.copy(tokens = null)) // forces a new sign-in
                } else {
                    Log.w(TAG, "Token refresh failed for now; keeping the sign-in", e)
                }
                null
            } catch (e: java.io.IOException) {
                Log.w(TAG, "Token refresh network error; keeping the sign-in", e)
                null
            }
        }

        private fun lock() = locks.getOrPut(serverId) { Mutex() }
    }

    private fun load(context: Context, serverId: String): Stored? {
        val o = SecretStore(context).get(key(serverId))?.let(OAuth::parse) ?: return null
        return runCatching {
            Stored(
                OAuth.serverAuthFrom(o["auth"] as JsonObject),
                OAuth.clientFrom(o["client"] as JsonObject),
                (o["tokens"] as? JsonObject)?.let(OAuth::tokensFrom),
            )
        }.getOrNull()
    }

    private fun save(context: Context, serverId: String, s: Stored) {
        SecretStore(context).put(key(serverId), buildJsonObject {
            put("auth", s.auth.toJson())
            put("client", s.client.toJson())
            s.tokens?.let { put("tokens", it.toJson()) }
        }.toString())
    }

    private const val PENDING_MAX_AGE_MS = 15 * 60_000L

    private fun JsonObject.string(k: String): String =
        (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: throw OAuth.OAuthException("Corrupt sign-in state")
}
