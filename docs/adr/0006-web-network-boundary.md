# ADR 0006: Web Network Boundary

Status: Accepted.

Internet permission is allowed solely for explicit user URL import. Network clients live under `content/web`; all other content, TTS, OCR, playback, database, and reader behavior remains offline. Only HTTP(S) is accepted. Static extraction is primary, a hardened WebView is fallback, and immutable snapshots isolate narration from later network or page changes.
