# Unified transcription verification

## Completed checks

- 110 offline JVM tests passed on the installed 0.3.9 implementation; the full suite excludes `LiveProvidersJvmTest` using `-PofflineTests=true`.
- Debug builds passed for arm64-v8a and x86_64, including the existing whisper.cpp native libraries. APK signature verification passed.
- Android lint completed successfully. Existing older-toolchain/newer-LiteRT/Kotlin metadata warnings remain; no lint baseline was introduced.
- Two local-only instrumentation tests passed on a OnePlus DN2103 / Android API 33: synthetic Ogg/Opus mono and WebM/Opus stereo import, decoding, non-silent 16 kHz mono WAV output, duration, playback preparation and intermediate-file cleanup.
- Updated the existing CheapWhisper package with `adb install -r` and verified launch; no package uninstall or settings wipe occurred.
- No paid provider test or real provider-key validation was run during implementation. Mock servers exercise network requests and cancellation.

## Coverage

Existing offline tests cover direct-provider streaming/REST, API-key validation and WAV handling. New tests cover family deduplication and old-selection migration; share URI/MIME validation; cold/warm automatic transcription; saved-model readiness; cancellation and replacement-share suppression; missing-key behavior; OpenRouter request/response/error contracts; strict key metadata checks; scoped timestamp fallback; decimal cost accounting; unknown usage; timestamp parsing and SRT; preserving complete text when timing is partial; continuous text and metric-stable highlights; hourly estimate formatting; and conditional result provenance.

## Remaining device checks

Live model accuracy, latency, language coverage, timestamp support and billing require an actual OpenRouter key and audio. Test keyboard write/modify/retry/cancel in a real input field after configuring providers. The original IME retry edits and direct-boot guards are retained. Long jobs require the app to remain alive; there is no process-death recovery. Fixed chunk boundaries may affect recognition. Check unconfirmed charges against the provider account.

## Local migration

The consolidated app retains `com.example.smartnotetaker`. Eleven non-secret usage records were merged from the superseded AudioScribe installation by request ID and the copy verified. AudioScribe was disabled, not uninstalled, preserving its data. Its per-package encrypted key was not read or transferred; users must enter their OpenRouter key once in CheapWhisper. Existing CheapWhisper credentials remain in its original encrypted preferences.

## Review fixes and offline recovery (0.3.10)

See [review-fixes.md](review-fixes.md) for the Claude/Codex finding dispositions. The expanded suite has 143 passing offline tests, including streaming timeout/failed-handshake recovery across all five providers, durable retry storage, multiple queued recordings, safe editor matching, active usage reset, compaction, Recents suppression, catalog persistence, tiny-tail handling and non-spaced-script alignment. App/test APK builds and lint pass. Device Opus tests passed before the final follow-up adjustments and are rerun on the final build. Real paid dictation through an actual network outage still requires manual testing; no provider keys or paid tests were used.

## IME deletion compatibility (0.3.11)

Backspace now finishes any previous IME composing region, deletes selected text, otherwise removes a Unicode code point with UTF-16/key-event fallbacks. Hold-to-repeat stays bound to its starting input connection and stops on focus loss or recording; deletion is disabled during processing. Six targeted Robolectric tests passed, as did lint and app/test APK builds. A no-network instrumentation test on the OnePlus Android 13 device passed against a native EditText containing pre-existing text, selection and composing emoji. No paid dictation or user text was used. Other apps with custom editors still need user verification.
