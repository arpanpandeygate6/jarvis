package com.gate6.jarvis

import android.content.Context
import com.google.firebase.appcheck.AppCheckProviderFactory
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import java.io.File

/**
 * Debug builds: App Check debug provider. Each install creates a token that must be allow-listed in
 * the Firebase console (App Check → Apps → ⋮ → Manage debug tokens).
 */
internal fun appCheckProviderFactory(): AppCheckProviderFactory = DebugAppCheckProviderFactory.getInstance()

/**
 * This install's debug token, so testers without Android Studio can send it to whoever runs the
 * Firebase project. The SDK has no API for it: read the prefs file it writes
 * ("com.google.firebase.appcheck.debug.store.<app key>", key "...DEBUG_SECRET"). Null until created.
 */
internal fun appCheckDebugToken(context: Context): String? {
    val dir = File(context.applicationInfo.dataDir, "shared_prefs")
    val name = dir.list()
        ?.firstOrNull { it.startsWith("com.google.firebase.appcheck.debug.store.") && it.endsWith(".xml") }
        ?.removeSuffix(".xml") ?: return null
    return context.getSharedPreferences(name, Context.MODE_PRIVATE)
        .getString("com.google.firebase.appcheck.debug.DEBUG_SECRET", null)
}
