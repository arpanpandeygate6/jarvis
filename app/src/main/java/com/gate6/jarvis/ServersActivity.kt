package com.gate6.jarvis

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import com.gate6.jarvis.mcp.McpAuthException
import com.gate6.jarvis.mcp.McpRegistry
import com.gate6.jarvis.mcp.McpServer
import com.gate6.jarvis.mcp.McpServerStore
import com.gate6.jarvis.mcp.ToolCatalog
import com.gate6.jarvis.mcp.oauth.McpOAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Add, edit, test and remove MCP servers. Tokens are write-only here: they are never shown again. */
class ServersActivity : Activity() {
    companion object {
        const val EXTRA_SIGNED_IN = "signed_in_server"
        const val EXTRA_SIGN_IN_ERROR = "sign_in_error"
    }

    private val scope = MainScope()
    private lateinit var store: McpServerStore
    private lateinit var adapter: ArrayAdapter<McpServer>
    private val lastResult = mutableMapOf<String, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_servers)
        store = McpServerStore(this)

        adapter = object : ArrayAdapter<McpServer>(this, android.R.layout.simple_list_item_2, android.R.id.text1) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                val s = getItem(position)!!
                v.setBackgroundResource(R.drawable.bg_row)
                val pad = (16 * resources.displayMetrics.density).toInt()
                v.setPadding(pad, pad * 3 / 4, pad, pad * 3 / 4)
                v.findViewById<TextView>(android.R.id.text1).apply {
                    text = if (s.enabled) s.name else "${s.name} (off)"
                    setTextColor(getColor(R.color.text))
                    textSize = 17f
                }
                v.findViewById<TextView>(android.R.id.text2).setTextColor(getColor(R.color.text_muted))
                val signedIn = if (McpOAuth.isSignedIn(context, s.id)) "Signed in" else null
                v.findViewById<TextView>(android.R.id.text2).text =
                    listOfNotNull(s.url, signedIn, lastResult[s.id]).joinToString("\n")
                return v
            }
        }
        findViewById<ListView>(R.id.servers).apply {
            adapter = this@ServersActivity.adapter
            emptyView = this@ServersActivity.findViewById(R.id.empty) // the activity's view, not a ListView child
            setOnItemClickListener { _, _, pos, _ -> edit(this@ServersActivity.adapter.getItem(pos)) }
        }
        findViewById<Button>(R.id.add).setOnClickListener { edit(null) }
        refresh()
        handleSignInResult(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSignInResult(intent)
    }

    /** Result of the browser sign-in, delivered by [OAuthCallbackActivity]. */
    private fun handleSignInResult(intent: Intent?) {
        intent ?: return
        intent.getStringExtra(EXTRA_SIGN_IN_ERROR)?.let {
            Toast.makeText(this, it, Toast.LENGTH_LONG).show()
            intent.removeExtra(EXTRA_SIGN_IN_ERROR)
        }
        intent.getStringExtra(EXTRA_SIGNED_IN)?.let { id ->
            intent.removeExtra(EXTRA_SIGNED_IN)
            scope.launch {
                val server = withContext(Dispatchers.IO) { store.all() }.firstOrNull { it.id == id } ?: return@launch
                test(server, offerSignIn = false)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun refresh() {
        scope.launch {
            val servers = withContext(Dispatchers.IO) { store.all() }
            adapter.clear()
            adapter.addAll(servers)
        }
    }

    private fun edit(existing: McpServer?) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val name = EditText(this).apply {
            hint = "Name (e.g. GitHub)"
            setSingleLine()
            setText(existing?.name)
        }
        val url = EditText(this).apply {
            hint = "https://server.example.com/mcp"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(existing?.url)
        }
        val hasToken = existing != null && store.hasToken(existing.id)
        val token = EditText(this).apply {
            hint = if (hasToken) "Access token (saved; leave blank to keep)" else "Access token (optional)"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            typeface = android.graphics.Typeface.DEFAULT // password fields default to monospace
        }
        val clearToken = CheckBox(this).apply {
            text = "Remove saved token"
            visibility = if (hasToken) View.VISIBLE else View.GONE
        }
        val signedIn = existing != null && McpOAuth.isSignedIn(this, existing.id)
        val signOut = CheckBox(this).apply {
            text = "Sign out"
            visibility = if (signedIn) View.VISIBLE else View.GONE
        }
        val enabled = CheckBox(this).apply {
            text = "Use in conversations"
            isChecked = existing?.enabled ?: true
        }
        val trust = CheckBox(this).apply {
            text = "Skip the yes/no for tools this server doesn't mark (only for read-only servers)"
            isChecked = existing?.trustUnmarked ?: false
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            listOf(name, url, token, clearToken, signOut, enabled, trust).forEach(::addView)
        }

        val builder = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Add MCP server" else "Edit ${existing.name}")
            .setView(form)
            .setPositiveButton("Save and test", null)
            .setNegativeButton("Cancel", null)
        if (existing != null) builder.setNeutralButton("Delete") { _, _ -> confirmDelete(existing) }
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val n = name.text.toString().trim()
                val u = url.text.toString().trim()
                when {
                    n.isEmpty() -> name.error = "Required"
                    !McpServerStore.validUrl(u) -> url.error = "Must be an https:// URL"
                    else -> {
                        val server = McpServer(id = existing?.id ?: java.util.UUID.randomUUID().toString(), name = n, url = u, enabled = enabled.isChecked, trustUnmarked = trust.isChecked)
                        val newToken = when {
                            clearToken.isChecked -> ""
                            token.text.isNotBlank() -> token.text.toString()
                            existing == null -> ""
                            else -> null // keep
                        }
                        dialog.dismiss()
                        if (signOut.isChecked) McpOAuth.signOut(this, server.id)
                        saveAndTest(server, newToken)
                    }
                }
            }
        }
        dialog.show()
    }

    private fun saveAndTest(server: McpServer, token: String?) {
        scope.launch {
            withContext(Dispatchers.IO) { store.save(server, token) }
            McpRegistry.forget(server.id)
            test(server, offerSignIn = true)
        }
    }

    private suspend fun test(server: McpServer, offerSignIn: Boolean) {
        lastResult[server.id] = "Testing…"
        refresh()
        val result = McpRegistry.test(this@ServersActivity, server)
        val authError = result.exceptionOrNull() as? McpAuthException
        if (offerSignIn && authError?.status == 401 && !store.hasToken(server.id)) {
            lastResult[server.id] = "Needs sign-in"
            adapter.notifyDataSetChanged()
            offerSignIn(server, authError.wwwAuthenticate)
            return
        }
        val summary = result.fold(
            onSuccess = { tools ->
                val writes = tools.count { ToolCatalog.needsConfirmation(it, server.trustUnmarked) }
                "Connected: ${tools.size} tools ($writes ask before running)"
            },
            onFailure = { "Failed: ${it.message ?: it.javaClass.simpleName}" },
        )
        lastResult[server.id] = summary
        adapter.notifyDataSetChanged()
        Toast.makeText(this@ServersActivity, "${server.name}: $summary", Toast.LENGTH_LONG).show()
    }

    private fun offerSignIn(server: McpServer, wwwAuthenticate: String?) {
        AlertDialog.Builder(this)
            .setTitle("Sign in to ${server.name}?")
            .setMessage("This server needs you to sign in. Jarvis will open your browser; after you approve, you come back here.")
            .setPositiveButton("Sign in") { _, _ -> signIn(server, wwwAuthenticate) }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun signIn(server: McpServer, wwwAuthenticate: String?) {
        scope.launch {
            try {
                val url = McpOAuth.begin(this@ServersActivity, server, wwwAuthenticate)
                CustomTabsIntent.Builder().build().launchUrl(this@ServersActivity, Uri.parse(url))
            } catch (e: Exception) {
                lastResult[server.id] = "Sign-in failed: ${e.message}"
                adapter.notifyDataSetChanged()
                Toast.makeText(this@ServersActivity, "Could not start sign-in: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun confirmDelete(server: McpServer) {
        AlertDialog.Builder(this)
            .setMessage("Remove ${server.name} and its saved token?")
            .setPositiveButton("Remove") { _, _ ->
                scope.launch {
                    withContext(Dispatchers.IO) { store.delete(server.id) }
                    McpRegistry.forget(server.id)
                    lastResult.remove(server.id)
                    refresh()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
