# Narration State Machine

States: `IDLE`, `PREPARING`, `WARMING_BUFFER`, `BUFFERING`, `PLAYING`, `PAUSED`, `SEEKING`, `STOPPING`, `ERROR`, `RELEASED`.

User intents: `Start`, `Pause`, `Resume`, `Stop`, `Next`, `Previous`, `Seek`, `SetPlaybackSpeed`, `SetVoice`, `SetStyle`.

System events: `DocumentChanged`, `SynthesisStarted`, `SynthesisCompleted`, `SynthesisFailed`, `PlaybackStarted`, `PlaybackPosition`, `PlaybackCompleted`, `PlaybackFailed`, `BufferLow`, `BufferRecovered`, `TtsProcessDied`.

Important transitions:

- `IDLE -> PREPARING -> WARMING_BUFFER -> PLAYING` after a valid document and sufficient initial wall buffer.
- `PLAYING -> BUFFERING` on depleted generated media; zero buffer is state, not an exception.
- `BUFFERING -> PLAYING` only after recovery satisfies the resume threshold.
- `PLAYING <-> PAUSED`; speed changes do not leave either state or rebuild the player.
- `* -> SEEKING -> WARMING_BUFFER/PLAYING` uses a new queue epoch so stale synthesis results cannot enter playback.
- `* -> ERROR` on unrecoverable synthesis/playback failure. `TtsProcessDied` maps to `TTS_ENGINE_DIED`, stops playback, preserves location, and permits one automatic rebind.
- `* -> STOPPING -> IDLE`; `* -> RELEASED` is terminal.

Every transition is serialized and emits a trace event without document text.
