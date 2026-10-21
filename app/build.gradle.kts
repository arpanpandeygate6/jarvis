import java.util.Properties

plugins {
    id("com.android.application")
}

// Release signing comes from keystore.properties (gitignored): storeFile, storePassword, keyAlias, keyPassword.
val keystoreProps = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { f ->
    Properties().apply { f.inputStream().use(::load) }
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
        versionCode = 3
        versionName = "0.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystoreProps != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystoreProps != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // The wake word model is memory-mapped from assets, so it must be stored uncompressed.
    androidResources { noCompress += "tflite" }
    // Pure-Kotlin unit tests; android.util.Log calls become no-ops.
    testOptions.unitTests.isReturnDefaultValues = true
}

dependencies {
    implementation(platform("com.google.firebase:firebase-bom:35.0.2"))
    implementation("com.google.firebase:firebase-ai")
    implementation("com.google.firebase:firebase-appcheck")
    debugImplementation("com.google.firebase:firebase-appcheck-debug")
    releaseImplementation("com.google.firebase:firebase-appcheck-playintegrity")
    implementation("androidx.core:core-ktx:1.17.0")
    // Versions match firebase-ai 18.0.0's runtime deps (it doesn't expose them at compile time).
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("androidx.browser:browser:1.8.0") // Custom Tabs for MCP sign-in
    // On-device wake word model runtime (classic Interpreter API).
    implementation("com.google.ai.edge.litert:litert:1.4.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
