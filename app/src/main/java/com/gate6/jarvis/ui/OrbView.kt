package com.gate6.jarvis.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import com.gate6.jarvis.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/**
 * Jarvis's animated orb: breathes and grows with the user's voice while listening, pulses with
 * Jarvis's voice while speaking, and shows a spinning arc while thinking or connecting.
 */
class OrbView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    enum class Mode { CONNECTING, LISTENING, THINKING, SPEAKING }

    var mode = Mode.CONNECTING
    /** Current loudness 0..1 (user's voice when listening, Jarvis's when speaking). */
    var level = 0f

    private var shown = 0f
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val core = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val arcRect = RectF()

    override fun onDraw(canvas: Canvas) {
        val t = SystemClock.uptimeMillis() / 1000f
        shown += (level - shown) * 0.3f // smooth out the 40-50 ms level steps
        val cx = width / 2f
        val cy = height / 2f
        val base = min(width, height) / 2f * 0.62f
        val color = context.getColor(
            when (mode) {
                Mode.CONNECTING -> R.color.state_idle
                Mode.LISTENING -> R.color.state_listening
                Mode.THINKING -> R.color.state_busy
                Mode.SPEAKING -> R.color.primary
            }
        )
        val r = base * when (mode) {
            Mode.LISTENING -> 1f + 0.05f * wave(t, 0.5f) + 0.32f * shown
            Mode.SPEAKING -> 1f + 0.06f * abs(wave(t, 1.2f)) + 0.32f * shown
            Mode.THINKING, Mode.CONNECTING -> 0.9f + 0.04f * wave(t, 0.8f)
        }

        glow.color = color
        glow.alpha = 50
        canvas.drawCircle(cx, cy, min(r * 1.32f, min(width, height) / 2f), glow)

        core.shader = RadialGradient(cx - r * 0.3f, cy - r * 0.35f, r * 1.4f, lighten(color), color, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r, core)

        if (mode == Mode.THINKING || mode == Mode.CONNECTING) {
            arc.color = color
            arc.strokeWidth = base * 0.14f
            val ar = base * 1.22f
            arcRect.set(cx - ar, cy - ar, cx + ar, cy + ar)
            canvas.drawArc(arcRect, (t * 300f) % 360f, 100f, false, arc)
        }
        if (isAttachedToWindow && visibility == VISIBLE) postInvalidateOnAnimation()
    }

    private fun wave(t: Float, hz: Float) = sin(2 * PI * hz * t).toFloat()

    private fun lighten(c: Int): Int {
        fun mix(v: Int) = v + ((255 - v) * 0.45f).toInt()
        return Color.rgb(mix(Color.red(c)), mix(Color.green(c)), mix(Color.blue(c)))
    }
}
