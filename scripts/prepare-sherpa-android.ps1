[CmdletBinding()]
param(
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$lockPath = Join-Path $projectRoot 'config\sherpa-onnx.lock.json'
$downloadRoot = Join-Path $projectRoot '.local-downloads'
$libraryRoot = Join-Path $projectRoot 'app\libs'
Import-Module (Join-Path $PSScriptRoot 'VerifiedDownload.psm1') -Force

function Get-LowerSha256([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Assert-FileMatchesLock(
    [string]$Path,
    [long]$ExpectedSize,
    [string]$ExpectedSha256
) {
    $item = Get-Item -LiteralPath $Path -ErrorAction Stop
    if ($item.Length -ne $ExpectedSize) {
        throw "Unexpected file size for $Path. Expected $ExpectedSize, got $($item.Length)."
    }
    $actualSha256 = Get-LowerSha256 $Path
    if ($actualSha256 -ne $ExpectedSha256.ToLowerInvariant()) {
        throw "SHA-256 mismatch for $Path. Expected $ExpectedSha256, got $actualSha256."
    }
}

if (-not (Test-Path -LiteralPath $lockPath)) {
    throw "Missing sherpa-onnx lock file: $lockPath"
}
$lock = Get-Content -Raw -Encoding UTF8 -LiteralPath $lockPath | ConvertFrom-Json
$asset = $lock.androidAar

New-Item -ItemType Directory -Force -Path $downloadRoot, $libraryRoot | Out-Null
$downloadPath = Join-Path $downloadRoot $asset.name
Invoke-VerifiedDownload `
    -Url $asset.url `
    -Destination $downloadPath `
    -ExpectedSize ([long]$asset.sizeBytes) `
    -ExpectedSha256 $asset.sha256 `
    -Force:$Force | Out-Null

Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead($downloadPath)
try {
    $entryNames = @($archive.Entries | ForEach-Object { $_.FullName })
    $requiredEntries = @(
        'jni/arm64-v8a/libonnxruntime.so',
        'jni/arm64-v8a/libsherpa-onnx-c-api.so',
        'jni/arm64-v8a/libsherpa-onnx-cxx-api.so',
        'jni/arm64-v8a/libsherpa-onnx-jni.so'
    )
    foreach ($entry in $requiredEntries) {
        if ($entryNames -notcontains $entry) {
            throw "Official AAR is missing required ARM64 entry: $entry"
        }
    }
} finally {
    $archive.Dispose()
}

$libraryPath = Join-Path $libraryRoot $asset.name
Copy-Item -LiteralPath $downloadPath -Destination $libraryPath -Force
Assert-FileMatchesLock $libraryPath ([long]$asset.sizeBytes) $asset.sha256

$versionPath = Join-Path $libraryRoot 'sherpa-onnx.version.json'
$versionRecord = [ordered]@{
    schemaVersion = 1
    version = $lock.version
    tag = $lock.tag
    sourceCommit = $lock.sourceCommit
    releaseUrl = $lock.releaseUrl
    assetName = $asset.name
    assetUrl = $asset.url
    sizeBytes = [long]$asset.sizeBytes
    sha256 = $asset.sha256
    checksumSource = $asset.checksumSource
    packagedAbi = $lock.packagedAbi
}
$versionJson = $versionRecord | ConvertTo-Json -Depth 5
[System.IO.File]::WriteAllText(
    $versionPath,
    "$versionJson`n",
    [System.Text.UTF8Encoding]::new($false)
)

Write-Host "SHERPA_AAR_VERIFIED $libraryPath"
Write-Host "SHERPA_AAR_SHA256 $($asset.sha256)"
Write-Host 'SHERPA_AAR_ARM64_OK'
