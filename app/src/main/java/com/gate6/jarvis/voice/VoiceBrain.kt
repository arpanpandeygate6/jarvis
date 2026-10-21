package com.gate6.jarvis.voice

import kotlinx.serialization.json.JsonObject

enum class BrainState { CONNECTING, LISTENING, THINKING, SPEAKING }

/** Provider-neutral tool definition; [parameters] is a plain JSON Schema object (as MCP returns it). */
data class ToolSpec(val name: String, val description: String, val parameters: JsonObject)

data class BrainConfig(
    /** Live models to try in order; the next one is used when one is out of quota or rejected. */
    val models: List<String>,
    val voice: String,
    val systemInstruction: String,
    /** Called after the mic is open, so slow tool discovery overlaps with the user speaking. */
    val tools: suspend () -> List<ToolSpec>,
    /** Say "Hi! How can I help?" at the start, unless the user is already talking. */
    val greet: Boolean = true,
)

/**
 * A realtime speech-to-speech backend. Owns the mic and speaker while started.
 * Implementations: [GeminiLiveBrain]. An OpenAI Realtime one can be added without touching callers.
 */
interface VoiceBrain {
    interface Listener {
        fun onState(state: BrainState)

        /** Partial transcript of the user's speech. Any call means the user is talking. */
        fun onUserTranscript(text: String)

        fun onAssistantTranscript(text: String)

        /**
         * Runs a tool and returns a compact text result for the model, or null to send no result
         * (the model would answer a result with another spoken turn, e.g. a second goodbye).
         */
        suspend fun onToolCall(name: String, args: JsonObject): String?

        /** Which model the session connected to. */
        fun onConnected(model: String) {}

        /** [model] could not be used (out of quota, rejected, unreachable); the next one will be tried. */
        fun onModelFailed(model: String, error: Throwable) {}

        /** The connection ended without [VoiceBrain.stop] being called. [error] is null on a clean server close. */
        fun onEnded(error: Throwable?)
    }

    /**
     * Opens the mic, then connects; speech captured while connecting is buffered and sent once the
     * session is up. Throws if the connection cannot be established.
     */
    suspend fun start(config: BrainConfig, listener: Listener)

    /** Waits (up to [timeoutMs]) until the current assistant turn has finished playing. */
    suspend fun awaitTurnFinished(timeoutMs: Long)

    /** Closes the connection and releases the mic and speaker. Safe to call repeatedly. */
    suspend fun stop()
}
