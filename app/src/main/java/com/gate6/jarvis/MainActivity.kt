package com.gate6.jarvis

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.gate6.jarvis.voice.SessionState
import com.gate6.jarvis.voice.VoiceSessionManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Milestone 1 test screen: start/stop a live voice session by hand. */
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

        if (!hasMic()) requestPermissions()
    }

    override fun onStart() {
        super.onStart()
        val status = findViewById<TextView>(R.id.status)
        val transcript = findViewById<TextView>(R.id.transcript)
        observers += scope.launch {
            VoiceSessionManager.status.collect { s ->
                val label = s.state.name.lowercase().replaceFirstChar { it.uppercase() }
                status.text = if (s.state == SessionState.ERROR) "Error: ${s.detail}" else label
            }
        }
        observers += scope.launch { VoiceSessionManager.transcript.collect { transcript.text = it } }
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
