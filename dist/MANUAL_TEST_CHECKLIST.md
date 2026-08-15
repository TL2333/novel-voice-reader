# Import/Language/Narration V3 Manual Device Checklist

Current execution status: `NOT_RUN_DEVICE_DISCONNECTED`.

## Device and installation

- [ ] `I7SGOJXWBMPBZ9AA` appears as `device` in `adb devices -l`.
- [ ] Install `NovelVoiceReader-arm64-debug-import-language-v3.apk` with `adb install -r`.
- [ ] Confirm Xiaomi 2311DRK48G, Android 15 / SDK 35, arm64-v8a, RAM and available storage.
- [ ] Confirm runtime profile is `HIGH_MEMORY` without a model-name override.
- [ ] App and reader main process survive a forced `:tts` process death and permit one retry.

## Format acceptance

- [ ] EPUB: import, open, navigate, narrate, restore progress, bookmark, sentence decoration.
- [ ] UTF-8 and GB18030 TXT: import, chapter detection, read, narrate, restore, bookmark.
- [ ] DOCX: title, heading, paragraph, list, table; read, narrate, restore, bookmark.
- [ ] Text PDF: open, page, extract, narrate, restore progress.
- [ ] Scan PDF: detect scan, run bundled local OCR, create readable text, narrate; clear error on OCR failure.
- [ ] Static Chinese and English articles: URL, extract, snapshot, Library, read, narrate offline.
- [ ] JS article: guarded fallback, snapshot, read/narrate after disconnect.
- [ ] Direct PDF/TXT/DOCX/EPUB URLs route to the correct importer.
- [ ] `.doc` shows explicit unsupported/conversion guidance and is never treated as DOCX/text.
- [ ] From WeChat, share PDF/TXT/DOCX/EPUB with both a precise MIME and `application/octet-stream`; confirm one import, no duplicate after rotation/recreation, and offline reopen.
- [ ] Open a renamed/misleading file through Android VIEW; confirm magic/ZIP structure wins over its filename and provider MIME.
- [ ] Chinese and English paragraphs in one book select ZH/EN routes without splitting iPhone, AI, GPT, decimals, or URLs.
- [ ] Japanese kana and contextual kanji segments show the explicit unavailable-backend message and are not synthesized with a Chinese voice.

## Speed and ordering

- [ ] During continuous playback switch 1.0 → 1.5 → 2.0 → 1.25 → 1.75 → 1.0.
- [ ] Pitch remains close to original.
- [ ] Current audio is not re-synthesized and cache is not cleared.
- [ ] No jump to sentence start, duplicate, skip, or out-of-order segment.
- [ ] At 1x, first audio begins near the 5-second policy target; at 2x it begins no later than the 8-second policy target under a healthy RTF.
- [ ] Seek while a long segment is synthesizing; confirm the old epoch never appears in the cache/player queue.
- [ ] Previous/next, pause/resume, stop, and “from here” work for every supported reader.

## Benchmark and 30-minute run

- [ ] Run the fixed 20-segment offline benchmark; record p50/p90/p95 RTF and load factors.
- [ ] Export NarrationTrace JSONL and verify no document text is present.
- [ ] Warm the required wall buffer, then run at least 30 minutes, including 1x/1.5x/2x.
- [ ] Record crash count, SIGABRT, underruns, duplicates, skips, ordering, restarts, min/average buffer, gaps, main RAM, TTS RAM, thermal and battery notes.
- [ ] Required: crash 0, SIGABRT 0, duplicate 0, skipped 0, out-of-order 0; target underrun 0 after warm buffer.

## Evidence fields

- Device/build fingerprint:
- Test corpus/document hashes:
- p50 / p95 RTF:
- Minimum / average buffer:
- Maximum unplanned gap:
- Crash / SIGABRT / underrun counts:
- Duplicate / skipped / out-of-order counts:
- Main / TTS RSS:
- 30-minute start/end timestamps:
- Trace/logcat attachment:
