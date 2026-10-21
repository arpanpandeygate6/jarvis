package com.gate6.jarvis

import android.app.Application
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.appCheck

class JarvisApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // FirebaseApp is auto-initialized from google-services.json; it is absent when the file is missing.
        if (FirebaseApp.getApps(this).isEmpty()) {
            Log.e(TAG, "Firebase not configured: add app/google-services.json (see README)")
            return
        }
        // Debug builds use the debug provider, release builds Play Integrity (see src/debug, src/release).
        // No API key or secret ships in the APK.
        Firebase.appCheck.installAppCheckProviderFactory(appCheckProviderFactory())
    }

    companion object {
        const val TAG = "Jarvis"
    }
}
