# CheapWhisper

CheapWhisper is an Android voice keyboard (IME). Hold a button, speak, release: the audio is
transcribed by a cheap cloud speech-to-text provider or by whisper.cpp on-device, cleaned up by
an LLM (punctuation, grammar, filler words), and committed into whatever text field is focused.
Opening the app shows the prerecorded file transcription workspace. The existing keyboard and independent Settings screen remain available.

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
- **Cancel**: a red X (or tapping the status line) aborts a recording or an in-flight request.
- **Retry / offline recovery**: accepted recordings are saved privately before processing. Network/stream failures retain the audio and any completed transcript across service restarts. Streaming timeouts do not count as successful silence; the full WAV is used as fallback. When connectivity returns, pending offline dictation resumes only in its original live, unchanged editor. In a later editor session, tap Retry twice to confirm applying the recording to the matching field. Changing fields cancels processing and keeps accepted audio for recovery, including when focus is lost while the button is still held.
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

## File transcription with OpenRouter

Share one voice note to **CheapWhisper** to import and automatically transcribe it with the saved model and language. **Import audio** opens the document picker and waits for **Transcribe**. Both paths support Spanish, English, Catalan, Italian, auto detection and the existing language choices, subject to model support.

The launcher uses Compose Material 3, a single row for model/language selection, continuous transcript text and a fixed play/pause/seek bar. Timed words highlight without changing font weight; scrolling follows playback automatically. Tap a word to seek. Export full text as TXT or available timing as SRT. The original model/language label appears only when it differs from current selections. Progress shows the fraction of audio processed.

The model dropdown keeps one representative per family and shows known Artificial Analysis WER and catalog pricing. Refresh updates availability/rates. The dated benchmark mappings and provider/version qualifications are in [model research](docs/openrouter-models.md). Token-priced hourly estimates assume 90,000 input and 12,000 output tokens per hour; actual costs come from API responses.

In **Settings**, the OpenRouter API Key field sits above OpenAI and uses the same password-field styling and inline validation. Changed keys are verified before saving on Back; invalid replacements preserve the previous key. Existing Android Keystore-backed encrypted preferences store the key. To use OpenRouter for dictation, choose it under **IME Transcriber**; it shares the file screen's model and returns text on release. The separately selected cleanup provider remains in effect.

**Settings → Usage** displays reported costs. **OpenRouter transcription → Show by model** expands transcribed time and cost per model, omitting unused models. Decimal costs are summed before display rounding; positive sub-cent totals show `< $0.01`. The journal is shared with the keyboard spending bucket and covers this installation, across key changes. Missing cost or interrupted requests remain explicitly unconfirmed; compare those with OpenRouter's dashboard.

### Audio, limits and lifecycle

- Incoming shares accept one content-URI audio attachment, OGG/Opus MIME aliases and generic binary attachments. The source app must grant read access. Links/private file paths are rejected.
- Opus is detected from Ogg headers or codec metadata and normalized to 16 kHz mono WAV during import for reliable playback and duration. Display names are preserved.
- Imports are capped at 100 MiB/two hours. Audio is decoded locally and sent as bounded WAV chunks, normally 60 seconds (30 for Qwen). Fixed boundaries can reduce recognition context.
- Every request starts by asking for word/segment timestamps. Only an explicit unsupported-timestamp HTTP 400 triggers one text-only attempt. Other errors are not retried automatically. If any spoken chunk lacks timing, the entire text remains visible without partial timing navigation.
- Completed parts are billed/tracked even if a later part fails or is canceled. No actual provider key or audio is bundled in the app.
- Audio/transcripts survive rotation but not process death; export before closing. Playback pauses in the background. Keep the app open for long jobs; no foreground processing service is provided.
- Temporary processing files are cleaned after completion/cancellation; Android may retain cache after process termination. Backups are disabled. API keys and transcripts are never written to usage records.

### Verification

Always select offline checks when credentials are available:

```sh
ANDROID_HOME=/opt/android-sdk ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug -PofflineTests=true -Pkotlin.compiler.execution.strategy=in-process
```

The suite covers provider regressions, shared-audio lifecycle, cancellation, model selection, key metadata validation, exact usage arithmetic, timing fallback, continuous-text alignment and result provenance. Synthetic Ogg/Opus and stereo WebM/Opus fixtures are tested locally on a device without provider calls. See [verification details](docs/unification-verification.md).

## Recovery and review fixes (0.3.10)

- Accepted keyboard audio is copied to a credential-protected, no-backup retry journal before any network/cleanup work. No API keys are stored in the journal. It retains the original model/provider, language, cleanup choice/prompt, source editor/text and completed raw transcript. Multiple pending recordings are retained; recovery offers only a recording saved from the current field (for Modify, with its text unchanged), and recordings expire after 24 hours. A transcript that comes back empty, even after a second one-shot check, counts as silence: nothing is inserted and the clip is discarded.
- Automatic network recovery is attempted at most once per saved item in the original live input connection while the keyboard is visible. Any later user edit/selection change or new recording disarms it. Editor IDs alone are not trusted across chats. Restored/new sessions require explicit confirmation via a second Retry tap. Changed Modify content must be restored before retry; text is rechecked again before applying the result. Hold Retry to discard a saved recording that is no longer wanted.
- Failed/canceled retries discard composing text through its original input connection. Old streaming callbacks cannot preview into a new editor. New short taps do not delete pending audio.
- Usage reset preserves genuinely active request records while clearing old interrupted history. Completed history compacts to exact model/cost aggregates plus recent requests; keyboard cost reads run off the UI thread.
- OpenRouter keyboard requests use the same longer, redirect-disabled timeout policy as file uploads. Refreshed model metadata persists for subsequent app sessions and the keyboard.
- Android network validation is only a hint for automatic recovery, never a hard gate on manual processing. A storage failure reports that retry is unavailable but still permits processing from the current recording. Malformed retry rows are skipped; unreadable JSON is preserved separately for recovery.
- Non-Opus metadata extraction uses `release()` for Android 8/9 compatibility. Recents replay cannot automatically resubmit an old share. Canceling during import cleans unadopted audio. Tiny final chunks are redistributed so remaining audio is not submitted as a sub-second tail.
- Leaving Settings with an invalid/unreachable replacement OpenRouter key preserves the old key, saves other edits, shows a short message and exits. Temporary 429/5xx errors are labeled unreachable, not invalid.
