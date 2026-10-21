package com.gate6.jarvis

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        config = JarvisConfig(this)
        model = findViewById(R.id.model)
        model.setText(config.model)

        findViewById<Button>(R.id.talk).setOnClickListener {
            config.model = model.text.toString()
            if (hasMic()) JarvisService.startSession(this) else requestPermissions()
        }
        findViewById<Button>(R.id.stop).setOnClickListener { JarvisService.stop(this) }
        val wake = findViewById<Switch>(R.id.wake)
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
        val transcript = findViewById<TextView>(R.id.transcript)
        observers += scope.launch {
            VoiceSessionManager.status.combine(JarvisService.wakeListening) { s, w -> s to w }.collect { (s, waiting) ->
                val label = s.state.name.lowercase().replaceFirstChar { it.uppercase() }
                status.text = when {
                    s.state == SessionState.ERROR -> "Error: ${s.detail}"
                    s.state == SessionState.IDLE && waiting -> "Waiting for \"Hey Jarvis\""
                    else -> label
                }
            }
        }
        observers += scope.launch { VoiceSessionManager.transcript.collect { transcript.text = it } }
    }

    override fun onResume() {
        super.onResume()
        // A microphone service may only start while the app is visible: (re)start wake listening here,
        // e.g. after a reboot or after the system stopped the service.
        if (config.wakeWordEnabled && hasMic()) JarvisService.startListening(this)
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
