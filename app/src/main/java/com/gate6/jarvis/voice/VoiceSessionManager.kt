package com.gate6.jarvis.voice

import android.Manifest
import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.annotation.RequiresPermission
import com.gate6.jarvis.JarvisConfig
import com.google.firebase.ai.type.QuotaExceededException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

enum class SessionState { IDLE, CONNECTING, LISTENING, THINKING, SPEAKING, ERROR }

data class SessionStatus(val state: SessionState, val detail: String = "")

/**
 * Owns one conversation at a time: starts the [VoiceBrain], applies end conditions
 * (end_conversation tool, closing phrase, idle timeout) and reports [status].
 */
class VoiceSessionManager(
    private val context: Context,
    private val onSessionClosed: () -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val config = JarvisConfig(context)
    private var brain: VoiceBrain? = null
    private var idleJob: Job? = null
    private var ending = false
    private val heard = StringBuilder()

    val isActive: Boolean get() = brain != null

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() {
        if (brain != null) return
        ending = false
        heard.clear()
        val b = GeminiLiveBrain(context)
        brain = b
        publish(SessionState.CONNECTING)
        scope.launch {
            try {
                b.start(
                    BrainConfig(
                        model = config.model,
                        fallbackModel = JarvisConfig.FALLBACK_MODEL,
                        voice = config.voice,
                        systemInstruction = JarvisConfig.SYSTEM_INSTRUCTION,
                        tools = listOf(END_CONVERSATION),
                    ),
                    listener,
                )
            } catch (e: Exception) {
                Log.e(TAG, "Could not start voice session", e)
                fail(e)
            }
        }
    }

    /** Ends the session now (user long-press, Stop button). */
    fun stop(reason: String = "stopped") {
        val b = brain ?: return
        brain = null
        idleJob?.cancel()
        Log.i(TAG, "Session closed: $reason")
        scope.launch {
            b.stop()
            if (_status.value.state != SessionState.ERROR) publish(SessionState.IDLE)
            onSessionClosed()
        }
    }

    fun release() {
        stop("released")
        scope.cancel()
    }

    /** Lets the assistant finish its goodbye, then closes. */
    private fun endGracefully(reason: String) {
        if (ending) return
        ending = true
        val b = brain ?: return
        scope.launch {
            delay(GOODBYE_GRACE_MS)
            b.awaitTurnFinished(GOODBYE_MAX_MS)
            stop(reason)
        }
    }

    private val listener = object : VoiceBrain.Listener {
        override fun onState(state: BrainState) {
            scope.launch {
                if (brain == null) return@launch
                publish(
                    when (state) {
                        BrainState.CONNECTING -> SessionState.CONNECTING
                        BrainState.LISTENING -> SessionState.LISTENING
                        BrainState.THINKING -> SessionState.THINKING
                        BrainState.SPEAKING -> SessionState.SPEAKING
                    }
                )
                // The idle clock only runs while waiting on the user.
                if (state == BrainState.SPEAKING) idleJob?.cancel() else restartIdleTimer()
            }
        }

        override fun onUserTranscript(text: String) {
            scope.launch {
                restartIdleTimer()
                heard.append(text)
                if (heard.length > 200) heard.delete(0, heard.length - 200)
                _transcript.value = "You: ${heard.toString().trim()}"
                if (CLOSING_PHRASE.containsMatchIn(heard)) endGracefully("closing phrase")
            }
        }

        override fun onAssistantTranscript(text: String) {
            scope.launch {
                // A new assistant turn means the next user utterance starts fresh.
                heard.clear()
                _transcript.value = "Jarvis: $text"
            }
        }

        override suspend fun onToolCall(name: String, args: JsonObject): String {
            if (name == END_CONVERSATION.name) {
                scope.launch { endGracefully("end_conversation") }
                return "Conversation will close after your goodbye."
            }
            return "Error: unknown tool $name"
        }

        override fun onEnded(error: Throwable?) {
            scope.launch {
                if (brain == null) return@launch
                if (error != null) fail(error) else stop("server closed")
            }
        }
    }

    private fun restartIdleTimer() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(config.idleTimeoutSec * 1000L)
            stop("idle timeout")
        }
    }

    private fun fail(e: Throwable) {
        val message = when {
            e is QuotaExceededException || e.message?.contains("quota", ignoreCase = true) == true ->
                "Gemini free quota reached. Try again later."
            e is SecurityException -> "Microphone permission is missing."
            else -> "Voice session failed. ${e.message?.take(80).orEmpty()}"
        }
        publish(SessionState.ERROR, message)
        speak(message)
        stop("error: ${e.message}")
    }

    /** On-device TTS for errors only, created on demand (the Live session is already gone at this point). */
    private fun speak(text: String) {
        var tts: TextToSpeech? = null
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis-error")
                scope.launch { delay(6000); tts?.shutdown() }
            } else {
                tts?.shutdown()
            }
        }
    }

    private fun publish(state: SessionState, detail: String = "") {
        _status.value = SessionStatus(state, detail)
    }

    companion object {
        private const val TAG = "VoiceSession"
        private const val GOODBYE_GRACE_MS = 1200L
        private const val GOODBYE_MAX_MS = 4000L

        private val _status = MutableStateFlow(SessionStatus(SessionState.IDLE))
        val status: StateFlow<SessionStatus> = _status.asStateFlow()
        private val _transcript = MutableStateFlow("")
        val transcript: StateFlow<String> = _transcript.asStateFlow()

        private val CLOSING_PHRASE = Regex(
            """\b(that'?s all|that is all|bye|goodbye|good bye|stop listening)\W*jarvis\b""",
            RegexOption.IGNORE_CASE,
        )

        val END_CONVERSATION = ToolSpec(
            name = "end_conversation",
            description = "Close the voice session. Call after saying goodbye when the user is done.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("reason") {
                        put("type", "string")
                        put("description", "Optional short reason, e.g. user said goodbye.")
                    }
                }
            },
        )
    }
}
