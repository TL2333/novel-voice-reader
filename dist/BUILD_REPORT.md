# Novel Voice Reader Build Report

- Status: BUILD_VERIFIED
- Runtime status: DEVICE_RUNTIME_PENDING (ADB available, no connected device)
- APK: C:\code\novel-voice-reader\dist\NovelVoiceReader-arm64-debug-import-language-v3.apk
- APK size: 263687255 bytes
- APK SHA256: 9b74ffd7dee8d0cefb35ae495c635f7aff92b907b43f0a8201b7b29927472c68
- applicationId: com.tl2333.novelvoicereader
- versionName: 0.1.0
- versionCode: 1
- ABI: arm64-v8a only
- minSdk: 26
- targetSdk: 36
- Readium Kotlin Toolkit: 3.2.0 (fe4c32b97f4745971facfbdfbde31520553c770e)
- sherpa-onnx: 1.13.4
- Kokoro: Kokoro-82M-v1.1-zh INT8 / 155831f1b4ba23b1f5c058be6a61df90cefb2a37
- Kokoro model SHA256: bda15858163726a492d02a9a727bc263551b86ac77f90812c4b30ff41d380e26
- Kokoro payload total: 215321602 bytes across 377 locked files
- Generated model directory including manifest: 215384118 bytes
- JVM unit tests: PASS (77 tests, 0 failures/errors/skips)
- Android Lint: PASS (0 errors; 48 non-blocking warnings)
- APK static verification: PASS
- APK contents report: C:\code\novel-voice-reader\dist\APK_CONTENTS_REPORT.txt

No physical-device test was performed. Installation, audible speech, voice quality, background/lock-screen playback, first-utterance latency, memory use, battery use, and thermal behavior remain pending user testing.

Known limits: ARM64 devices only; unprotected local EPUB 2/3 only; sentence-level highlighting because Kokoro has no reliable word timings; no cloud services, accounts, syncing, voice cloning, or remote TTS.
