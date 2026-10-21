package com.gate6.jarvis

import com.google.firebase.appcheck.AppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

/**
 * Release builds: Play Integrity. Works for installs from Google Play (including internal testing
 * tracks) once the app is linked to the Firebase project; sideloaded release APKs will be rejected.
 */
internal fun appCheckProviderFactory(): AppCheckProviderFactory = PlayIntegrityAppCheckProviderFactory.getInstance()
