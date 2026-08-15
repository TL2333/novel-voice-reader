# Import, language routing, and runtime narration upgrade

Validation date: 2026-08-15

## Root causes addressed

- Local import selected a parser from provider MIME or filename extension, so renamed files and generic `application/octet-stream` shares could be routed incorrectly.
- `MainActivity` only accepted `MAIN/LAUNCHER`; Android `ACTION_VIEW` and `ACTION_SEND` never entered the library pipeline.
- Canonical narration always applied Chinese normalization and had no language on `SpeechSegment`.
- Playback waited for a fixed 15/25/45-second warm buffer and every chunk used the same 3–8 segment, 12–24-second policy.
- The device benchmark affected watermarks but not native thread count, chunk shape, or queue depth.
- A generated segment was written to cache and immediately read back before chunking, and stale synthesis completion had no final cache/queue ownership check.

## Implementation

### Local and remote type detection

`DocumentTypeDetector` is now the shared policy for local and remote content. Detection order is fixed magic, safe ZIP structure, declared MIME, extension, then conservative text fallback.

- `%PDF-` wins over misleading MIME/name.
- OLE compound-file magic is reported as legacy DOC and rejected with conversion guidance.
- EPUB requires `mimetype=application/epub+zip` and `META-INF/container.xml`.
- DOCX requires `[Content_Types].xml` and `word/document.xml`.
- Local ZIP inspection uses `SafeArchive` with entry, expanded-size, ratio, path, and depth limits.
- Only an 8 KiB local prefix is read for non-ZIP sniffing; the source is streamed to staging and is never loaded with `readBytes()`.
- UTF BOM, UTF-16 layout, UTF-8, declared text MIME, and text extensions are recognized for TXT.

### External Android imports

`MainActivity` now declares `VIEW` and `SEND` filters for PDF, text, EPUB, DOCX, MS Word, octet-stream, and generic shares. The pure `ExternalImportIntentRouter` accepts `data` for VIEW and `EXTRA_STREAM` (or data fallback) for SEND, rejects non-local schemes, and creates the unified `ImportRequest`.

Duplicate delivery is prevented across `onNewIntent` and activity recreation. Persistable read permission is attempted but failure is non-fatal. Every local/external URI is immediately streamed into app cache staging, sniffed there, parsed through the existing format importer, and committed to private storage/Room. No broad storage permission was added.

WeChat share chain:

`ACTION_SEND -> MainActivity -> intent router/deduplicator -> ImportRequest(ACTION_SEND) -> private staging copy -> DocumentTypeDetector -> EPUB/TXT/PDF/DOCX importer -> private library + Room -> bookshelf`

URL chain remains isolated:

`user URL -> UrlValidator/network destination policy -> WebFetcher -> DocumentTypeDetector.detectRemote -> article extractor or direct-document importer -> private web snapshot/library`

### Language routing and capability boundary

- `SpeechSegment` now carries `SpeechLanguage` (`ZH`, `EN`, `JA`).
- `LanguageDetector` uses Han, kana, and Latin scripts, with neighboring Japanese context for kanji-only segments.
- English segments use `EnglishTextNormalizer`; Chinese number/punctuation normalization is not applied to English technical tokens such as iPhone, AI, GPT, or URLs.
- Kokoro voices advertise ZH/EN support. The packaged `Kokoro v1.1-zh` assets contain Chinese and US/GB English lexicons but no verified Japanese lexicon/voice. Japanese is therefore not sent through a fake Chinese route: `JapaneseTtsBackend` is pluggable and the packaged implementation returns an explicit unsupported error.

### Startup, buffering, cache, and stale work

- First chunk: 1–3 segments, target 5–10 seconds, and playback begins on the first playable chunk.
- Following chunks: 3–8 segments, target 12–24 seconds; recent p95 RTF can choose conservative/risk variants within those bounds.
- Warm wall-time target changed from 15/25/45 seconds to 5/6.5/8 seconds for low/medium/high playback speeds.
- Native threads are selected at model initialization: LOW=2, NORMAL=4, HIGH=4 or 6 based on cores and a recent benchmark. Inference remains serialized through one model worker.
- Recent p95 RTF also affects watermarks, following chunk shape, and queue-ahead depth. Missing benchmark data selects conservative defaults.
- Queue generation pauses at the high wall-time/depth boundary and resumes as playback consumes data.
- Seek/start/stop advances a monotonic epoch. A stale Binder return cannot publish a cache entry or append a Media3 item. One Binder process-death rebind retry remains.
- Single-segment chunks can feed the cached/generated WAV directly to ExoPlayer. Newly synthesized PCM is no longer written and immediately reread from disk.
- Cache identity now includes model commit, sherpa version, normalized text, voice, language, style/synthesis profile, tokenizer version, and normalizer version. Media3 playback speed is intentionally absent.

## Automated validation

- `testDebugUnitTest`: 77 tests, 0 failures, 0 errors, 0 skipped (previous Narration V2 baseline: 64 tests).
- `lintDebug`: 0 errors, 48 pre-existing/non-blocking warnings.
- `assembleDebug`: successful.
- APK: `NovelVoiceReader-arm64-debug-import-language-v3.apk`, 263,687,255 bytes.
- SHA-256: `9b74ffd7dee8d0cefb35ae495c635f7aff92b907b43f0a8201b7b29927472c68`.

New coverage includes magic/ZIP type detection, VIEW/SEND/unsupported/duplicate routing, language and kanji-context detection, honest voice capability, first/following chunk bounds, 5–8-second warm targets, RTF/thread/depth fallback, and seek/stop epoch invalidation. Existing 2x buffer, playback-speed cache, Binder architecture, archive, URL, OCR/PDF, DOCX, TXT, EPUB, and narration state tests remain green.

## Device status and performance claims

The Android SDK ADB executable was found, but `adb devices -l` returned no connected device. Installation, WeChat handoff, audible ZH/EN synthesis, Japanese error UI, first-audio wall time, cache-hit latency, p95 RTF, 2x underrun behavior, thermal behavior, and long-run stability are `DEVICE_RUNTIME_PENDING`.

The measurable code-policy change is 15/25/45-second warm targets to 5/6.5/8 seconds plus a smaller 1–3-segment first chunk. No real-device latency or RTF improvement is claimed until the checklist is run on hardware.
