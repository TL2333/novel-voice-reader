# Device Benchmark Report

- Status: `NOT_RUN_DEVICE_DISCONNECTED`
- ADB command: `C:\devtools\android-sdk\platform-tools\adb.exe devices -l`
- Check times: `2026-08-14T23:12:04+08:00`, `2026-08-14T23:24:13+08:00`
- Result: the device list was empty; serial `I7SGOJXWBMPBZ9AA` was not connected.
- Intended target: Xiaomi 2311DRK48G, Android 15 / SDK 35, arm64-v8a, approximately 11.6 GiB RAM.
- Profile expectation: `HIGH_MEMORY`, but runtime classification was not observed and is not claimed.

| Metric | Result |
|---|---|
| 1.0x | NOT_RUN |
| 1.5x | NOT_RUN |
| 2.0x | NOT_RUN |
| p50 RTF | NOT_RUN |
| p90 RTF | NOT_RUN |
| p95 RTF | NOT_RUN |
| Minimum wall buffer | NOT_RUN |
| Average wall buffer | NOT_RUN |
| Unplanned gap | NOT_RUN |
| Buffer underrun | NOT_RUN |
| Duplicate / skipped / out-of-order segment | NOT_RUN |
| Main / TTS process RAM | NOT_RUN |
| TTS restart | NOT_RUN |
| Crash / SIGABRT | NOT_RUN |
| 30-minute continuous narration | NOT_RUN |

The app includes a 20-segment offline benchmark and privacy-safe trace export so these values can be measured on the target without rebuilding. Historical callback-crash smoke results in `CRASH_FIX_REPORT.md` are not counted as Narration V2 evidence.
