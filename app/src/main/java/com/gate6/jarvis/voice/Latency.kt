package com.gate6.jarvis.voice

import android.os.SystemClock
import android.util.Log

/**
 * Timing log for the path from the wake word (or a button) to Jarvis listening and replying.
 * Read it with `adb logcat -s JarvisLatency`. Main thread and binder callbacks only; cheap.
 */
object Latency {
    private const val TAG = "JarvisLatency"
    @Volatile private var t0 = 0L
    private val once = mutableSetOf<String>()

    /** Starts a new measurement, e.g. at the wake word. */
    fun start(trigger: String) {
        t0 = SystemClock.elapsedRealtime()
        synchronized(once) { once.clear() }
        Log.i(TAG, "0 ms: $trigger")
    }

    fun mark(event: String) {
        if (t0 == 0L) return
        Log.i(TAG, "+${SystemClock.elapsedRealtime() - t0} ms: $event")
    }

    /** Like [mark], but only the first time per measurement (e.g. first reply audio). */
    fun markOnce(event: String) {
        if (synchronized(once) { once.add(event) }) mark(event)
    }
}
