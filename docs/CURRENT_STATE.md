# Current State

Update: 2026-08-15. Narration V2 is now extended with content-first local detection, Android VIEW/SEND import, per-segment ZH/EN/JA routing, a verified Japanese-unsupported backend boundary, 5–8-second first-play policy, RTF-driven native threads/chunks/queue depth, language-complete cache keys, and stale-epoch rejection. The current validation is 77 JVM tests, Lint with 0 errors, and a successful debug APK build. No ADB device is connected, so runtime measurements remain pending. See `dist/IMPORT_LANGUAGE_RUNTIME_REPORT.md`.

## Narration V2 audit baseline

Audit date: 2026-08-14. The local working tree, not GitHub `main`, is the source of truth.

## Repository and build

- Audit started on `codex/build-deliverable-apk` at `ca4e950`; work was moved without discarding changes to `codex/narration-v2-multiformat-reader`.
- Existing uncommitted work fixes the Android 15 sherpa callback JNI abort in `KokoroTtsEngine`, adds a forbidden-callback test, tokenizer coverage, a debug smoke activity, and crash-fix reports. It is preserved.
- Android application module: `app`; application ID `com.tl2333.novelvoicereader`; ARM64 only; min SDK 26; target/compile SDK 36.
- Toolchain: Gradle 9.1.0, AGP 9.0.0, Kotlin 2.3.20, JDK 17.
- Locked core dependencies: Readium 3.2.0, Media3 1.10.0, Room 2.8.4, DataStore 1.2.1, sherpa-onnx 1.13.4, Kokoro commit `155831f1b4ba23b1f5c058be6a61df90cefb2a37`.
- The repository includes the locked Readium and sherpa source trees under `.codex/vendor`; the sherpa AAR and Kokoro assets are present locally and are verified by the build.
- At audit time `adb devices -l` returned no connected device. The earlier crash-fix report records a successful Xiaomi/POCO Android 15 smoke run, but Narration V2 performance has not been measured.

## Working functionality to preserve

- SAF EPUB import with signature, archive, free-space, duplicate, and atomic-copy checks.
- Readium EPUB rendering, table of contents, full Locator progress, bookmarks, decorations, and fixed-layout support.
- Offline Kokoro/sherpa synthesis, packaged model verification, selectable Chinese voices, Readium TTS navigation, sentence highlighting, and diagnostics.
- Room library/progress/bookmark/cache metadata, DataStore reader/TTS preferences, Media3 MediaSession integration, and disk WAV LRU cache.
- The preserved crash fix uses `OfflineTts.generateWithConfig`; production contains no callback generate call.

## Current architecture and gaps

- The app is one Gradle module. `core/*` and `feature/*` contain empty scaffolding and are not included by `settings.gradle.kts`.
- `BookEntity`, `PublicationManager`, and the UI are EPUB-specific. There is no canonical multi-format document model.
- Readium `TtsNavigator` is the narration coordinator. UI/view-model and the MediaSession client control it through `ReaderNarrationNavigator`; there is no format-neutral `NarrationController` state machine.
- Kokoro owns synthesis and `AudioTrack` playback in the main app process. Native inference is not isolated in `:tts`.
- The current provider maps the same preference speed to Media3 `PlaybackParameters` and to sherpa `GenerationConfig.speed`. The cache key also includes that speed. This is the double-speed/cache-invalidation defect to remove.
- Cache defaults to a user preference of 512 MiB; it is not derived from free space. WAV entries are sentence scoped, and PCM for an utterance is accumulated in memory before caching.
- No RTF history, wall-time buffer planner, warm buffer, playback chunks, narration trace, or device performance profile exists.
- Only EPUB is supported. TXT, DOCX, DOC, PDF display/text/OCR, URL fetching, article extraction, snapshots, and canonical document readers are absent.
- The manifest explicitly removes Internet permissions. That boundary must be deliberately revised only for `content/web`.

## Duplication and temporary code

- `BookEntity` plus Readium Locator persistence are reusable but format-specific names prevent direct multi-format reuse.
- `ChineseSentenceTokenizer` and `ChineseReadiumTextTokenizer` are separate adapters; the latter delegates Readium tokenization while the former protects Kokoro native input. Narration V2 must put semantic segmentation before all engines without deleting the Readium adapter until EPUB migration is proven.
- `NarrationMediaSessionService` and the Readium navigator currently combine coordination, player adaptation, and lifecycle. These responsibilities must be split while keeping one formal Media3 player.
- `app/src/debug/DeviceSmokeActivity` is intentional debug-only regression support, not production architecture.
- Empty `core/*` and `feature/*` directories are unused scaffolding; they are not treated as implemented modules.

## Baseline risks and gates

- Never reintroduce sherpa callback generation.
- Preserve the existing EPUB path until a replacement passes import/open/progress/narration tests.
- Migrate speed authority before performance conclusions: synthesis remains at profile speed 1.0, user speed is Media3-only, and playback speed is excluded from cache keys.
- A native-process boundary requires file-based artifacts and Binder death handling; large PCM arrays must never cross Binder.
- Device claims remain `NOT_RUN` until a device is connected and measured.
