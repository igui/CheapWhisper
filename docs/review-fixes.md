# Review fixes and offline dictation recovery

The initial Claude and Codex reviews covered `40c70d4..a654b60`. The changes below address their concrete findings and the reported loss of offline live dictation.

| Finding | Resolution |
|---|---|
| Usage reset discards active paid response | Track active request IDs; reset preserves those rows, while released/unconfirmed historical rows can be cleared. Completion and release remain serialized. |
| Modify retry overwrites changed/different field | Require original editor/text before retry and recheck connection/content before committing a modification. Restored input sessions need explicit recovery confirmation. |
| Retry cancellation leaves composing text | Both retry error/cancellation paths discard preview through its original input connection. |
| Keyboard OpenRouter has short timeouts | Use the configured OpenRouter client for keyboard requests; canceling the pipeline cancels both clients. |
| API 26–28 non-Opus import | Replace MediaMetadataRetriever `.use` with `try/finally release()`. |
| Recents resubmits shared audio | Ignore `FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY` shares. |
| Invalid/unreachable edited key traps Settings | Preserve old key, save other settings, report failed key update and close. Treat temporary 429/5xx as unreachable. |
| Microphone permission instruction is stale | Direct the user explicitly to Settings → Grant. |
| Refreshed model unavailable to keyboard/restart | Persist refreshed catalog metadata and use it for file/keyboard selection resolution. |
| Tiny final audio tail fails whole transcription | Redistribute sub-second tails without dropping samples or exceeding chunk limits. |
| Short tap discards earlier retry | Starting a recording never deletes prior saved recordings; accepted recordings append to the retry store. |
| Canceled import leaks completed temporary file | Track ownership across cancellable IO dispatch and delete unadopted audio in non-cancellable cleanup. |
| Cue matches inside unrelated longer word | Apply word-boundary checks before mapping a cue to transcript characters. Highlight remains cleared during genuine silence intentionally. |
| Unbounded completed usage/main-thread reads | Compact older completed requests into exact aggregates, retain recent/pending records, and read keyboard cost data on IO. |
| Fixed OpenRouter rate is misleading in keyboard | Display reported spend without the unrelated hard-coded model rate. |

## Offline recording behavior

Every accepted keyboard recording is durably copied with its retry configuration before network/cleanup processing. The retry journal uses credential-protected app-private no-backup storage and contains no API keys. Failed network requests or service restarts no longer erase audio. Failed cleanup keeps the already completed transcript so retry need not transcribe the same audio again.

All five streaming clients now distinguish confirmed completion/silence from timeouts, unresponsive handshakes, premature closes and provider failures. Unconfirmed completion throws, allowing the recorded WAV fallback. If offline, audio remains saved until connectivity returns or the user retries.

Automatic recovery requires the original live InputConnection, original editor identity and unchanged field text while the keyboard is visible. User edits/selection changes and new recordings invalidate automatic recovery; each saved item gets at most one automatic attempt. Persisted editor IDs alone cannot identify a chat safely. After changing/restarting editor sessions, the user must tap Retry again to confirm recovery into the matching field. Multiple saved recordings are retained; one from another editor does not prevent recovery of a matching entry. Explicit Cancel removes that attempt. Focus-loss cancellation preserves accepted audio. Hold Retry to discard unwanted saved audio.

## Follow-up review dispositions

Claude's second pass identified stale auto-resume after a later user edit, lack of discard, overly strict connectivity gating, storage failures blocking dictation, and CJK alignment. These are addressed by invalidating original-connection bindings on edits/new recordings, explicit Cancel/hold-Retry discard, allowing actual requests regardless of Android validation, graceful unsaved processing and malformed-row handling, and script-aware word boundaries. Catalog caches now carry the app version and merge updated bundled metadata; successful retry removal is non-cancellable. Settings preserving the old key on unreachable validation remains intentional. Premature streaming completion is never treated as success merely to avoid fallback charges. Input-connection restart compatibility needs manual validation with target apps.

## Verification

- Full offline suite: 143 tests passed after the follow-up fixes; focused lifecycle/accounting checks also cover the final persistence adjustments.
- Android app and test APK builds and lint passed. Existing Kotlin/LiteRT lint metadata warnings remain.
- No paid transcription or live-provider test was run. Stream failure paths use MockWebServer; persistence/cancellation/accounting are covered by JVM tests.
- Review follow-up checked original-connection gating, queue selection, cancellation and active-only billing reset. Device network-outage dictation and Android 8/9 import still need manual validation; source compatibility and simulated failure paths are covered.
