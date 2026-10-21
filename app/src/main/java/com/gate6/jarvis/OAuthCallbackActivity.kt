package com.gate6.jarvis

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import com.gate6.jarvis.mcp.McpRegistry
import com.gate6.jarvis.mcp.oauth.McpOAuth
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Receives the browser redirect (com.gate6.jarvis://oauth/callback), finishes sign-in, returns to the server list. */
class OAuthCallbackActivity : Activity() {
    private val scope = MainScope()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        setContentView(TextView(this).apply {
            text = "Finishing sign-in…"
            textSize = 18f
            setPadding(pad, pad, pad, pad)
        })
        val data = intent?.data
        if (data == null) {
            finish()
            return
        }
        scope.launch {
            val back = Intent(this@OAuthCallbackActivity, ServersActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            try {
                val serverId = McpOAuth.complete(this@OAuthCallbackActivity, data)
                McpRegistry.forget(serverId)
                back.putExtra(ServersActivity.EXTRA_SIGNED_IN, serverId)
            } catch (e: Exception) {
                Log.w("OAuthCallback", "Sign-in failed", e)
                back.putExtra(ServersActivity.EXTRA_SIGN_IN_ERROR, e.message ?: "Sign-in failed")
            }
            startActivity(back)
            finish()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
