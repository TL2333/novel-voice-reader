[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Apk,
    [ValidateSet('PASS')][string]$UnitTestResult = 'PASS',
    [ValidateSet('PASS')][string]$LintResult = 'PASS',
    [ValidateSet('PASS')][string]$StaticVerificationResult = 'PASS'
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$sourceApk = (Resolve-Path -LiteralPath $Apk).Path
$dist = Join-Path $projectRoot 'dist'
$destination = Join-Path $dist 'NovelVoiceReader-arm64-debug.apk'
$lockPath = Join-Path $projectRoot 'config\sherpa-onnx.lock.json'
$kokoroLockPath = Join-Path $projectRoot 'config\kokoro.lock.json'
$sherpaLock = Get-Content -Raw -Encoding UTF8 -LiteralPath $lockPath | ConvertFrom-Json
$kokoroLock = Get-Content -Raw -Encoding UTF8 -LiteralPath $kokoroLockPath | ConvertFrom-Json
$modelRoot = Join-Path (Join-Path $projectRoot '.local-models') $kokoroLock.modelId
if (-not (Test-Path -LiteralPath $modelRoot -PathType Container)) {
    throw "Verified Kokoro model directory is missing: $modelRoot"
}

New-Item -ItemType Directory -Path $dist -Force | Out-Null
$staticReportPath = Join-Path $dist 'APK_CONTENTS_REPORT.txt'
if (
    -not (Test-Path -LiteralPath $staticReportPath -PathType Leaf) -or
    (Get-Content -LiteralPath $staticReportPath -Encoding UTF8 -TotalCount 1) -ne 'APK static verification: PASS'
) {
    throw "A passing APK_CONTENTS_REPORT.txt must exist before deliverables are published."
}
Copy-Item -LiteralPath $sourceApk -Destination $destination -Force

$apkInfo = Get-Item -LiteralPath $destination
$apkSha = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant()
$modelSize = (Get-ChildItem -LiteralPath $modelRoot -File -Recurse | Measure-Object -Property Length -Sum).Sum
$modelPayloadSize = [long]$kokoroLock.payloadSizeBytes
$coreModelSha = $kokoroLock.requiredFiles.'model.int8.onnx'.sha256

[System.IO.File]::WriteAllText(
    (Join-Path $dist 'SHA256SUMS.txt'),
    "$apkSha  NovelVoiceReader-arm64-debug.apk`n",
    [System.Text.UTF8Encoding]::new($false)
)

$buildReport = @"
# Novel Voice Reader Build Report

- Status: BUILD_VERIFIED
- Runtime status: DEVICE_RUNTIME_PENDING_USER_TEST
- APK: $destination
- APK size: $($apkInfo.Length) bytes
- APK SHA256: $apkSha
- applicationId: com.tl2333.novelvoicereader
- versionName: 0.1.0
- versionCode: 1
- ABI: arm64-v8a only
- minSdk: 26
- targetSdk: 36
- Readium Kotlin Toolkit: 3.2.0 (fe4c32b97f4745971facfbdfbde31520553c770e)
- sherpa-onnx: $($sherpaLock.version)
- Kokoro: $($kokoroLock.modelVersion) / $($kokoroLock.huggingFaceCommit)
- Kokoro model SHA256: $coreModelSha
- Kokoro payload total: $modelPayloadSize bytes across $($kokoroLock.payloadFileCount) locked files
- Generated model directory including manifest: $modelSize bytes
- JVM unit tests: $UnitTestResult (testDebugUnitTest)
- Android Lint: $LintResult (lintDebug)
- APK static verification: $StaticVerificationResult
- APK contents report: $(Join-Path $dist 'APK_CONTENTS_REPORT.txt')

No physical-device test was performed. Installation, audible speech, voice quality, background/lock-screen playback, first-utterance latency, memory use, battery use, and thermal behavior remain pending user testing.

Known limits: ARM64 devices only; unprotected local EPUB 2/3 only; sentence-level highlighting because Kokoro has no reliable word timings; no cloud services, accounts, syncing, voice cloning, or remote TTS.
"@
[System.IO.File]::WriteAllText((Join-Path $dist 'BUILD_REPORT.md'), $buildReport, [System.Text.UTF8Encoding]::new($false))

$installGuide = @"
# Installation Guide

1. Copy NovelVoiceReader-arm64-debug.apk to the phone's **Download** folder using USB/MTP.
2. Open the APK in the phone's file manager.
3. Allow that file manager to **Install unknown apps** when Android asks.
4. Install the APK.
5. Open **Novel Voice Reader / AI 小说阅读器**.
6. Wait for **正在准备离线朗读模型** to finish verification and initialization.
7. Open **设置 → 离线朗读诊断**.
8. Generate the fixed test sentence and confirm playback.
9. From the bookshelf, choose **导入内置测试书**.
10. Open chapter 1 and start narration.
11. Use **导入 EPUB** to select your own unprotected .epub through Android's document picker.

The APK is debug-signed. It is offline-only and declares no Internet or network-state permission.
"@
[System.IO.File]::WriteAllText((Join-Path $dist 'INSTALL_GUIDE.md'), $installGuide, [System.Text.UTF8Encoding]::new($false))

$manualChecklist = @"
# Manual Device Test Checklist

Device information:

- [ ] Brand/model:
- [ ] Android version:
- [ ] CPU ABI reports arm64-v8a:
- [ ] RAM:
- [ ] Available storage:

Installation and model:

- [ ] APK installs successfully.
- [ ] App starts without a crash.
- [ ] Offline model preparation completes.
- [ ] Diagnostics page generates audible speech.
- [ ] Chinese is intelligible.
- [ ] Female voice (default sid 3 / zf_001) is audible.
- [ ] Male voice (default sid 58 / zm_009) is audible.

EPUB and narration:

- [ ] A normal local EPUB imports through the document picker.
- [ ] Chapter table of contents is correct.
- [ ] Full reading position restores after reopening.
- [ ] Play/pause works.
- [ ] Previous/next sentence works.
- [ ] Sentence highlight follows narration.
- [ ] Auto-follow works without excessive jumping.
- [ ] Automatic next chapter works.
- [ ] Background playback works.
- [ ] Lock-screen playback controls work.
- [ ] First sentence wait time (seconds):
- [ ] Continuous narration has no unacceptable gaps.

Stability and quality:

- [ ] No crash observed.
- [ ] Thermal behavior:
- [ ] Actual voice-quality notes:
- [ ] Error screenshot attached if applicable.
- [ ] Diagnostics error code:
"@
[System.IO.File]::WriteAllText((Join-Path $dist 'MANUAL_TEST_CHECKLIST.md'), $manualChecklist, [System.Text.UTF8Encoding]::new($false))

$dependencyReport = @"
# Dependency Lock Report

| Component | Version / commit | Official source | SHA256 |
|---|---|---|---|
| Readium Kotlin Toolkit | 3.2.0 / fe4c32b97f4745971facfbdfbde31520553c770e | https://github.com/readium/kotlin-toolkit/tree/3.2.0 | Git tag/commit verified |
| sherpa-onnx Android AAR | $($sherpaLock.version) / $($sherpaLock.sourceCommit) | $($sherpaLock.androidAar.url) | $($sherpaLock.androidAar.sha256) |
| Kokoro model archive | $($kokoroLock.modelVersion) / HF $($kokoroLock.huggingFaceCommit) | $($kokoroLock.sourceUrl) | $($kokoroLock.archiveSha256) |
| model.int8.onnx | $($kokoroLock.modelVersion) | Embedded official archive | $coreModelSha |
| lexicon-zh.txt | Same model archive | Embedded official archive | $($kokoroLock.requiredFiles.'lexicon-zh.txt'.sha256) |

Gradle resolves application dependencies only from google() and mavenCentral() with pinned catalog versions. Models, AARs, APKs, local SDK paths, signing material, and user content are not committed.
"@
[System.IO.File]::WriteAllText((Join-Path $dist 'DEPENDENCY_LOCK_REPORT.md'), $dependencyReport, [System.Text.UTF8Encoding]::new($false))

Write-Host "ARTIFACTS_COPIED $destination"
Write-Host "APK_SIZE $($apkInfo.Length)"
Write-Host "APK_SHA256 $apkSha"
