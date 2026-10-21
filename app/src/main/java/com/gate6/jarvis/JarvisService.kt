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
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.gate6.jarvis.voice.VoiceSessionManager

/**
 * The app's only microphone owner, as a microphone-type foreground service.
 * Milestone 1: hosts a single voice session and stops itself when the session ends.
 */
class JarvisService : Service() {
    private lateinit var sessions: VoiceSessionManager

    override fun onCreate() {
        super.onCreate()
        sessions = VoiceSessionManager(this, onSessionClosed = { stopSelf() })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> if (sessions.isActive) sessions.stop("user stop") else stopSelf()
            ACTION_START_SESSION -> startSession()
        }
        return START_NOT_STICKY
    }

    private fun startSession() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO not granted")
            stopSelf()
            return
        }
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } catch (e: Exception) {
            // Android 14+: a mic FGS may only start while the app is visible (activity or overlay window).
            Log.e(TAG, "Not allowed to start microphone foreground service now", e)
            stopSelf()
            return
        }
        sessions.start()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Jarvis voice", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, JarvisService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_jarvis)
            .setContentTitle("Jarvis is listening")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        sessions.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "JarvisService"
        private const val CHANNEL_ID = "jarvis"
        private const val NOTIFICATION_ID = 1
        const val ACTION_START_SESSION = "com.gate6.jarvis.START_SESSION"
        const val ACTION_STOP = "com.gate6.jarvis.STOP"

        fun startSession(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, JarvisService::class.java).setAction(ACTION_START_SESSION)
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, JarvisService::class.java).setAction(ACTION_STOP))
        }
    }
}
