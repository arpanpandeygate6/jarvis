package com.gate6.jarvis.voice

import android.content.Context
import java.io.File
import kotlin.math.sqrt

/**
 * Jarvis's spoken reply to the wake word ("Hi! How can I help?") in its own Gemini voice.
 * The first time with a given voice, Gemini says it live and the audio is saved here; after that it
 * plays instantly from the phone, before the connection is even up.
 */
class GreetingCache(context: Context) {
    private val dir = File(context.noBackupFilesDir, "greetings")

    private fun file(voice: String) = File(dir, "${voice.lowercase().replace(Regex("[^a-z0-9]"), "")}-v$VERSION.pcm")

    /** 24 kHz mono PCM16, or null if not recorded yet for this voice. */
    fun get(voice: String): ByteArray? =
        file(voice).takeIf { it.isFile && it.length() in MIN_BYTES..MAX_BYTES }?.readBytes()

    fun put(voice: String, pcm: ByteArray): Boolean {
        if (pcm.size !in MIN_BYTES..MAX_BYTES) return false
        dir.mkdirs()
        val tmp = File(dir, "${file(voice).name}.tmp")
        tmp.writeBytes(pcm)
        return tmp.renameTo(file(voice))
    }

    companion object {
        /** Bump to re-record every voice's greeting, e.g. after changing [PROMPT]. */
        private const val VERSION = 1
        const val TEXT = "Hi! How can I help?"
        const val PROMPT = "The user just said \"Hey Jarvis\". Reply with exactly: $TEXT"
        /** Words the transcript of a recorded greeting must contain to be saved. */
        val EXPECTED = Regex("""\bhelp\b""", RegexOption.IGNORE_CASE)
        private const val BYTES_PER_SEC = 24_000L * 2
        private const val MIN_BYTES = BYTES_PER_SEC * 4 / 10 // 0.4 s
        private const val MAX_BYTES = BYTES_PER_SEC * 6
    }
}

/** Tiny loudness check used to tell whether the user kept talking right after the wake word. */
object Speech {
    /** Above this RMS (16-bit, about -32 dBFS) a 50 ms chunk counts as speech. */
    const val RMS_THRESHOLD = 800.0

    fun rms(pcm16le: ByteArray): Double {
        val n = pcm16le.size / 2
        if (n == 0) return 0.0
        var sum = 0.0
        for (i in 0 until n) {
            val s = (pcm16le[2 * i].toInt() and 0xFF) or (pcm16le[2 * i + 1].toInt() shl 8)
            sum += s.toDouble() * s
        }
        return sqrt(sum / n)
    }

    fun isSpeech(pcm16le: ByteArray) = rms(pcm16le) > RMS_THRESHOLD
}
