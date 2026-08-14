# Known Issues at Upgrade Start

- Only EPUB is implemented.
- User speed currently affects both Media3 playback and Kokoro generation and is included in cache keys.
- Native TTS and playback execute in the main process; a native abort can terminate the reader.
- Narration is sentence-at-a-time Readium TTS with no wall-time synthesis buffer or chunk pipeline.
- Cache defaults to 512 MiB rather than free-space-based sizing.
- No RTF statistics, benchmark persistence, trace export, or device profile.
- No TXT, Word, PDF, OCR, URL, article extraction, dynamic Web fallback, or snapshots.
- The requested device is currently disconnected; runtime acceptance remains pending.
