package com.gate6.jarvis

import android.app.Activity
import android.app.AlertDialog
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
import com.gate6.jarvis.mcp.McpRegistry
import com.gate6.jarvis.mcp.McpServer
import com.gate6.jarvis.mcp.McpServerStore
import com.gate6.jarvis.mcp.ToolCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Add, edit, test and remove MCP servers. Tokens are write-only here: they are never shown again. */
class ServersActivity : Activity() {
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
                v.findViewById<TextView>(android.R.id.text1).text = if (s.enabled) s.name else "${s.name} (off)"
                v.findViewById<TextView>(android.R.id.text2).text =
                    listOfNotNull(s.url, lastResult[s.id]).joinToString("\n")
                return v
            }
        }
        findViewById<ListView>(R.id.servers).apply {
            adapter = this@ServersActivity.adapter
            emptyView = findViewById(R.id.empty)
            setOnItemClickListener { _, _, pos, _ -> edit(this@ServersActivity.adapter.getItem(pos)) }
        }
        findViewById<Button>(R.id.add).setOnClickListener { edit(null) }
        refresh()
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
        }
        val clearToken = CheckBox(this).apply {
            text = "Remove saved token"
            visibility = if (hasToken) View.VISIBLE else View.GONE
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
            listOf(name, url, token, clearToken, enabled, trust).forEach(::addView)
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
            lastResult[server.id] = "Testing…"
            refresh()
            val result = McpRegistry.test(this@ServersActivity, server)
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
