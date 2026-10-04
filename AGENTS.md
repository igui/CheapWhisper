# AGENTS.md — CheapWhisper (Android)

Guide for AI coding agents and new contributors. Read the code before changing it; this file points at where things live.

## What it is
Android voice-dictation keyboard (IME); the launcher activity is just the Settings screen. Audio is transcribed by a cloud STT provider or on-device whisper.cpp, then cleaned by an LLM (OpenAI or on-device Gemma via LiteRT-LM). Package `com.example.smartnotetaker`, app label "CheapWhisper", single Gradle module `:app`.

## Project layout
- `app/build.gradle.kts` — versionName is the single source of truth (`versionCode` derived), compileSdk 34 / minSdk 26, NDK 26.1, ABIs arm64-v8a + x86_64, Compose BOM 2023.10.01, OkHttp 4.12, security-crypto, LiteRT-LM.
- `app/src/main/cpp/` — `CMakeLists.txt` builds `libwhisper*.so` from `whisper.cpp/` (git submodule, `ggml-org/whisper.cpp`) + `jni.c`; `ai_chat.cpp`, `logging.h`. Kotlin JNI wrapper: `app/src/main/java/com/whispercpp/whisper/LibWhisper.kt` (`WhisperContext`).
- `app/src/main/java/com/example/smartnotetaker/`
  - `MainActivity.kt` — the catch-all file (~1100 lines). Top to bottom: endpoint/model constants and `PROVIDER_*` constants, `TRANSCRIBE_LANGUAGES`, `ApiKeys` (+ `keyFor`), `COST_PROVIDERS`, `CostEstimator` (rates), `UsageTracker` (plain prefs, micro-USD), `MainActivity` (hosts `SettingsScreen`, requests RECORD_AUDIO), `SecureStorage` (EncryptedSharedPreferences getters/setters), `SettingsScreen` (Compose), `AIProcessor` (one-shot transcription per provider, local whisper, cleanup/modify LLM calls).
  - `VoiceKeyboardService.kt` — the IME (`InputMethodService`), classic Views inflated from `res/layout/keyboard_view.xml` (ids: `btn_mic`, `btn_modify`, `btn_delete`, `btn_cancel`, `btn_retry`, `btn_back`, `btn_settings`, `tv_status`, `tv_transcript`, `btn_cost`, `cost_panel`, `tv_cost_breakdown`).
  - `LiveTranscriber.kt` — streaming interface; implemented by `DeepgramStream.kt`, `OpenAiStream.kt`, `ElevenLabsStream.kt`, `AssemblyAiStream.kt`.
  - `WavRecorder.kt` — `AudioRecord` -> WAV in cacheDir; exposes `amplitude`, `pcmBytesWritten`, `lastSpeechByte`, `onPcm` tap, `exportChunk`, `decodeWavToFloatArray`.
  - `LocalModelDownloader.kt` — downloads ggml Whisper and Gemma `.litertlm` models from Hugging Face into filesDir. `FallbackEngine.kt` — LiteRT-LM GPU->CPU fallback. `SettingsActivity.kt` — hosts `SettingsScreen` for the IME's gear button.
- `app/src/main/AndroidManifest.xml` — `MainActivity`, `SettingsActivity`, `VoiceKeyboardService` (`res/xml/method.xml`). Permissions: INTERNET, RECORD_AUDIO.

## Build and run
- First: `git submodule update --init` (the native build fails without `app/src/main/cpp/whisper.cpp`).
- Build: `./gradlew assembleDebug -q --console=plain`.
- If Kotlin reports "Daemon compilation failed: null", add `-Pkotlin.compiler.execution.strategy=in-process`.
- Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
- Signature mismatch (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`, app was installed from another machine's debug key): `adb uninstall com.example.smartnotetaker` first. This wipes the user's stored API keys and settings; warn before doing it.
- Enable the keyboard in system settings; grant mic permission from the CheapWhisper app, not the IME.

## Conventions
- Kotlin only. IME work runs in `serviceScope` (`Dispatchers.Main + SupervisorJob`); network/file/model calls use `withContext(Dispatchers.IO)`. Streaming callbacks must be posted to the main thread (`Handler(Looper.getMainLooper())`).
- Networking: OkHttp + `org.json`. No Retrofit, no Gson/Moshi, no DI framework.
- Audio contract: 16 kHz, mono, 16-bit little-endian PCM (32,000 bytes/s; WAV header 44 bytes). Providers needing another format convert themselves (see `OpenAiStream` upsampling to 24 kHz).
- Providers are string constants `PROVIDER_*` in `MainActivity.kt`; the string is what `SecureStorage` persists as `model_choice`, so never rename one. `isLocalProvider()` = `startsWith("Local")`.
- Cost metering: `UsageTracker.add(provider, micros)` with amounts from `CostEstimator` (`transcriptionMicros`, `streamingMicros`, `llmMicros`). Every paid provider needs a rate in `usdPerMinute` and, if it streams at a different price, `streamingUsdPerMinute`; add it to `COST_PROVIDERS` so it appears in the breakdown. LLM spend goes to the separate `PROVIDER_LLM` bucket.
- Settings persistence: `SecureStorage` has one `saveX()`/`getX()` pair per setting with a string pref key and a default in the getter. `ApiKeys` is the snapshot passed around; read it via `getApiKeys()`.
- Settings UI: Compose Material3. Pickers are a plain `OutlinedButton` + `DropdownMenu`/`DropdownMenuItem`. Do not use `ExposedDropdownMenuBox` (it re-anchored on every scroll frame and caused jank; see commit 269c444). API key inputs use `ApiKeyField` (password transformation).
- Recordings under `MIN_RECORDING_BYTES` (1 s) are discarded as accidental taps.
- Commit messages are short imperative summaries (see `git log --oneline`).

## Adding a transcription provider
1. `MainActivity.kt`: add `PROVIDER_FOO`, its endpoint constant, and append to `TRANSCRIPTION_PROVIDERS` and `COST_PROVIDERS`.
2. `ApiKeys`: add a field and a `keyFor` branch; `SecureStorage`: add `saveFooApiKey`/`getFooApiKey` and include it in `getApiKeys()`.
3. `SettingsScreen`: add a `remember` state, an `ApiKeyField` shown when `modelChoice == PROVIDER_FOO`, and persist it in the save action.
4. `AIProcessor.transcribe`: add a `when` branch returning `Stt(text, billedSeconds?)`; reuse `transcribeOpenAiCompatible` if the API is OpenAI-shaped.
5. `CostEstimator`: add rates to `usdPerMinute` and, if applicable, `streamingUsdPerMinute`.
6. Optional live preview: implement `LiveTranscriber` mirroring `DeepgramStream` — OkHttp `WebSocket`, `CompletableDeferred<String>` for the result, finals accumulated on the reader thread with a `@Volatile` snapshot, callbacks posted via `Handler` to main, `finish()` bounded by `withTimeoutOrNull(8_000)`, `cancel()` idempotent, and `finish()` must throw if the socket failed so the IME falls back to the one-shot request on the WAV. Register it in `VoiceKeyboardService.createLiveTranscriber`.
7. Build, run the offline unit tests, then verify the IME on a device (only with the user's go-ahead).

## IME state machine (VoiceKeyboardService)
- `recordMode`: `null` (idle) | `"write"` (dictate and commit) | `"modify"` (spoken edit instruction applied to the whole field by the LLM). Buttons are push-to-talk (`ACTION_DOWN` -> `startRec`, `ACTION_UP` -> `stopAndProcess`).
- `processing`: true from release until the pipeline's `finally` runs `finishUi()`; all buttons are disabled and the cancel X appears. `imeJob` is the pipeline coroutine.
- Live preview (always on, no setting): cloud providers with streaming get a `LiveTranscriber` fed from `WavRecorder.onPcm`; local Whisper gets pause-delimited chunk transcription (`startChunkedPreview`); others show text only on release. In write mode preview is `setComposingText` (underlined) in the field; in modify mode it goes to `tv_transcript`.
- Pipeline: `stream.finish()` (or `AIProcessor.transcribe` when no stream / stream failed) -> cleanup (`cleanText`/`cleanTextLocal`) or modify (`modifyText`/`modifyTextLocal`) -> `commitText` / `replaceFieldText`.
- Retry: on a failed dictation the recorded WAV is copied to `retry_audio.wav`, `pendingRetry` is stashed (mode, wav, rawText-if-transcribed, field-before snapshot), and a Retry button (`btn_retry`) re-runs only the failed stage via `applyResult`/`aiProcessor.transcribe`. Cleared by a new recording or a successful run. `applyResult` is the post-transcription pipeline shared by `stopAndProcess` and `retry`.
- `cancelCurrent()` aborts a recording or cancels the in-flight job (`imeJob.cancel()`, `liveStream.cancel()`, `aiProcessor.cancelInFlight()`); it is called silently on `onFinishInputView`/`onFinishInput` (focus loss), and on error the preview text already captured is committed rather than lost.

## Testing
- No JVM unit tests exist; nothing runs under `./gradlew test`.
- JVM unit tests in `app/src/test` (Robolectric + MockWebServer): one class per streaming client, a shared `LiveTranscriberContractTest`, `ApiKeyValidatorTest`, `WavRecorderTest`. Run `./gradlew :app:testDebugUnitTest -Pkotlin.compiler.execution.strategy=in-process`. Each stream class takes a trailing `endpoint` constructor parameter and `ApiKeyValidator.baseUrlOverride` exists only so tests can point them at a mock server. Keep these green; add a mock test for any new provider.
- `LiveProvidersJvmTest` (same source set) hits the real APIs from the JVM using `.env` keys passed as system properties by `app/build.gradle.kts`; skipped when a key is blank. Prefer it over device tests.
- Live integration tests live in `app/src/androidTest` (instrumented, hit real provider APIs, sample audio in `androidTest/assets/jfk.wav`). Run with `scripts/live-tests.sh` (wraps `:app:connectedDebugAndroidTest`; set `ANDROID_SERIAL` to pick a device). Keys come from a root `.env` (copy `.env.example`); `app/build.gradle.kts` passes them as instrumentation-runner arguments, never compiling them into an APK. Tests for providers with no key are skipped. They need a connected device and spend real money. Never launch them without the user's explicit go-ahead: AGP's connected-test task reinstalls the app and, by default, UNINSTALLS it afterwards, wiping the user's on-device API keys and settings. `scripts/live-tests.sh` passes `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true` to prevent that; always run through the script, never the bare gradle task.
- Manual check for any IME change: build, install, dictate into a real app's text field (write, modify, retry after a failure, cancel mid-processing, focus loss while recording).

## Gotchas
- Settings: selectors are one-row label + `OutlinedButton` (plain choice name; rates only in the menu). The cleanup prompt is edited in an `AlertDialog`. API key fields validate on change (700 ms debounce) and on save via `ApiKeyValidator` (cheap GET per provider; `PROVIDER_LLM` checks the OpenAI key against `OPENAI_LLM_MODEL`).
- The keyboard's bottom row shows the current transcription model, language and cleanup model (`tv_config`, refreshed in `onStartInputView`).
- `SecureStorage` uses `EncryptedSharedPreferences` (`secret_shared_prefs`, AES256-GCM master key). Data is per-install: uninstall or a new signing key loses it, and it is not readable via `adb shell` prefs dumps. `UsageTracker` uses plain `usage_prefs`.
- `VoiceKeyboardService` is `directBootAware`: it can be bound after a reboot before the user unlocks, when credential-encrypted storage throws. Nothing in `onCreate`/`onCreateInputView`/`onStartInputView` may construct `SecureStorage` (or read `filesDir` models) unless `isUserUnlocked()` is true; the service greys its buttons and shows "Unlock phone to use CheapWhisper" until `ACTION_USER_UNLOCKED` arrives. Losing this guard makes the keyboard crash at boot and get disabled by the system, which is the original "re-enable after reboot" bug.
- `UsageTracker` lives in device-protected storage (`createDeviceProtectedStorageContext()`), migrated once from the old CE prefs; keep it there so the IME works pre-unlock.
- The IME process is long-lived. Local Whisper contexts are cached in `AIProcessor` (`localMutex`/`localCtx`); always release with `aiProcessor.releaseLocalModel()` when a pipeline ends (`finishUi()` does this) or memory stays pinned across apps.
- Never log API keys, request headers, or full request bodies. `Log.w/e` with the exception is fine.
- `.env` (provider keys for live tests) is gitignored and must stay that way; `.env.example` is the committed template. Never paste keys into code, docs, test assets, or commit messages.
- OpenAI `gpt-5.6-luna` rejects sampling params (temperature etc.); reasoning effort is pinned `low` via `OPENAI_REASONING_EFFORT`.
- Local model files are large (Gemma ~2.5 GB) and live in `filesDir`; `LocalModelDownloader` follows redirects manually and deletes partial downloads on failure.
- `keyboard_view.xml` disables child clipping so scaled (mic-level) buttons are not cropped; keep padding if you touch the layout.
