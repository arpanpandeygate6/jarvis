package com.gate6.jarvis

import com.google.firebase.appcheck.AppCheckProviderFactory
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

/**
 * Debug builds: App Check debug provider. Each install logs a token (logcat tag
 * "DebugAppCheckProvider") that must be allow-listed in the Firebase console.
 */
internal fun appCheckProviderFactory(): AppCheckProviderFactory = DebugAppCheckProviderFactory.getInstance()
