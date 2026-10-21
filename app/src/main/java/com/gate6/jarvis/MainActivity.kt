package com.gate6.jarvis

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.gate6.jarvis.voice.SessionState
import com.gate6.jarvis.voice.VoiceSessionManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Test screen: start/stop a live voice session by hand and open MCP server settings. */
class MainActivity : Activity() {
    private val scope = MainScope()
    private val observers = mutableListOf<Job>()
    private lateinit var config: JarvisConfig
    private lateinit var model: EditText
    private lateinit var wake: Switch
    private lateinit var voice: Spinner
    private lateinit var idle: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        config = JarvisConfig(this)
        model = findViewById(R.id.model)
        model.setText(config.model)

        voice = findViewById(R.id.voice)
        voice.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, JarvisConfig.VOICES)
        voice.setSelection(JarvisConfig.VOICES.indexOf(config.voice).coerceAtLeast(0))
        idle = findViewById(R.id.idle)
        idle.setText(config.idleTimeoutSec.toString())

        findViewById<Button>(R.id.talk).setOnClickListener {
            saveSettings()
            if (hasMic()) JarvisService.startSession(this) else requestPermissions()
        }
        findViewById<Button>(R.id.stop).setOnClickListener {
            val inConversation = VoiceSessionManager.status.value.state !in setOf(SessionState.IDLE, SessionState.ERROR)
            // Stop ends a conversation; with none running it turns the wake word off (via the switch, so it stays in sync).
            if (!inConversation && wake.isChecked) wake.isChecked = false else JarvisService.stop(this)
        }
        wake = findViewById(R.id.wake)
        wake.isChecked = config.wakeWordEnabled
        wake.setOnCheckedChangeListener { _, on ->
            config.wakeWordEnabled = on
            when {
                on && hasMic() -> JarvisService.startListening(this)
                on -> requestPermissions()
                else -> JarvisService.stopListening(this)
            }
        }
        val sensitivity = findViewById<RadioGroup>(R.id.sensitivity)
        sensitivity.check(SENSITIVITY_IDS[config.wakeSensitivity])
        sensitivity.setOnCheckedChangeListener { _, id ->
            config.wakeSensitivity = SENSITIVITY_IDS.indexOf(id).coerceAtLeast(0)
            // The service restarts the detector when the threshold changed.
            if (config.wakeWordEnabled && hasMic()) JarvisService.startListening(this)
        }

        findViewById<Button>(R.id.servers).setOnClickListener {
            startActivity(android.content.Intent(this, ServersActivity::class.java))
        }

        if (!hasMic()) requestPermissions()
    }

    override fun onStart() {
        super.onStart()
        val status = findViewById<TextView>(R.id.status)
        val dot = findViewById<View>(R.id.statusDot)
        val transcript = findViewById<TextView>(R.id.transcript)
        observers += scope.launch {
            combine(VoiceSessionManager.status, JarvisService.wakeListening, VoiceSessionManager.transcript) { s, w, t ->
                Triple(s, w, t)
            }.collect { (s, waiting, said) ->
                val idle = s.state == SessionState.IDLE || s.state == SessionState.ERROR
                val (label, color) = when {
                    // Between conversations the wake word state matters more than the last error.
                    idle && waiting -> "Say \"Hey Jarvis\"" to R.color.state_listening
                    s.state == SessionState.ERROR -> "Something went wrong" to R.color.state_error
                    s.state == SessionState.IDLE -> "Ready" to R.color.state_idle
                    s.state == SessionState.CONNECTING -> "Connecting…" to R.color.state_busy
                    s.state == SessionState.LISTENING -> "Listening" to R.color.state_listening
                    s.state == SessionState.THINKING -> "Thinking…" to R.color.state_busy
                    else -> "Speaking" to R.color.state_speaking
                }
                status.text = label
                dot.background.mutate().setTint(getColor(color))
                transcript.text = if (s.state == SessionState.ERROR) s.detail else said
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The service may have turned the wake word off (notification "Turn off"); keep the switch honest.
        if (wake.isChecked != config.wakeWordEnabled) wake.isChecked = config.wakeWordEnabled
        // A microphone service may only start while the app is visible: (re)start wake listening here,
        // e.g. after a reboot or after the system stopped the service.
        if (config.wakeWordEnabled && hasMic()) JarvisService.startListening(this)
    }

    override fun onPause() {
        saveSettings() // applies to the next conversation, however it is started
        super.onPause()
    }

    private fun saveSettings() {
        config.model = model.text.toString()
        config.voice = voice.selectedItem as String
        idle.text.toString().toIntOrNull()?.let { config.idleTimeoutSec = it } // clamped to 5..120
        idle.setText(config.idleTimeoutSec.toString())
    }

    override fun onStop() {
        observers.forEach { it.cancel() }
        observers.clear()
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (!hasMic()) Toast.makeText(this, "Jarvis needs the microphone", Toast.LENGTH_LONG).show()
        else if (config.wakeWordEnabled) JarvisService.startListening(this)
    }

    private companion object {
        val SENSITIVITY_IDS = listOf(R.id.sens_normal, R.id.sens_high, R.id.sens_max)
    }

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        val perms = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        requestPermissions(perms.toTypedArray(), 1)
    }
}
