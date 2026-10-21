package com.gate6.jarvis

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Invisible target of the "Talk to Jarvis" launcher shortcut: starts a conversation in one tap.
 * Being an activity matters: Android only lets a microphone service start while the app is visible.
 */
class TalkActivity : Activity() {
    override fun onResume() {
        super.onResume()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            JarvisService.startSession(this)
        } else {
            startActivity(Intent(this, MainActivity::class.java)) // asks for the microphone
        }
        finish()
    }
}
