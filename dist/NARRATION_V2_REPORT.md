# Narration V2 Report

## Build result

- Source implementation commit: `8ee7b0c`
- APK: `NovelVoiceReader-arm64-debug-narration-v2.apk`
- Size: `263563538` bytes
- SHA-256: `dd07cbb86eae567c78b92ebd869cae5f7828143f68c5b918c6591143d32827d2`
- Gates: `clean`, 64/64 unit tests, `lintDebug`, `assembleDebug`, APK signing/alignment/content verification all passed.

## Controller and state machine

`NarrationController` is the intent/event state authority. Its states are `IDLE`, `PREPARING`, `WARMING_BUFFER`, `BUFFERING`, `PLAYING`, `PAUSED`, `SEEKING`, `STOPPING`, `ERROR`, and `RELEASED`. UI actions enter through controller-backed session methods; synthesis, playback, buffer, and Binder-death events update the same snapshot.

## Playback speed

User speed supports the bounded set 0.75/1.0/1.25/1.5/1.75/2.0. Canonical TXT/DOCX/PDF/Web playback changes the existing ExoPlayer with `PlaybackParameters(speed, 1f)` and does not recreate the player, document, chunks, or cache. EPUB continues through Readium's Media3 player adapter. Kokoro requests use synthesis profile speed `1.0f`; playback speed is absent from `TtsCacheKeyInput`. The speed-authority and cache-key tests cover the required boundary values and transition sequence.

## Buffer and RTF policy

The common measure is `bufferWallMs = remainingGeneratedMediaDurationMs / playbackSpeed`. Remaining duration subtracts current player position and then adds queued chunk duration. Warm start is 15 s at up to 1.25x, 25 s at 1.5x, and 45 s at 1.75x or 2x.

For a runtime-detected `HIGH_MEMORY` profile, normal low/target/high wall marks are 30/90/180 s. When `p95RTF * playbackSpeed` reaches `AT_RISK`, the policy raises them to 45/150/300 s. `UNSUSTAINABLE_REALTIME` enables aggressive pre-generation without silently lowering user speed. Temporary PCM is bounded by the device profile; completed audio is moved to disk.

`RtfTracker` records generation time divided by audio duration and calculates p50/p90/p95. The diagnostics screen runs a fixed 20-segment offline benchmark in `:tts` and persists the latest profile. No device was connected for this delivery, so measured p50/p95 and minimum buffer are `NOT_RUN`, not zero.

## Segments, chunks, and cache

Text cleaning, Chinese normalization, semantic segmentation, and prosody planning produce stable `SpeechSegment` IDs. `ChunkAssembler` groups 3–8 segments and targets 12–24 seconds of media while writing a `ChunkTimeline` with segment IDs, time bounds, pauses, and source anchors. Cache keys contain model version, voice, normalized text, synthesis profile, and normalizer version, but not playback speed. Disk capacity is selected from free space up to 4 GiB with a 4 GiB safety reserve and LRU eviction.

## TTS process and crash isolation

Kokoro and sherpa-onnx run only in `TtsInferenceService` with `android:process=":tts"`. IPC carries text/profile/path metadata, never a large `FloatArray`. The service serializes inference, writes a `.part` WAV, validates it, and atomically publishes the result. Production source contains no sherpa callback generation API. Binder death triggers one rebind/retry; a second death stops playback and enters `ERROR/TTS_ENGINE_DIED` without terminating the reader process.

## Trace and device result

Privacy-safe JSONL traces contain IDs and timings but no document text, and can be exported through SAF. At `2026-08-14T23:12:04+08:00` and again at `23:24:13+08:00`, ADB returned no connected device. Installation, audible output, 1x/1.5x/2x runtime switching, RTF, RAM, gaps, underruns, native aborts, and the required 30-minute run are therefore `NOT_RUN_DEVICE_DISCONNECTED`.

## Transition limitations

EPUB keeps the existing Readium rendering/narration bridge instead of the new canonical chunk queue. Canonical-format ExoPlayer playback is currently activity-scoped rather than hosted by the existing `MediaSessionService`; background and lock-screen control for these formats require device-tested service integration. See `KNOWN_LIMITATIONS.md`.
