package com.gate6.jarvis.voice

import android.Manifest
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import androidx.annotation.RequiresPermission
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Full-duplex PCM audio for a voice session: 16 kHz mic in, 24 kHz speech out.
 *
 * Uses voice-communication mode so the platform echo canceller removes the assistant's own voice
 * from the mic on speakerphone; without it barge-in would trigger on the assistant itself.
 */
class AudioIO(context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val playQueue = LinkedBlockingQueue<ByteArray>()

    private var recorder: AudioRecord? = null
    private var track: AudioTrack? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var micThread: Thread? = null
    private var playerThread: Thread? = null
    private var previousMode = AudioManager.MODE_NORMAL

    @Volatile private var running = false
    @Volatile private var writing = false

    /** Called on the player thread when audio starts / stops coming out of the speaker. */
    var onPlaybackStarted: () -> Unit = {}
    var onPlaybackDrained: () -> Unit = {}

    val isPlaying: Boolean get() = writing || playQueue.isNotEmpty()

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(onMicChunk: (ByteArray) -> Unit) {
        check(!running) { "AudioIO already started" }
        previousMode = audioManager.mode
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        routeOutput()

        val inMin = AudioRecord.getMinBufferSize(IN_RATE, AudioFormat.CHANNEL_IN_MONO, ENCODING)
        val rec = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(IN_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(ENCODING)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(inMin, MIC_CHUNK_BYTES * 4))
            .build()
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "Microphone unavailable (in use by another app?)" }
        recorder = rec
        if (AcousticEchoCanceler.isAvailable()) {
            echoCanceler = AcousticEchoCanceler.create(rec.audioSessionId)?.apply { enabled = true }
        }
        if (NoiseSuppressor.isAvailable()) {
            noiseSuppressor = NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true }
        }

        val outMin = AudioTrack.getMinBufferSize(OUT_RATE, AudioFormat.CHANNEL_OUT_MONO, ENCODING)
        val out = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(OUT_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(ENCODING)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(outMin * 2)
            .build()
        track = out

        running = true
        rec.startRecording()
        out.play()

        micThread = Thread({
            while (running) {
                val buf = ByteArray(MIC_CHUNK_BYTES)
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) onMicChunk(if (n == buf.size) buf else buf.copyOf(n))
                else if (n < 0) break
            }
        }, "jarvis-mic").apply { priority = Thread.MAX_PRIORITY; start() }

        playerThread = Thread({
            while (running) {
                // Blocking poll (no busy-wait) so an idle speaker costs no CPU.
                val chunk = try {
                    playQueue.poll(DRAIN_GAP_MS, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    break
                }
                if (chunk == null) {
                    if (writing) {
                        writing = false
                        onPlaybackDrained()
                    }
                    continue
                }
                if (!writing) {
                    writing = true
                    onPlaybackStarted()
                }
                out.write(chunk, 0, chunk.size)
            }
        }, "jarvis-speaker").apply { priority = Thread.MAX_PRIORITY; start() }
    }

    fun enqueuePlayback(pcm: ByteArray) {
        if (running) playQueue.offer(pcm)
    }

    /** Drops queued and buffered assistant audio immediately (user barged in). */
    fun flushPlayback() {
        playQueue.clear()
        track?.runCatching {
            pause()
            flush()
            play()
        }
    }

    fun stop() {
        if (!running) return
        running = false
        playQueue.clear()
        runCatching { recorder?.stop() }
        runCatching { track?.pause(); track?.flush() }
        playerThread?.interrupt()
        micThread?.join(500)
        playerThread?.join(500)
        echoCanceler?.release()
        noiseSuppressor?.release()
        recorder?.release()
        track?.release()
        recorder = null
        track = null
        echoCanceler = null
        noiseSuppressor = null
        writing = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = false
        }
        audioManager.mode = previousMode
    }

    /** Communication mode defaults to the earpiece; prefer a headset, else the loudspeaker. */
    private fun routeOutput() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = audioManager.availableCommunicationDevices
            val pick = OUTPUT_PREFERENCE.firstNotNullOfOrNull { type -> devices.firstOrNull { it.type == type } }
            pick?.let { audioManager.setCommunicationDevice(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = !audioManager.isWiredHeadsetOn && !audioManager.isBluetoothScoOn
        }
    }

    companion object {
        const val IN_RATE = 16_000
        const val OUT_RATE = 24_000
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val MIC_CHUNK_BYTES = IN_RATE * 2 / 20 // 50 ms
        private const val DRAIN_GAP_MS = 150L
        private val OUTPUT_PREFERENCE = listOf(
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        )
    }
}
