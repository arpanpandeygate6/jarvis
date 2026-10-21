package com.gate6.jarvis.mcp

import java.io.BufferedReader

/**
 * Minimal Server-Sent Events reader: returns the joined `data:` lines of each event.
 * Event names, ids and retry hints are ignored; MCP only needs the JSON payloads.
 */
class SseReader(private val reader: BufferedReader) {
    /** Next event's data, or null at end of stream. Events without data lines are skipped. */
    fun next(): String? {
        val data = StringBuilder()
        var hasData = false
        while (true) {
            val line = reader.readLine() ?: return if (hasData) data.toString() else null
            when {
                line.isEmpty() -> if (hasData) return data.toString()
                line.startsWith(":") -> Unit // comment / keep-alive
                line.startsWith("data:") -> {
                    if (hasData) data.append('\n')
                    data.append(line.removePrefix("data:").removePrefix(" "))
                    hasData = true
                }
                else -> Unit // event:, id:, retry:
            }
        }
    }
}
