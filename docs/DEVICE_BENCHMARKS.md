# Device Benchmarks

## Target device

Xiaomi 2311DRK48G, Android 15 / SDK 35, arm64-v8a, approximately 11.6 GiB RAM. Classification must be runtime-derived from `ActivityManager.MemoryInfo`, `memoryClass`, `largeMemoryClass`, total RAM, and available RAM; the model name is informational only.

## Narration V2 run

Status: `NOT_RUN_DEVICE_DISCONNECTED` on 2026-08-14. Checks at 23:12:04 and 23:24:13 +08:00 both returned an empty device list after the final APK was built.

Build-side gates completed: 64/64 JVM unit tests passed, `lintDebug` passed, `assembleDebug` passed, and static APK verification passed. These results do not substitute for physical-device RTF, audio, memory, or stability evidence.

Required measurements when connected: p50/p90/p95 RTF; 1x/1.5x/2x load factor; minimum/average wall buffer; unplanned gaps; buffer underruns; duplicate/skipped/out-of-order segments; main and TTS process RAM; native aborts; TTS restarts; crash count; and a 30-minute continuous result.

Earlier `dist/CRASH_FIX_REPORT.md` documents an Android 15 callback-crash smoke test. It is historical evidence, not Narration V2 performance evidence.
