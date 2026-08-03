[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot

function Invoke-Step([string]$name, [scriptblock]$action) {
    Write-Host "`n=== $name ==="
    $global:LASTEXITCODE = 0
    try {
        & $action
        $stepSucceeded = $?
        $stepExitCode = $global:LASTEXITCODE
    } catch {
        throw "$name failed: $($_.Exception.Message)"
    }
    if (-not $stepSucceeded -or $stepExitCode -ne 0) {
        throw "$name failed with exit code $stepExitCode."
    }
}

function Get-SdkRootFromLocalProperties([string]$path) {
    $line = Get-Content -LiteralPath $path -Encoding UTF8 |
        Where-Object { $_ -match '^\s*sdk[.]dir\s*=' } |
        Select-Object -First 1
    if (-not $line) { throw "sdk.dir is missing from $path" }
    $encoded = ($line -split '=', 2)[1].Trim()
    # bootstrap-windows writes a Java-properties escaped drive separator (for example C\:/Sdk).
    $decoded = $encoded.Replace('\:', ':').Replace('\\', '\')
    return [System.IO.Path]::GetFullPath($decoded)
}

$dist = Join-Path $projectRoot 'dist'
$staleOutputs = @(
    'NovelVoiceReader-arm64-debug.apk',
    'SHA256SUMS.txt',
    'BUILD_REPORT.md',
    'INSTALL_GUIDE.md',
    'MANUAL_TEST_CHECKLIST.md',
    'DEPENDENCY_LOCK_REPORT.md',
    'APK_CONTENTS_REPORT.txt'
)
foreach ($name in $staleOutputs) {
    $path = Join-Path $dist $name
    if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path -Force }
}

Invoke-Step 'Gate 0 - Windows environment' { & "$PSScriptRoot\bootstrap-windows.ps1" }
$python = $env:NVR_PYTHON
if (-not $python -or -not (Test-Path -LiteralPath $python)) {
    throw 'bootstrap-windows.ps1 did not propagate a usable NVR_PYTHON interpreter.'
}

Invoke-Step 'Official reference sources' { & "$PSScriptRoot\prepare-official-sources.ps1" }
Invoke-Step 'Kokoro model' { & "$PSScriptRoot\prepare-kokoro-model.ps1" }
Invoke-Step 'sherpa-onnx Android AAR' { & "$PSScriptRoot\prepare-sherpa-android.ps1" }
Invoke-Step 'Deterministic test EPUB' { & $python "$PSScriptRoot\create-test-epub.py" }
Invoke-Step 'Offline asset verification' { & $python "$PSScriptRoot\verify-offline-assets.py" }

Invoke-Step 'Gradle clean' { & '.\gradlew.bat' --no-daemon clean }
Invoke-Step 'Restore generated offline assets after clean' { & '.\gradlew.bat' --no-daemon :app:syncOfflineAssets }
Invoke-Step 'Post-clean offline asset verification' { & $python "$PSScriptRoot\verify-offline-assets.py" }
Invoke-Step 'JVM unit tests' { & '.\gradlew.bat' --no-daemon testDebugUnitTest }
Invoke-Step 'Android lint' { & '.\gradlew.bat' --no-daemon lintDebug }
Invoke-Step 'Debug APK build' { & '.\gradlew.bat' --no-daemon assembleDebug }

$apk = Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk'
$sdkRoot = $env:NVR_ANDROID_SDK_ROOT
if (-not $sdkRoot) {
    $sdkRoot = Get-SdkRootFromLocalProperties (Join-Path $projectRoot 'local.properties')
}
if (-not (Test-Path -LiteralPath $sdkRoot -PathType Container)) {
    throw "Resolved Android SDK root does not exist: $sdkRoot"
}

Invoke-Step 'APK static verification' {
    & $python "$PSScriptRoot\verify-apk.py" --apk $apk --sdk-root $sdkRoot --report (Join-Path $projectRoot 'dist\APK_CONTENTS_REPORT.txt')
}
Invoke-Step 'Deliverable copy and reports' {
    & "$PSScriptRoot\copy-artifacts.ps1" -Apk $apk -UnitTestResult PASS -LintResult PASS -StaticVerificationResult PASS
}

Write-Host "`nBUILD_VERIFIED"
Write-Host 'DEVICE_RUNTIME_PENDING_USER_TEST'
