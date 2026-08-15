# Narration Architecture

One `NarrationController` is the only state authority for every source type. UI sends intents; it never coordinates player, cache, or TTS directly.

Inputs are `CanonicalDocument`, `ContentBlock`, and `DocumentLocation`. `SpeechPlanner` cleans and normalizes text, performs semantic segmentation, and assigns prosody. `PrefetchPlanner` orders unique segment IDs and selects a wall-time target from device profile, measured RTF, speed, memory budget, chapter boundaries, and cached artifacts.

`TtsClient` requests callback-free synthesis from `TtsInferenceService`. The service writes `*.part`, validates the WAV, atomically renames it, and returns `AudioArtifact`. The main process stores and trims artifacts using `AudioCache`. `ChunkAssembler` combines 3-8 segments into 12-24 seconds of media and records a `ChunkTimeline`. The playback queue exposes one Media3 player and `MediaSessionService`.

Visual synchronization uses immutable segment-to-anchor mappings. Reader adapters may highlight or follow, but cannot mutate narration order.

Exactly one synthesis request may execute at a time. Segment and chunk identities are stable, making retries idempotent and preventing duplicate, skipped, or out-of-order playback.
