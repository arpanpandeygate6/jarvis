package com.gate6.jarvis

import android.content.Context
import com.google.firebase.appcheck.AppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

/**
 * Release builds: Play Integrity. Works for installs from Google Play (including internal testing
 * tracks) once the app is linked to the Firebase project; sideloaded release APKs will be rejected.
 */
internal fun appCheckProviderFactory(): AppCheckProviderFactory = PlayIntegrityAppCheckProviderFactory.getInstance()

/** Release builds have no debug token; the "Send my device code" button stays hidden. */
@Suppress("UNUSED_PARAMETER")
internal fun appCheckDebugToken(context: Context): String? = null
