# Novel Voice Reader Build Report

- Status: BUILD_VERIFIED
- Runtime status: DEVICE_RUNTIME_PENDING_USER_TEST
- APK: C:\code\novel-voice-reader\dist\NovelVoiceReader-arm64-debug.apk
- APK size: 249045495 bytes
- APK SHA256: 86438ecd4c32070ac80c00c9827653e40dd3a4bda3885d8cadb7f138df364444
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
- JVM unit tests: PASS (testDebugUnitTest)
- Android Lint: PASS (lintDebug)
- APK static verification: PASS
- APK contents report: C:\code\novel-voice-reader\dist\APK_CONTENTS_REPORT.txt

No physical-device test was performed. Installation, audible speech, voice quality, background/lock-screen playback, first-utterance latency, memory use, battery use, and thermal behavior remain pending user testing.

Known limits: ARM64 devices only; unprotected local EPUB 2/3 only; sentence-level highlighting because Kokoro has no reliable word timings; no cloud services, accounts, syncing, voice cloning, or remote TTS.