package com.gate6.jarvis.ui

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import com.gate6.jarvis.JarvisConfig
import com.gate6.jarvis.JarvisService
import com.gate6.jarvis.MainActivity
import com.gate6.jarvis.R
import com.gate6.jarvis.voice.SessionState
import com.gate6.jarvis.voice.VoiceLevel
import com.gate6.jarvis.voice.VoiceSessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The floating Jarvis card shown at the bottom of the screen, over any app, during a conversation.
 * Touches outside the card go to the app underneath. Needs "Display over other apps"; without it
 * nothing is shown and the notification remains the only UI.
 */
class AssistantOverlay(private val context: Context) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var root: View? = null
    private var scope: CoroutineScope? = null
    private val hideRunnable = Runnable { animateOut() }

    fun show() {
        main.removeCallbacks(hideRunnable)
        root?.let { return } // already showing (e.g. a pending hide was cancelled)
        if (!isAllowed(context)) return

        val view = LayoutInflater.from(ContextThemeWrapper(context, R.style.Theme_Jarvis))
            .inflate(R.layout.overlay_assistant, null)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // Before Android 11 overlay windows don't avoid the navigation bar by themselves.
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom + dp(48))
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.BOTTOM }
        try {
            wm.addView(view, params)
        } catch (e: Exception) {
            Log.w(TAG, "Could not show overlay", e)
            return
        }
        root = view

        val card = view.findViewById<View>(R.id.card)
        val orb = view.findViewById<OrbView>(R.id.orb)
        val status = view.findViewById<TextView>(R.id.status)
        val transcript = view.findViewById<TextView>(R.id.transcript)
        view.findViewById<View>(R.id.close).setOnClickListener { JarvisService.stop(context) }
        card.setOnClickListener {
            context.startActivity(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }

        // Slide up from below the screen edge.
        card.alpha = 0f
        card.translationY = dp(120).toFloat()
        card.animate().alpha(1f).translationY(0f).setDuration(240).setInterpolator(DecelerateInterpolator()).start()

        scope = MainScope().also { s ->
            s.launch {
                combine(VoiceSessionManager.status, VoiceSessionManager.transcript) { st, t -> st to t }.collect { (st, said) ->
                    val (label, mode) = when (st.state) {
                        SessionState.CONNECTING -> "Connecting…" to OrbView.Mode.CONNECTING
                        SessionState.LISTENING -> "Listening" to OrbView.Mode.LISTENING
                        SessionState.THINKING -> "Thinking…" to OrbView.Mode.THINKING
                        SessionState.SPEAKING -> "Speaking" to OrbView.Mode.SPEAKING
                        SessionState.ERROR -> "Something went wrong" to OrbView.Mode.CONNECTING
                        SessionState.IDLE -> "Jarvis" to OrbView.Mode.CONNECTING
                    }
                    status.text = label
                    orb.mode = mode
                    val text = if (st.state == SessionState.ERROR) st.detail else said
                    transcript.text = if (text.length > MAX_CHARS) "…" + text.takeLast(MAX_CHARS) else text
                    transcript.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
                }
            }
            s.launch {
                combine(VoiceLevel.user, VoiceLevel.jarvis) { u, j -> u to j }.collect { (u, j) ->
                    orb.level = if (orb.mode == OrbView.Mode.SPEAKING) j else u
                }
            }
        }
    }

    /** Slides the card away after [delayMs] (e.g. to let an error be read). */
    fun hide(delayMs: Long = 0) {
        main.removeCallbacks(hideRunnable)
        main.postDelayed(hideRunnable, delayMs)
    }

    /** Removes the card at once, e.g. when the service is destroyed. */
    fun dismiss() {
        main.removeCallbacks(hideRunnable)
        removeNow()
    }

    private fun animateOut() {
        val card = root?.findViewById<View>(R.id.card) ?: return removeNow()
        card.animate().alpha(0f).translationY(card.height.toFloat() + dp(24)).setDuration(200)
            .setInterpolator(AccelerateInterpolator()).withEndAction { removeNow() }.start()
    }

    private fun removeNow() {
        scope?.cancel()
        scope = null
        root?.let { runCatching { wm.removeView(it) } }
        root = null
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "AssistantOverlay"
        private const val MAX_CHARS = 160

        /** The user wants the overlay and Android allows it. */
        fun isAllowed(context: Context) = JarvisConfig(context).overlayEnabled && Settings.canDrawOverlays(context)
    }
}
