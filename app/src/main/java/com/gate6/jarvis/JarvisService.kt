package com.gate6.jarvis

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.gate6.jarvis.mcp.McpRegistry
import com.gate6.jarvis.ui.AssistantOverlay
import com.gate6.jarvis.voice.Latency
import com.gate6.jarvis.voice.SessionState
import com.gate6.jarvis.voice.VoiceSessionManager
import com.gate6.jarvis.wake.WakeWordListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The app's only microphone owner, as a microphone-type foreground service. It alternates between
 * listening for "Hey Jarvis" on-device ([WakeWordListener]) and one live conversation
 * ([VoiceSessionManager]), handing the microphone from one to the other.
 *
 * Android 14+ only lets a microphone foreground service start while the app is visible, so the
 * service is started from the activity and then stays running; later conversations reuse it.
 */
class JarvisService : Service() {
    private lateinit var config: JarvisConfig
    private lateinit var sessions: VoiceSessionManager
    private lateinit var overlay: AssistantOverlay
    private var wake: WakeWordListener? = null
    private var foreground = false
    private val main = Handler(Looper.getMainLooper())
    private val resumeWake = Runnable { if (foreground && !sessions.isActive) startWake() }

    override fun onCreate() {
        super.onCreate()
        config = JarvisConfig(this)
        sessions = VoiceSessionManager(this, onSessionClosed = ::onSessionClosed)
        overlay = AssistantOverlay(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SESSION -> startSession()
            ACTION_START_LISTENING -> if (ensureForeground() && !sessions.isActive) startWake()
            ACTION_STOP -> if (sessions.isActive) sessions.stop("user stop") else turnOffWake()
            ACTION_STOP_LISTENING -> turnOffWake()
            else -> if (!foreground) stopSelf() // e.g. system restart without an intent
        }
        return START_NOT_STICKY
    }

    @Suppress("MissingPermission") // hasMic() is checked first
    private fun startSession() {
        if (!hasMic()) {
            Log.e(TAG, "RECORD_AUDIO not granted")
            if (!foreground) stopSelf()
            return
        }
        if (!ensureForeground()) return
        Latency.start("talk requested")
        stopWake()
        sessions.start()
        overlay.show()
        updateNotification()
    }

    /** Wake word off: stop listening, remember the choice, and stop the service unless in a conversation. */
    private fun turnOffWake() {
        config.wakeWordEnabled = false
        stopWake()
        if (!sessions.isActive) shutDown()
    }

    private fun startWake() {
        if (!config.wakeWordEnabled || !hasMic() || !foreground) return
        val current = wake
        if (current?.isRunning == true && current.cutoff == config.wakeCutoff) return
        stopWake() // not running, or the sensitivity changed
        val listener = WakeWordListener(this, config.wakeCutoff)
        @Suppress("MissingPermission") // hasMic() checked above
        val started = listener.start(
            onWake = { onWake(listener) },
            onStopped = { if (wake === listener) stopWake() },
        )
        if (!started) {
            Log.e(TAG, "Wake word unavailable on this device")
            return
        }
        wake = listener
        _wakeListening.value = true
        updateNotification()
        // Have tool lists and an App Check token ready before "Hey Jarvis", not after.
        McpRegistry.prewarm(this)
        JarvisApp.prewarmAppCheck()
    }

    private fun stopWake() {
        wake?.stop()
        wake = null
        _wakeListening.value = false
    }

    private fun onWake(listener: WakeWordListener) {
        Latency.start("wake word detected")
        // Ignore a detection that raced with "Turn off", a shutdown or a restart of the listener.
        if (wake !== listener || !foreground || !config.wakeWordEnabled || sessions.isActive) return
        _wakeListening.value = false
        wake = null
        if (!hasMic()) return
        @Suppress("MissingPermission")
        sessions.start()
        overlay.show()
        updateNotification()
    }

    private fun onSessionClosed() {
        // Leave an error on screen long enough to read.
        overlay.hide(if (VoiceSessionManager.status.value.state == SessionState.ERROR) ERROR_LINGER_MS else 0)
        if (config.wakeWordEnabled && foreground) {
            // Let the session's recorder fully release before reopening the mic.
            main.removeCallbacks(resumeWake)
            main.postDelayed(resumeWake, MIC_HANDOVER_MS)
            updateNotification()
        } else {
            shutDown()
        }
    }

    private fun ensureForeground(): Boolean {
        if (foreground) return true
        if (!hasMic()) {
            stopSelf()
            return false
        }
        return try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
            foreground = true
            true
        } catch (e: Exception) {
            // Android 14+: a mic FGS may only start while the app is visible.
            Log.e(TAG, "Not allowed to start microphone foreground service now", e)
            stopSelf()
            false
        }
    }

    private fun shutDown() {
        main.removeCallbacks(resumeWake)
        stopWake()
        foreground = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun hasMic() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun updateNotification() {
        if (!foreground) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Jarvis voice", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        fun action(title: String, action: String, code: Int) = Notification.Action.Builder(
            null, title,
            PendingIntent.getService(this, code, Intent(this, JarvisService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE),
        ).build()

        val inSession = sessions.isActive
        val b = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_jarvis)
            .setContentIntent(open)
            .setOngoing(true)
        if (inSession) {
            b.setContentTitle("Jarvis is listening")
                .addAction(action("End", ACTION_STOP, 1))
        } else {
            b.setContentTitle("Say \"Hey Jarvis\"")
                .setContentText("Wake word runs on this phone; nothing is sent until you say it.")
                .addAction(action("Talk now", ACTION_START_SESSION, 2))
                .addAction(action("Turn off", ACTION_STOP_LISTENING, 3))
        }
        return b.build()
    }

    override fun onDestroy() {
        overlay.dismiss()
        main.removeCallbacks(resumeWake)
        stopWake()
        sessions.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "JarvisService"
        private const val CHANNEL_ID = "jarvis"
        private const val NOTIFICATION_ID = 1
        private const val MIC_HANDOVER_MS = 300L
        private const val ERROR_LINGER_MS = 3500L
        const val ACTION_START_SESSION = "com.gate6.jarvis.START_SESSION"
        const val ACTION_START_LISTENING = "com.gate6.jarvis.START_LISTENING"
        const val ACTION_STOP_LISTENING = "com.gate6.jarvis.STOP_LISTENING"
        const val ACTION_STOP = "com.gate6.jarvis.STOP"

        private val _wakeListening = MutableStateFlow(false)
        /** True while the wake word detector has the microphone. */
        val wakeListening: StateFlow<Boolean> = _wakeListening.asStateFlow()

        fun startSession(context: Context) = start(context, ACTION_START_SESSION)

        /** Starts (or keeps) wake word listening. Call while an activity is visible. */
        fun startListening(context: Context) = start(context, ACTION_START_LISTENING)

        fun stopListening(context: Context) {
            context.startService(Intent(context, JarvisService::class.java).setAction(ACTION_STOP_LISTENING))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, JarvisService::class.java).setAction(ACTION_STOP))
        }

        private fun start(context: Context, action: String) {
            ContextCompat.startForegroundService(context, Intent(context, JarvisService::class.java).setAction(action))
        }
    }
}
