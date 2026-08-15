# ADR 0002: Single Narration Controller

Status: Accepted.

One serialized `NarrationController` owns state, queue epoch, speed, voice/style selection, buffer response, TTS failure handling, and playback coordination. UI sends typed intents and observes immutable state. Reader adapters receive synchronization events. Multiple controllers or UI-driven orchestration would permit conflicting transitions and duplicate playback.
