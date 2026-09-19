# CheapWhisper

CheapWhisper is an Android voice keyboard (IME). Hold a button, speak, release: the audio is
transcribed by a cheap cloud speech-to-text provider or by whisper.cpp on-device, cleaned up by
an LLM (punctuation, grammar, filler words), and committed into whatever text field is focused.
Opening the app shows its Settings screen; all dictation happens from the keyboard.

Package name: `com.example.smartnotetaker` (the app shows as "CheapWhisper").

## Features

- **Write** (push-to-talk mic button): transcribe, clean up, and insert at the cursor.
- **Modify** by voice: hold the second button, speak an edit instruction ("make it shorter",
  "translate to Spanish"); the LLM rewrites the field's existing text. Enabled only when the
  field has text.
- **Live preview** while you speak (always on):
  - OpenAI, Deepgram, ElevenLabs, AssemblyAI, Soniox: audio streams over a WebSocket and interim text
    shows as underlined composing text (Write) or in a transcript box (Modify).
  - Local Whisper: whisper.cpp transcribes a chunk at each pause (>= 0.6 s of silence) or
    every 6 s, and appends it.
  - If a stream dies mid-dictation the recorded WAV is sent as a one-shot request instead.
- **Undo**: full-field snapshots. A Write pushes two levels (back to the raw transcript, then to
  before the dictation); a Modify pushes one.
- **Cancel**: a red X (or tapping the status line) aborts a recording or an in-flight request.
  Losing focus or hiding the keyboard also cancels whatever is running.
- **Cost tracking**: cumulative spend per provider, shown on the keyboard and in Settings, with
  a per-provider breakdown and a reset button. Transcription is metered on the duration the
  provider reports (falling back to the WAV length); the OpenAI LLM is metered on returned
  token usage. Recordings under 1 s are ignored.
- Hold-to-repeat backspace, language selection (auto-detect or one of 13 fixed languages),
  and a Storage section to delete downloaded models.

## Supported providers

Rates are the estimates hard-coded in `CostEstimator` (`MainActivity.kt`), not live prices.

| Provider | One-shot model | One-shot rate | Live preview (WebSocket) | Streaming rate |
|---|---|---|---|---|
| OpenAI | `whisper-1` | $0.006/min ($0.36/hr) | `gpt-4o-mini-transcribe` via Realtime API | $0.003/min ($0.18/hr) |
| Deepgram | `nova-3` | $0.0077/min ($0.46/hr) | `nova-3` | same as one-shot |
| ElevenLabs | `scribe_v1` | $0.40/hr | `scribe_v2_realtime` | $0.39/hr |
| AssemblyAI | async upload + poll | $0.27/hr | `universal-streaming-english` / `-multilingual` | $0.15/hr |
| Soniox | `stt-async-v5` | $0.10/hr | `stt-rt-v5` | $0.12/hr |
| Local (tiny / base / small) | whisper.cpp, multilingual ggml models | free | pause-chunked local transcription | free |

Local Whisper models are downloaded on first use from `huggingface.co/ggerganov/whisper.cpp`
into the app's private files directory.

## LLM cleanup

Selected in Settings under "LLM Cleanup Model":

- **OpenAI**: `gpt-5.6-luna` via Chat Completions with `reasoning_effort: low`. Estimated at
  roughly $0.04 per hour of speech (500 tokens/min in and out at $0.20 / $1.20 per 1M tokens);
  actual cost is tracked from token usage.
- **Local (Gemma-4 E2B)** or **Local (Gemma-4 E4B)**: `.litertlm` models from
  `litert-community` on Hugging Face, run with LiteRT-LM (GPU backend, CPU fallback). Free,
  but a multi-GB download.

The cleanup system prompt is editable in Settings ("Cleanup prompt", with a reset button). The
default asks the model to fix punctuation, grammar and formatting, drop filler words, and
output only the cleaned text. Modify uses its own fixed "precise text editor" prompt.

## Build

whisper.cpp is a git submodule at `app/src/main/cpp/whisper.cpp`, built by
`app/src/main/cpp/CMakeLists.txt` (CPU backend, `-O3 -flto`, plus an `armv8.2-a+fp16` variant
on arm64).

```sh
git clone --recurse-submodules <repo-url>
# or, in an existing checkout:
git submodule update --init

./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requirements (from `app/build.gradle.kts` and the root build script):

- JDK 17
- Android SDK: compileSdk/targetSdk 34, minSdk 26
- Android NDK `26.1.10909125` and CMake 3.10+ (install via SDK Manager)
- Android Gradle Plugin 8.2.0, Kotlin 1.9.20
- ABIs built: `arm64-v8a` and `x86_64`

The version is set once in `app/build.gradle.kts` (`appVersionName`), and `versionCode` is
derived from it.

## First-run setup

1. Open the CheapWhisper app and grant the microphone permission when prompted (tap Write).
2. Enable the keyboard: Android Settings > System > Languages & input > On-screen keyboard >
   Manage keyboards > turn on "CheapWhisper Voice Input".
3. Open Settings (gear icon in the app or on the keyboard), pick a Transcription Model and an
   LLM Cleanup Model, and enter the API key(s) they need. Only the key fields for the selected
   providers are shown. Local providers need no key.
4. In any text field, switch to CheapWhisper from the keyboard picker and hold the mic button.

## Where keys are stored

API keys and settings are kept in `EncryptedSharedPreferences` (`secret_shared_prefs`, AES-256
GCM values, AES-256 SIV keys, Android Keystore master key). They never leave the device except
in requests to the provider they belong to. Usage totals are in plain `SharedPreferences`.

## Tests

Offline unit tests (no device, no keys, no cost) run on the JVM with Robolectric and OkHttp's
MockWebServer, which plays each provider's WebSocket or REST endpoint:

```sh
./gradlew :app:testDebugUnitTest -Pkotlin.compiler.execution.strategy=in-process
```

They cover the four streaming clients (request shape, audio framing, interim/final joining,
end-of-audio handshake, failure, cancel, timeout), the API key validator, and WAV helpers.

Live tests hit the real provider APIs with your keys. Copy `.env.example` to `.env` and fill
it in (never commit it). Two ways to run them:

- **On this machine (JVM, preferred):** the `LiveProvidersJvmTest` class streams and
  transcribes a sample clip through every provider that has a key and checks the cleanup LLM:

  ```sh
  ./gradlew :app:testDebugUnitTest --tests "*LiveProvidersJvmTest*" -Pkotlin.compiler.execution.strategy=in-process
  ```

- **On a connected device (instrumented):** `scripts/live-tests.sh`. The script keeps the app
  installed after the run; the bare `connectedDebugAndroidTest` task would uninstall it and
  wipe your saved keys, so always use the script.

Live runs spend a small amount of real money. Tests for providers without a key are skipped.
