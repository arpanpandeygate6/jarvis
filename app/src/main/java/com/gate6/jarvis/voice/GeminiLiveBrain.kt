package com.gate6.jarvis.voice

import android.Manifest
import android.content.Context
import android.util.Log
import androidx.annotation.RequiresPermission
import com.google.firebase.Firebase
import com.google.firebase.ai.LiveGenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.AudioTranscriptionConfig
import com.google.firebase.ai.type.ContextWindowCompressionConfig
import com.google.firebase.ai.type.FunctionCallPart
import com.google.firebase.ai.type.FunctionResponsePart
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.InlineData
import com.google.firebase.ai.type.InlineDataPart
import com.google.firebase.ai.type.LiveServerContent
import com.google.firebase.ai.type.LiveServerGoAway
import com.google.firebase.ai.type.LiveServerToolCall
import com.google.firebase.ai.type.LiveServerToolCallCancellation
import com.google.firebase.ai.type.LiveSession
import com.google.firebase.ai.type.LiveSessionResumptionUpdate
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.QuotaExceededException
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.SessionResumptionConfig
import com.google.firebase.ai.type.SlidingWindow
import com.google.firebase.ai.type.SpeechConfig
import com.google.firebase.ai.type.Tool
import com.google.firebase.ai.type.Voice
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.liveGenerationConfig
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * [VoiceBrain] on the Gemini Live API via Firebase AI Logic (Gemini Developer API backend).
 *
 * Audio is hand-rolled ([AudioIO]) instead of the SDK's startAudioConversation(): that helper
 * exposes no playback start/stop events (needed for the speaking/listening bubble states), takes a
 * non-suspending function handler (MCP calls are network I/O), and plays on USAGE_MEDIA, which
 * weakens echo cancellation on speakerphone.
 */
@OptIn(PublicPreviewAPI::class)
class GeminiLiveBrain(private val context: Context) : VoiceBrain {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val toolJobs = ConcurrentHashMap<String, Job>()

    private var session: LiveSession? = null
    private var audio: AudioIO? = null
    private lateinit var listener: VoiceBrain.Listener

    @Volatile private var stopping = false
    @Volatile private var turnDone = true
    @Volatile private var resumeHandle: String? = null
    @Volatile private var state: BrainState? = null
    private var thinkingDebounce: Job? = null

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override suspend fun start(config: BrainConfig, listener: VoiceBrain.Listener) {
        this.listener = listener
        stopping = false
        turnDone = true
        resumeHandle = null
        setState(BrainState.CONNECTING)

        val live = try {
            connect(config, config.model)
        } catch (e: Exception) {
            val fallback = config.fallbackModel
            if (e is CancellationException || e is QuotaExceededException || fallback == null || fallback == config.model) throw e
            Log.w(TAG, "Model ${config.model} failed (${e.message}); falling back to $fallback")
            connect(config, fallback)
        }
        session = live

        val io = AudioIO(context)
        io.onPlaybackStarted = { setState(BrainState.SPEAKING) }
        io.onPlaybackDrained = { if (turnDone) setState(BrainState.LISTENING) }
        audio = io

        // Bounded so a stalled socket can't grow memory; stale mic audio is worthless anyway.
        val mic = Channel<ByteArray>(capacity = 40, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        try {
            io.start { mic.trySend(it) }
        } catch (e: Exception) {
            runCatching { live.close() }
            session = null
            audio = null
            throw e
        }
        scope.launch {
            for (chunk in mic) {
                runCatching { session?.sendAudioRealtime(InlineData(chunk, MIC_MIME)) }
                    .onFailure { if (it is CancellationException) throw it }
            }
        }
        scope.launch { receiveLoop(live, io) }
        setState(BrainState.LISTENING)
    }

    private suspend fun connect(config: BrainConfig, modelName: String): LiveSession {
        Log.i(TAG, "Connecting to $modelName")
        val declarations = config.tools.mapNotNull { GeminiSchema.toDeclaration(it) }
        val model: LiveGenerativeModel =
            Firebase.ai(backend = GenerativeBackend.googleAI(), useLimitedUseAppCheckTokens = true).liveModel(
                modelName = modelName,
                generationConfig = liveGenerationConfig {
                    responseModality = ResponseModality.AUDIO
                    speechConfig = SpeechConfig(voice = Voice(config.voice))
                    inputAudioTranscription = AudioTranscriptionConfig()
                    outputAudioTranscription = AudioTranscriptionConfig()
                    // Lets long conversations exceed the audio-only session cap instead of being cut off.
                    contextWindowCompression = ContextWindowCompressionConfig(slidingWindow = SlidingWindow())
                },
                tools = if (declarations.isEmpty()) null else listOf(Tool.functionDeclarations(declarations)),
                systemInstruction = content { text(config.systemInstruction) },
            )
        // An empty config enables resumption handles, used to survive server GoAway mid-conversation.
        return model.connect(SessionResumptionConfig())
    }

    private suspend fun receiveLoop(live: LiveSession, io: AudioIO) {
        try {
            live.receive().collect { msg ->
                when (msg) {
                    is LiveServerContent -> onContent(msg, io)
                    is LiveServerToolCall -> onToolCalls(live, msg.functionCalls)
                    is LiveServerToolCallCancellation -> msg.functionIds.forEach { toolJobs.remove(it)?.cancel() }
                    is LiveSessionResumptionUpdate -> if (msg.resumable == true) msg.newHandle?.let { resumeHandle = it }
                    is LiveServerGoAway -> scope.launch { resume(live, msg) }
                    else -> Unit
                }
            }
            if (!stopping) listener.onEnded(null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Live session failed", e)
            if (!stopping) listener.onEnded(e)
        }
    }

    private fun onContent(msg: LiveServerContent, io: AudioIO) {
        msg.inputTranscription?.text?.takeIf { it.isNotBlank() }?.let {
            listener.onUserTranscript(it)
            // No explicit end-of-user-speech event: treat a short transcript lull as "thinking".
            thinkingDebounce?.cancel()
            thinkingDebounce = scope.launch {
                delay(THINKING_AFTER_MS)
                if (state == BrainState.LISTENING) setState(BrainState.THINKING)
            }
        }
        msg.outputTranscription?.text?.takeIf { it.isNotBlank() }?.let { listener.onAssistantTranscript(it) }

        if (msg.interrupted) {
            io.flushPlayback()
            turnDone = true
            setState(BrainState.LISTENING)
            return
        }
        msg.content?.parts?.filterIsInstance<InlineDataPart>()?.forEach {
            turnDone = false
            thinkingDebounce?.cancel()
            io.enqueuePlayback(it.inlineData)
        }
        if (msg.turnComplete) {
            turnDone = true
            if (!io.isPlaying) setState(BrainState.LISTENING)
        }
    }

    private fun onToolCalls(live: LiveSession, calls: List<FunctionCallPart>) {
        setState(BrainState.THINKING)
        for (call in calls) {
            val job = scope.launch {
                val result = try {
                    listener.onToolCall(call.name, JsonObject(call.args))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    "Error: ${e.message ?: e.javaClass.simpleName}"
                }
                runCatching {
                    live.sendFunctionResponse(
                        listOf(FunctionResponsePart(call.name, buildJsonObject { put("result", result) }, call.id))
                    )
                }.onFailure { Log.w(TAG, "Could not send tool result for ${call.name}", it) }
            }
            call.id?.let { id ->
                toolJobs[id] = job
                job.invokeOnCompletion { toolJobs.remove(id) }
            }
        }
    }

    /** Server will drop the connection soon: reconnect transparently if we have a handle, else let it end. */
    private suspend fun resume(live: LiveSession, goAway: LiveServerGoAway) {
        val handle = resumeHandle
        Log.i(TAG, "GoAway (timeLeft=${goAway.timeLeft}); resumable=${handle != null}")
        if (handle == null || stopping) return
        runCatching { live.resumeSession(SessionResumptionConfig(handle)) }
            .onFailure { Log.w(TAG, "Session resumption failed; session will end", it) }
    }

    override suspend fun awaitTurnFinished(timeoutMs: Long) {
        withTimeoutOrNull(timeoutMs) {
            while (!turnDone || audio?.isPlaying == true) delay(100)
        }
    }

    override suspend fun stop() {
        stopping = true
        // Close the socket first: it is what terminates the SDK's receive loop.
        runCatching { session?.close() }
        session = null
        scope.coroutineContext.cancelChildren()
        toolJobs.clear()
        audio?.stop()
        audio = null
        state = null
    }

    private fun setState(s: BrainState) {
        if (state == s) return
        state = s
        listener.onState(s)
    }

    companion object {
        private const val TAG = "GeminiLiveBrain"
        private const val MIC_MIME = "audio/pcm;rate=16000"
        private const val THINKING_AFTER_MS = 700L
    }
}
