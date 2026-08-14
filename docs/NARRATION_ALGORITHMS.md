# Narration Algorithms

## Segmentation and pauses

Clean control/markup noise, normalize Chinese numbers and punctuation, then segment at paragraph and `。！？；……——` boundaries before commas. Target 50-120 Chinese characters, soft maximum 120, hard maximum 160. Never split inside a number, date, URL, or Latin token. Merge semantically short neighbors when limits allow.

Default planned pauses are centralized in `NarrationProfile`: semicolon 130 ms, period 200 ms, question 220 ms, exclamation 190 ms, paragraph 320 ms, heading 550 ms. Planned pause is excluded from underrun measurement; extra silence is `unplannedGapMs`.

## RTF and buffer

`RTF = generationMs / audioDurationMs`. Maintain bounded recent samples and nearest-rank p50/p90/p95. `loadFactor = p95RTF * playbackSpeed`.

- GOOD: `< 0.60`; SAFE: `< 0.75`; both use 30/90/180 seconds low/target/high wall time.
- AT_RISK: `< 1.00`; use 45/150/300 seconds and prefetch the rest of the chapter plus the next chapter start.
- UNSUSTAINABLE_REALTIME: `>= 1.00`; use aggressive pregeneration and never silently reduce speed.

`bufferWallMs = remainingGeneratedMediaDurationMs / playbackSpeed`. Desired media duration is wall duration multiplied by speed. Initial warm thresholds: 15 seconds through 1.25x, 25 seconds at 1.5x, and 45 seconds at 1.75x or 2x.

## Chunks and cache

Assemble 3-8 adjacent segments, normally 12-24 seconds of source media. Append only planned silence and record each segment's media interval and anchor. Playback speed never changes a chunk.

Cache key: SHA-256 of model version, voice ID, normalized text, synthesis profile, and normalizer version. Playback speed is excluded. Cache capacity is 4 GiB at >=32 GiB free, 2 GiB at >=16 GiB, 1 GiB at >=8 GiB, otherwise at most 512 MiB and at most 5% of free space while reserving 4 GiB. LRU never removes protected in-use files.

Temporary PCM is converted and written immediately. HIGH_MEMORY hard budget is 96 MiB and normal target is below 64 MiB.
