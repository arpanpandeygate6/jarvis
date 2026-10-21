plugins {
    id("com.android.application")
}

// google-services.json is developer-supplied (see README); build without it so CI/compile checks work.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
} else {
    logger.warn("app/google-services.json missing: Firebase will not initialize at runtime.")
}

android {
    namespace = "com.gate6.jarvis"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.gate6.jarvis"
        minSdk = 26 // TYPE_APPLICATION_OVERLAY
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(platform("com.google.firebase:firebase-bom:35.0.2"))
    implementation("com.google.firebase:firebase-ai")
    implementation("com.google.firebase:firebase-appcheck-debug")
    implementation("androidx.core:core-ktx:1.17.0")
    // Versions match firebase-ai 18.0.0's runtime deps (it doesn't expose them at compile time).
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
}
