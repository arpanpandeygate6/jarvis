package com.gate6.jarvis

import android.content.Context

/** Non-secret runtime settings. Secrets (MCP tokens) live in encrypted storage, never here. */
class JarvisConfig(context: Context) {
    private val prefs = context.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    var model: String
        get() = prefs.getString(KEY_MODEL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL
        set(v) = prefs.edit().putString(KEY_MODEL, v.trim()).apply()

    var voice: String
        get() = prefs.getString(KEY_VOICE, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_VOICE
        set(v) = prefs.edit().putString(KEY_VOICE, v.trim()).apply()

    var idleTimeoutSec: Int
        get() = prefs.getInt(KEY_IDLE, DEFAULT_IDLE_SEC)
        set(v) = prefs.edit().putInt(KEY_IDLE, v.coerceIn(5, 120)).apply()

    companion object {
        private const val KEY_MODEL = "model"
        private const val KEY_VOICE = "voice"
        private const val KEY_IDLE = "idle_timeout_sec"

        const val DEFAULT_MODEL = "gemini-3.1-flash-live-preview"
        /** Native-audio Live model listed in the Firebase AI Logic docs; used if [DEFAULT_MODEL] is rejected. */
        const val FALLBACK_MODEL = "gemini-2.5-flash-native-audio-preview-12-2025"
        const val DEFAULT_VOICE = "Charon"
        const val DEFAULT_IDLE_SEC = 20

        val SYSTEM_INSTRUCTION = """
            You are Jarvis, a hands-free voice assistant running on the user's phone.
            Your replies are spoken aloud: answer in one to three short, natural sentences.
            Never use markdown, lists, code, emoji, or read out URLs.
            Before a tool call that may take more than a moment, say a brief filler such as "One sec".
            Some tools have a required "user_confirmed" parameter because they change data. For those:
            first read the exact action back to the user and ask "Should I go ahead?". Only call the tool
            with user_confirmed set to true after the user clearly says yes in their latest reply.
            Never set user_confirmed to true on your own.
            When the user says goodbye or that they are done (for example "that's all, Jarvis" or
            "bye Jarvis"), say a very short goodbye and call end_conversation.
        """.trimIndent()
    }
}
