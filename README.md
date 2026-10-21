# Jarvis Voice (Android)

Hands-free voice assistant: on-device "Hey Jarvis" wake word → Gemini Live voice session
(Firebase AI Logic, free tier) → MCP tools → back to wake-word listening.

Status: **Milestone 1** (tap a button → live, interruptible voice conversation).

## One-time Firebase setup (free, no billing account)

1. https://console.firebase.google.com → **Add project** (Analytics optional).
2. **AI Services → AI Logic → Get started** → choose **Gemini Developer API**
   (no-cost Spark plan). This enables the API and keeps the Gemini key server-side.
3. **Project settings → Add app → Android**, package name `com.gate6.jarvis`.
   Download `google-services.json` into `app/` (it is gitignored).
4. **Security → App Check** → register the Android app. Provider for these sideloaded builds is the
   **debug provider** (see step 6). App Check enforcement becomes mandatory for AI Logic on
   **2 Nov 2026**, so do this now.

## Build & install

```sh
./gradlew installDebug          # phone connected with USB debugging on
```

(or open the folder in Android Studio and press Run).

6. First launch: `adb logcat -s DebugAppCheckProvider` prints
   `Enter this debug secret into the allow list ...: <token>`. Add it in
   **App Check → Apps → ⋮ → Manage debug tokens**. One token per device/install.

## Notes

- Model is editable on the main screen. Default `gemini-3.1-flash-live-preview`; if that is rejected
  the app retries once with `gemini-2.5-flash-native-audio-preview-12-2025`.
- Free tier: Google may use prompts/audio to improve products. Don't test with sensitive data.
- No API keys or tokens are compiled into the APK.
