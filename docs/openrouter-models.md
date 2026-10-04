# OpenRouter transcription models and costs

Verified 2026-10-03 using the [transcription catalog](https://openrouter.ai/models?output_modalities=transcription), [filtered models API](https://openrouter.ai/api/v1/models?output_modalities=transcription), each model's displayed pricing units and [Artificial Analysis non-streaming AA-WER v2](https://artificialanalysis.ai/speech-to-text).

This is prerecorded **transcription**, retaining the spoken language, not translation between languages. WER measures transcription errors; it is not a translation score or a separate score for Spanish/Catalan/Italian. Lower WER is better. Benchmark hosts can differ from OpenRouter routes; these are matched model reference results, not measurements of this app.

## Historical ten-model comparison

These are the ten lowest matched AA-WER entries in the current OpenRouter catalog after excluding unresolved version aliases. MAI-Transcribe 2 is the app default: strong reference accuracy, all four requested languages, timestamp support and $0.10/hour.

| Model | AA WER | OpenRouter catalog pricing | Timing |
|---|---:|---|---|
| MAI-Transcribe 2 | 2.04% | $0.10/hour | Requested via verbose_json; route-dependent |
| MAI-Transcribe 1.5 | 2.38% | $0.36/hour | Requested via verbose_json; route-dependent |
| Gemini 3.5 Transcribe | 2.60% | $2.00 input / $12.00 output per million tokens; variable/hour | Requested via verbose_json; route-dependent |
| Voxtral Small 24B 2507 STT | 2.77% | From $0.18/hour | Requested via verbose_json; route-dependent |
| Universal-3.5 Pro | 3.02% | From $0.45/hour | Requested via verbose_json; route-dependent |
| GPT Transcribe | 3.31% | From $0.27/hour | Requested via verbose_json; route-dependent |
| Voxtral Mini Transcribe | 3.59% | From $0.18/hour | Requested via verbose_json; route-dependent |
| Voxtral Mini 3B 2507 | 3.84% | From $0.06/hour | Requested via verbose_json; route-dependent |
| GPT-4o Transcribe | 3.96% | $2.50 input / $10.00 output per million tokens; variable/hour | Text only |
| Whisper 1 | 4.06% | From $0.36/hour | Requested via verbose_json; route-dependent |

Token-priced models do not have a fixed honest hourly conversion without assumptions about billed input/output token volume. The app shows their token rates and the actual reported cost after each request. Duration-priced rates are converted using the verified unit (seconds × 3,600 or hours unchanged). All displayed dollars round to cents; positive sub-cent amounts use `< $0.01`.

## All 24 available models

The app now shows one model per family from this snapshot and offers **Models & pricing → Refresh catalog**. Known model rates refresh from the filtered API; new model pricing units are labeled unverified until reviewed. AA scores and unit metadata remain a dated snapshot. Refresh does not silently invent WER scores or assign a per-second unit to unknown prices.

| Model ID | AA WER | Catalog pricing |
|---|---:|---|
| `microsoft/mai-transcribe-2` | 2.04% | $0.10/hour |
| `microsoft/mai-transcribe-1.5` | 2.38% | $0.36/hour |
| `google/gemini-3.5-transcribe` | 2.6% | $2.00 input / $12.00 output per million tokens; variable/hour |
| `mistralai/voxtral-small-24b-2507-stt` | 2.77% | From $0.18/hour |
| `assemblyai/universal-3-5-pro` | 3.02% | From $0.45/hour |
| `openai/gpt-transcribe` | 3.31% | From $0.27/hour |
| `mistralai/voxtral-mini-transcribe` | 3.59% | From $0.18/hour |
| `mistralai/voxtral-mini-3b-2507` | 3.84% | From $0.06/hour |
| `openai/gpt-4o-transcribe` | 3.96% | $2.50 input / $10.00 output per million tokens; variable/hour |
| `openai/whisper-1` | 4.06% | From $0.36/hour |
| `openai/whisper-large-v3` | 4.07% | From $0.03/hour |
| `google/chirp-3` | 4.32% | From $0.96/hour |
| `openai/gpt-4o-mini-transcribe` | 4.47% | $1.25 input / $5.00 output per million tokens; variable/hour |
| `openai/whisper-large-v3-turbo` | 4.62% | From $0.01/hour |
| `deepgram/nova-3` | 5.18% | From $0.26/hour |
| `qwen/qwen3-asr-flash-2026-02-10` | 5.8% | From $0.13/hour |
| `x-ai/grok-stt-1.0` | Not matched / unavailable | From $0.10/hour |
| `meta/muse-voice-transcribe-1.0` | Not matched / unavailable | From $0.18/hour |
| `nvidia/nemotron-3.5-asr-streaming-multilingual-0.6b` | Not matched / unavailable | From $0.01/hour |
| `nvidia/parakeet-tdt-0.6b-v3` | Not matched / unavailable | From $0.09/hour |
| `qwen/qwen3-asr-0.6b` | Not matched / unavailable | From $0.01/hour |
| `qwen/qwen3-asr-1.7b` | Not matched / unavailable | From $0.03/hour |
| `fish-audio/transcribe-1` | Not matched / unavailable | From $0.36/hour |
| `fish-audio/transcribe-1-pro` | Not matched / unavailable | From $0.36/hour |

## Qualifications and routing

- `microsoft/mai-transcribe-2` and `microsoft/mai-transcribe-1.5` use **hourly** pricing, confirmed by their model pages ($0.10 and $0.36/hour). Multiplying those rates by 3,600 would be incorrect.
- Gemini 3.5 Transcribe and GPT-4o/Mini Transcribe use token pricing. No invented hourly rate is displayed.
- `mistralai/voxtral-mini-transcribe` canonical version 2602 matches AA's Voxtral Mini Transcribe 2. `mistralai/voxtral-mini-3b-2507` uses AA's hosted Voxtral Mini/DeepInfra result (3.84%).
- `openai/whisper-1` maps to the OpenAI Whisper Large v2 benchmark (4.06%). Whisper Large v3 uses AA's best model score (4.07%, fal.ai); OpenRouter's hosts differ. Voxtral Small's reference benchmark uses Mistral, while its OpenRouter host is DeepInfra. Turbo's reference uses Groq.
- OpenRouter still lists `x-ai/grok-stt-1.0`. AA reports 4.03% for Grok Voice Transcribe 1.0 and 2.29% for 2.0, but xAI documents redirection of its 1.0 endpoint to 2.0. Without verifying OpenRouter's current effective version, this alias is unranked instead of assigning it the newer model's score.
- Missing AA entries remain unranked; Parakeet v3 does not inherit the v2 score. Fish/Meta/Nemotron entries and Qwen 1.7B/0.6B have no matched AA-WER in this snapshot.
- Nova-3's base rate is about $0.26/hour; multilingual use is about $0.31/hour. Whisper pricing depends on the selected host and timestamp support: its lowest catalog route may not be used for a timestamped request.
- OpenRouter's model catalog does not expose a complete timestamp or language capability matrix. Known GPT-4o/Mini models are text-only; other models can request timestamps and show a clear error if the selected endpoint rejects them. Turn off Timestamps or choose MAI/Whisper in that case. No silent retry incurs an extra charge.

## API and accounting

[OpenRouter transcription tutorial](https://openrouter.ai/blog/tutorials/transcription-on-openrouter/) confirms `POST /api/v1/audio/transcriptions`, multipart `file` + `model`, `response_format=verbose_json`, and `timestamp_granularities[]=word/segment`. The [OpenAPI schema](https://openrouter.ai/openapi.json) is the source for current request fields and `GET /api/v1/key` validation. The filtered transcription catalog is separate from the default chat model catalog.

Audio is decoded locally into mono PCM and uploaded as WAV chunks, normally 60 seconds (30 for Qwen), bounded below 25 MB. This accommodates voice-note containers and limits upstream processing time. Chunk timestamps are offset back onto the original audio timeline. A word at a fixed chunk boundary may lose context; no overlapping audio is double-submitted. API timeouts remain possible.

Each successful response's `usage.seconds` and `usage.cost` are accumulated with decimal arithmetic, without per-request rounding. If duration is omitted, the measured chunk duration is marked as a fallback; missing costs remain explicitly unreported. Interrupted requests remain unconfirmed for reconciliation in the OpenRouter dashboard. Completed parts of failed/canceled jobs are included. Generation IDs are retained where returned. No audio/text/key is stored in the usage journal.

The lower-left panel groups by **model provider/publisher via OpenRouter**, and the Usage by model dialog shows individual models. These labels are not a claim about OpenRouter's actual infrastructure host. Totals cover this installation, across key changes; they are not account-wide billing. Providers with no completed or unconfirmed requests are omitted.

## Family-deduplicated selector (version 1.2)

The table above preserves the original research. The visible selector now has 14 families, including MAI-Transcribe 2, Voxtral Small, GPT Transcribe, Whisper 1, Qwen3 ASR 1.7B and Fish Transcribe 1 Pro as the representatives of their respective families. Family grouping applies on initial load and live refresh. Old selections migrate to their retained family; old usage entries remain accurate and are not merged into a different model.

## Hourly display estimates (0.3.8)

The historical tables above retain the source token rates. The app now displays approximate hourly cost for token-priced models, calculated as input-token price × 90,000 plus output-token price × 12,000. This assumes 25 input audio tokens/second and 200 output tokens/minute; actual tokenization and speech density vary. Estimates use the `~` prefix and never replace server-reported cost in the usage journal.
