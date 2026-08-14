# ADR 0004: TTS Separate Process

Status: Accepted.

Kokoro, sherpa JNI, and the single inference worker run in `TtsInferenceService` declared with `android:process=":tts"`. Binder messages are small and file based. Native death maps to a controller error, leaves the reader/database alive, preserves position, stops playback, and permits one rebind. No Room, Readium, UI, or main player code enters the TTS process.
