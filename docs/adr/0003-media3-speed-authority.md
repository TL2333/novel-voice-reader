# ADR 0003: Media3 Speed Authority

Status: Accepted.

User speed from 0.75x through 2x is applied only as Media3 `PlaybackParameters(speed, 1.0f)`. Kokoro uses a synthesis profile independent of playback speed, normally 1.0. Speed changes preserve the current player, audio artifacts, document, queue identity, and cache. Playback speed is excluded from cache keys.
