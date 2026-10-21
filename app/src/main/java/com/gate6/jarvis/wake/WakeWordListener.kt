package com.gate6.jarvis.wake

import android.Manifest
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import androidx.annotation.RequiresPermission

/**
 * Keeps the microphone open and runs [WakeWordDetector] on a background thread. On a detection it
 * releases the microphone first and then calls [onWake] on the main thread, so the voice session
 * can open its own recorder.
 */
class WakeWordListener(private val context: Context, val cutoff: Float) {
    @Volatile private var running = false
    private var thread: Thread? = null
    private val main = Handler(Looper.getMainLooper())

    val isRunning: Boolean get() = running

    /**
     * Loads the model and starts listening. Returns false if the model could not be loaded.
     * [onWake] runs on the main thread after a detection; [onStopped] if listening ends on its own
     * for any other reason.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(onWake: () -> Unit, onStopped: () -> Unit = {}): Boolean {
        if (running) return true
        val detector = try {
            WakeWordDetector(context, cutoff)
        } catch (e: Exception) {
            Log.e(TAG, "Wake word model failed to load", e)
            return false
        }
        running = true
        thread = Thread({ loop(detector, onWake, onStopped) }, "jarvis-wake").apply { start() }
        return true
    }

    /** Stops listening and waits until the microphone is released. */
    fun stop() {
        running = false
        val t = thread ?: return
        thread = null
        if (t !== Thread.currentThread()) t.join(1500)
    }

    @Suppress("MissingPermission") // checked by the caller of start()
    private fun loop(detector: WakeWordDetector, onWake: () -> Unit, onStopped: () -> Unit) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buf = ShortArray(CHUNK)
        var woke = false
        try {
            while (running) {
                val rec = openRecorder()
                if (rec == null) {
                    // Mic busy or not ready (e.g. a call). Try again shortly.
                    sleepQuietly(RETRY_MS)
                    continue
                }
                var detected = false
                try {
                    rec.startRecording()
                    Log.i(TAG, "Listening for the wake word")
                    while (running) {
                        val n = rec.read(buf, 0, buf.size)
                        if (n < 0) {
                            Log.w(TAG, "AudioRecord read error $n; reopening")
                            break
                        }
                        if (n > 0 && detector.process(buf, n)) {
                            detected = true
                            break
                        }
                    }
                } finally {
                    runCatching { rec.stop() }
                    rec.release()
                }
                if (detected && running) {
                    Log.i(TAG, "Wake word detected")
                    running = false
                    woke = true
                    main.post(onWake)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Wake word listener crashed", e)
        } finally {
            detector.close()
            val wasRunning = running
            running = false
            if (!woke && wasRunning) main.post(onStopped)
        }
    }

    @Suppress("MissingPermission")
    private fun openRecorder(): AudioRecord? {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return null
        return try {
            // VOICE_RECOGNITION: no AGC or heavy noise suppression, closest to what the model was trained on.
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(min, CHUNK * 2 * 4),
            ).takeIf { it.state == AudioRecord.STATE_INITIALIZED } ?: run {
                Log.w(TAG, "AudioRecord not initialized")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not open microphone", e)
            null
        }
    }

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }

    companion object {
        private const val TAG = "WakeWord"
        private const val RATE = MicroFrontend.SAMPLE_RATE
        private const val CHUNK = 480 // 30 ms
        private const val RETRY_MS = 2000L
    }
}
