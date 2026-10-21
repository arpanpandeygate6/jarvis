package com.gate6.jarvis.voice

import android.Manifest
import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.annotation.RequiresPermission
import com.gate6.jarvis.JarvisConfig
import com.gate6.jarvis.mcp.Confirmation
import com.gate6.jarvis.mcp.McpAuthException
import com.gate6.jarvis.mcp.McpRegistry
import com.gate6.jarvis.mcp.ToolArgs
import com.gate6.jarvis.mcp.ToolCatalog
import com.gate6.jarvis.mcp.ToolRoute
import com.google.firebase.ai.type.QuotaExceededException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

enum class SessionState { IDLE, CONNECTING, LISTENING, THINKING, SPEAKING, ERROR }

data class SessionStatus(val state: SessionState, val detail: String = "")

/**
 * Owns one conversation at a time: starts the [VoiceBrain], applies end conditions
 * (end_conversation tool, closing phrase, idle timeout), runs MCP tools and reports [status].
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
    private var catalog: ToolCatalog? = null

    // Turn tracking for the confirmation gate (main thread only).
    private val userTurn = StringBuilder()
    private val assistantTurn = StringBuilder()
    private var lastAssistantTurn = ""
    private var assistantSpokeLast = false
    private var userTurnId = 0
    private var approvedTurnId = -1
    private var approvedAt = 0L
    private var runningTools = 0

    val isActive: Boolean get() = brain != null

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() {
        if (brain != null) return
        ending = false
        heard.clear()
        resetTurns()
        val b = GeminiLiveBrain(context)
        brain = b
        publish(SessionState.CONNECTING)
        scope.launch {
            try {
                val loaded = try {
                    McpRegistry.load(context, TOOL_LOAD_TIMEOUT_MS)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Could not load MCP tools", e)
                    null
                }
                if (brain !== b) return@launch // stopped while tools were loading
                catalog = loaded?.catalog
                loaded?.let { _transcript.value = toolSummary(it) }
                b.start(
                    BrainConfig(
                        model = config.model,
                        fallbackModel = JarvisConfig.FALLBACK_MODEL,
                        voice = config.voice,
                        systemInstruction = JarvisConfig.SYSTEM_INSTRUCTION,
                        tools = listOf(END_CONVERSATION) + loaded?.catalog?.specs().orEmpty(),
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
                if (assistantSpokeLast) {
                    // The user is answering what the assistant just said.
                    lastAssistantTurn = assistantTurn.toString()
                    assistantTurn.clear()
                    userTurn.clear()
                    assistantSpokeLast = false
                    userTurnId++
                }
                userTurn.append(text)
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
                assistantTurn.append(text)
                assistantSpokeLast = true
                _transcript.value = "Jarvis: ${assistantTurn.toString().trim().takeLast(300)}"
            }
        }

        override suspend fun onToolCall(name: String, args: JsonObject): String {
            if (name == END_CONVERSATION.name) {
                scope.launch { endGracefully("end_conversation") }
                return "Conversation will close after your goodbye."
            }
            val route = catalog?.route(name) ?: return "Error: unknown tool $name"
            return runTool(route, args)
        }

        override fun onEnded(error: Throwable?) {
            scope.launch {
                if (brain == null) return@launch
                if (error != null) fail(error) else stop("server closed")
            }
        }
    }

    private suspend fun runTool(route: ToolRoute, args: JsonObject): String {
        if (route.needsConfirmation) {
            // Give the user's "yes" transcript a moment to arrive before judging it.
            delay(CONFIRM_SETTLE_MS)
            val flag = (args[Confirmation.PARAM] as? JsonPrimitive)?.booleanOrNull == true
            val verdict = withContext(Dispatchers.Main) { checkConfirmation(flag) }
            if (!verdict.allowed) {
                Log.i(TAG, "Blocked ${route.exposedName}: ${verdict.messageForModel}")
                return verdict.messageForModel
            }
        }
        val client = McpRegistry.clientFor(route.serverId) ?: return "Error: ${route.serverName} is not connected."
        val cleaned = ToolArgs.decode(JsonObject(args - Confirmation.PARAM), route.tool.inputSchema)
        Log.i(TAG, "Calling ${route.serverName}/${route.tool.name}")
        return try {
            withContext(Dispatchers.Main) {
                _transcript.value = "Jarvis is using ${route.serverName}: ${route.label}"
                // A slow tool is not the user going quiet.
                runningTools++
                idleJob?.cancel()
            }
            withTimeout(TOOL_TIMEOUT_MS) { client.callTool(route.tool.name, cleaned) }
        } catch (e: TimeoutCancellationException) {
            "Error: ${route.serverName} did not answer in time."
        } catch (e: CancellationException) {
            throw e
        } catch (e: McpAuthException) {
            "Error: ${route.serverName} rejected Jarvis's access token. The user needs to update it in settings."
        } catch (e: Exception) {
            Log.w(TAG, "Tool ${route.exposedName} failed", e)
            "Error: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            scope.launch {
                runningTools = (runningTools - 1).coerceAtLeast(0)
                if (brain != null) restartIdleTimer()
            }
        }
    }

    /**
     * One "yes" authorizes the data-changing calls the model makes right after it (a batch), not later
     * ones: a new action needs a new question and a new yes.
     */
    private fun checkConfirmation(flag: Boolean): Confirmation.Verdict {
        val now = System.currentTimeMillis()
        if (approvedTurnId == userTurnId && now - approvedAt > CONFIRM_BATCH_MS) {
            return Confirmation.Verdict(
                false,
                "Not executed: the user's yes was already used. Read this new action back and ask \"Should I go ahead?\".",
            )
        }
        val verdict = Confirmation.check(flag, lastAssistantTurn, userTurn.toString())
        if (verdict.allowed && approvedTurnId != userTurnId) {
            approvedTurnId = userTurnId
            approvedAt = now
        }
        return verdict
    }

    private fun resetTurns() {
        userTurn.clear()
        assistantTurn.clear()
        lastAssistantTurn = ""
        assistantSpokeLast = false
        userTurnId = 0
        approvedTurnId = -1
        approvedAt = 0L
        runningTools = 0
    }

    private fun toolSummary(r: McpRegistry.LoadResult): String = buildString {
        if (r.serverCount == 0) {
            append("No MCP servers set up: only chat is available.")
            return@buildString
        }
        append("Tools ready: ${r.catalog.size} from ${r.serverCount - r.failures.size} of ${r.serverCount} servers.")
        r.failures.forEach { (name, err) -> append("\n$name unavailable: ").append(err.take(80)) }
        if (r.catalog.skipped > 0) append("\n${r.catalog.skipped} tools left out (limit ${ToolCatalog.MAX_TOOLS}).")
    }

    private fun restartIdleTimer() {
        idleJob?.cancel()
        if (runningTools > 0) return
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
        private const val TOOL_LOAD_TIMEOUT_MS = 6000L
        private const val TOOL_TIMEOUT_MS = 50_000L
        private const val CONFIRM_SETTLE_MS = 400L
        private const val CONFIRM_BATCH_MS = 3000L

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
