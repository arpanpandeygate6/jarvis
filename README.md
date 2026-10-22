# Jarvis Voice (Android)

Hands-free voice assistant: on-device "Hey Jarvis" wake word → Gemini Live voice session
(Firebase AI Logic, free tier) → MCP tools → back to wake-word listening.

Status: **Milestone 3**: say "Hey Jarvis" (or tap a button) → live, interruptible voice conversation
that can use tools from your MCP servers → back to listening for the wake word.

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

Needs JDK 17+ (Android Studio's bundled one, or `brew install openjdk@21` and
`export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`).

```sh
./gradlew installDebug          # phone connected with USB debugging on
./gradlew testDebugUnitTest     # unit tests; add MCP_LIVE_TEST=1 to also hit a real public MCP server
```

(or open the folder in Android Studio and press Run). `adb` lives in
`~/Library/Android/sdk/platform-tools`.

6. First launch: if Jarvis says "This phone is not registered with Firebase App Check yet", run
   `adb logcat -s DebugAppCheckProvider`, which prints
   `Enter this debug secret into the allow list ...: <token>`. Add it in
   **App Check → Apps → ⋮ → Manage debug tokens**. One token per device/install.

## Wake word (Milestone 3)

Main screen → **Listen for "Hey Jarvis"**. Jarvis then keeps a microphone service running with a
notification ("Talk now" / "Turn off"). After each conversation it goes back to listening.

- Detection runs entirely on the phone: the microWakeWord `hey_jarvis` model (Apache-2.0, by Kevin
  Ahrendt, from `esphome/micro-wake-word-models`) on LiteRT, with a Kotlin port of the TFLite Micro
  audio frontend. No audio leaves the phone until the wake word is heard.
- **Greeting**: Jarvis answers the wake word with "Hi! How can I help?" in its own voice. The first
  time with each voice Gemini says it live and the audio is saved on the phone; after that it plays
  instantly. If you keep talking straight after "Hey Jarvis" it skips the greeting and just listens.
- **Speed**: the mic opens about 0.3 s after the wake word and you can keep talking straight away
  ("Hey Jarvis, what's on my calendar?"). Speech is buffered while Jarvis connects
  (about 1–2 s) and sent as soon as the session is up. Tool lists and the App Check token are fetched
  ahead of time, and a model that just failed is tried last. Timings per conversation:
  `adb logcat -s JarvisLatency`.
- **Floating card**: during a conversation a Jarvis card slides up at the bottom of the screen over
  any app: an orb that grows with your voice (green), pulses with Jarvis's voice (indigo) and spins
  while thinking (amber), the status, the latest line, and ✕ to end. Tap the card to open Jarvis;
  the rest of the screen stays usable. Main screen → **Show Jarvis over other apps** (Android asks for
  "Display over other apps" once). Android doesn't allow it over the lock screen; voice still works there.
- One-tap alternative: long-press the Jarvis icon → **Talk to Jarvis** (drag it to the home screen to
  pin it), or "Talk now" in the notification.
- **Sensitivity**: Normal (model default, 0.97), High (0.90), Very high (0.80). Higher catches more
  accents and quieter speech but wakes by mistake more often.
- Android only allows a microphone service to start while the app is on screen. After a reboot, or if
  the system stops the service, open the app once and listening resumes.
- **Known limits** (from tests with macOS voices; real voices may differ): reliable for US, UK,
  Australian, Scottish and South African voices; weak for the Indian-English voices and for a slow
  "Hey… Jarvis" with a pause; "Hey Travis" can trigger it. If it misses you, raise the sensitivity
  or use "Talk now" in the notification. A model trained on Indian-English speakers would fix this.

## MCP tools (Milestone 2)

Main screen → **MCP servers** → **Add server**: name, `https://` URL of a Streamable HTTP MCP endpoint,
optional access token (sent as `Authorization: Bearer …`). **Save and test** connects and reports the
tool count. Tokens are encrypted with an Android Keystore key and never shown again.

- At the start of each conversation Jarvis loads tools from all enabled servers in parallel (6 s cap,
  tool lists cached for 10 min). A server that is down is skipped and named on screen; the rest still work.
- Tools appear to the model as `<server>__<tool>`; at most 80 tools per conversation.
- **Tools that change data need a spoken yes, enforced in code**, not just by the prompt. A tool needs
  confirmation unless the server marks it `readOnlyHint: true`. The call only runs when the model set
  `user_confirmed`, its previous turn asked ("Should I go ahead?"), and your latest reply is a clear yes
  ("yes", "go ahead", "haan, kar do"…) with no "no / wait / cancel". One yes covers one action.
- Many servers don't annotate their tools at all, so even lookups would ask for a yes. For servers you
  know are read-only, tick **Skip the yes/no for tools this server doesn't mark**. Tools a server
  explicitly marks as changing data still always ask.
- **Sign-in (OAuth)**: if a server answers "401, sign in", Save and test offers **Sign in**. Jarvis
  finds the sign-in server from the MCP server's metadata, registers itself automatically, and opens
  a browser tab (PKCE, `resource` indicator, state and `iss` checks); after you approve you land back
  in the app and it re-tests. Tokens are Keystore-encrypted and refreshed automatically; if refresh
  fails the server shows "Needs sign-in" again. Edit a server → **Sign out** to forget it. Works with
  servers that support dynamic client registration (e.g. `https://mcp.notion.com/mcp`,
  `https://mcp.linear.app/mcp`). Servers that need a pre-registered client ID (e.g. GitHub) still
  need a personal access token in the token field.

### Device test script

1. Add `https://mcp.deepwiki.com/mcp` (no token), tick the skip-yes/no box, Save and test → "Connected: 3 tools".
2. Talk to Jarvis: "What is the OkHttp repo by Square about?" → it uses DeepWiki and answers briefly.
3. Untick the box, start a new conversation, ask again → Jarvis reads the action back and asks first;
   say "no" → nothing runs; ask again and say "yes" → it runs.
4. Sign-in: add `https://mcp.linear.app/mcp` (or Notion), Save and test → Sign in → approve in the
   browser → back in Jarvis, the row shows "Signed in" and "Connected: N tools". Ask Jarvis about
   your Linear issues.
5. Turn on airplane mode, start a conversation → status shows the server as unavailable; chat still works.
6. Milestone 1 checks still pass: barge-in, "bye Jarvis", 20 s silence ends the session.
7. Wake word: turn it on, lock the phone, say "Hey Jarvis" from 1–3 m away → Jarvis says "Hi! How can I help?" → ask something →
   say "bye Jarvis" → notification goes back to "Say Hey Jarvis". Try a few people and accents, and a
   TV or music playing (false wakes), and note which sensitivity works.

`./gradlew connectedDebugAndroidTest` runs the wake word detector on a device or emulator against
recorded clips.

## Latency log

`adb logcat -s JarvisLatency` prints, per conversation, milliseconds from the wake word (or button):
`session start`, `mic open`, `tools ready`, `connected; sending buffered speech`, `first reply audio`.
"mic open" is when you are being heard; "connected" minus "mic open" is network + Gemini setup.

## Release builds

- **App Check**: debug builds use the debug provider (one allow-listed token per install); release
  builds use **Play Integrity**, which only passes for installs from Google Play (internal testing
  track is fine). In Firebase: App Check → Apps → register Play Integrity with the app's SHA-256
  signing certificate. Sideloaded release APKs will be rejected by App Check; share debug builds
  with testers instead.
- **Signing**: create `keystore.properties` in the project root (gitignored) with `storeFile`,
  `storePassword`, `keyAlias`, `keyPassword`, then `./gradlew assembleRelease` (or `bundleRelease`
  for Play). Without it the release APK is unsigned.
- **CI**: `.github/workflows/android.yml` runs unit tests, lint and a debug build on every push and
  uploads the APK and reports. It starts working once the repo is pushed to GitHub.

## Notes

- Main screen settings apply to the next conversation: Gemini voice, and how many seconds of silence
  end a conversation (5–120, default 20).
- Model is editable on the main screen. Default `gemini-3.1-flash-live-preview`. Each model has its own
  free quota, so when one is out of quota (or rejected) Jarvis tries the next:
  `gemini-2.5-flash-native-audio-preview-12-2025`, then `-09-2025`. A model that failed while another
  one worked is tried last for an hour. When all three are out, Jarvis says the free quota is reached.
- Free tier: Google may use prompts/audio to improve products. Don't test with sensitive data.
- No API keys or tokens are compiled into the APK.
