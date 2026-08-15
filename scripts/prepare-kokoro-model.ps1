[CmdletBinding()]
param(
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$lockPath = Join-Path $projectRoot 'config\kokoro.lock.json'
$downloadRoot = Join-Path $projectRoot '.local-downloads'
$modelRoot = Join-Path $projectRoot '.local-models'
$generatedAssetsRoot = Join-Path $projectRoot 'app\build\generated\offlineAssets'
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

function Assert-StrictChildPath([string]$Candidate, [string]$Parent) {
    $candidateFull = [System.IO.Path]::GetFullPath($Candidate).TrimEnd('\')
    $parentFull = [System.IO.Path]::GetFullPath($Parent).TrimEnd('\')
    if ($candidateFull -eq $parentFull -or -not $candidateFull.StartsWith("$parentFull\", [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing filesystem operation outside expected root: $candidateFull"
    }
}

function Resolve-Python3 {
    $candidates = @(
        $env:NVR_PYTHON,
        (Get-Command python.exe -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source -ErrorAction SilentlyContinue),
        (Get-Command python3.exe -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source -ErrorAction SilentlyContinue),
        'C:\Program Files\Unity\Hub\Editor\6000.3.4f1\Editor\Data\PlaybackEngines\WebGLSupport\BuildTools\Emscripten\python\python.exe'
    ) | Where-Object { $_ -and (Test-Path -LiteralPath $_) } | Select-Object -Unique

    foreach ($candidate in $candidates) {
        $previousErrorActionPreference = $ErrorActionPreference
        try {
            $ErrorActionPreference = 'SilentlyContinue'
            & $candidate --version *> $null
            $candidateExitCode = $LASTEXITCODE
        } catch {
            $candidateExitCode = 1
        } finally {
            $ErrorActionPreference = $previousErrorActionPreference
        }
        if ($candidateExitCode -eq 0) {
            return $candidate
        }
    }
    throw 'Python 3 is required to safely extract and verify the Kokoro model.'
}

function Invoke-CheckedPython([string]$Python, [string[]]$Arguments) {
    & $Python @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Python command failed with exit code ${LASTEXITCODE}: $($Arguments -join ' ')"
    }
}

if (-not (Test-Path -LiteralPath $lockPath)) {
    throw "Missing Kokoro lock file: $lockPath"
}
$lock = Get-Content -Raw -Encoding UTF8 -LiteralPath $lockPath | ConvertFrom-Json

New-Item -ItemType Directory -Force -Path $downloadRoot, $modelRoot, $generatedAssetsRoot | Out-Null
$archivePath = Join-Path $downloadRoot "$($lock.modelId).tar.bz2"

Invoke-VerifiedDownload `
    -Url $lock.sourceUrl `
    -Destination $archivePath `
    -ExpectedSize ([long]$lock.archiveSizeBytes) `
    -ExpectedSha256 $lock.archiveSha256 `
    -Force:$Force | Out-Null

Assert-FileMatchesLock $archivePath ([long]$lock.archiveSizeBytes) $lock.archiveSha256
$python = Resolve-Python3
$modelDirectory = Join-Path $modelRoot $lock.modelId
Assert-StrictChildPath $modelDirectory $modelRoot
$modelVerified = $false

if ($Force -and (Test-Path -LiteralPath $modelDirectory)) {
    Remove-Item -LiteralPath $modelDirectory -Recurse -Force
}
if (-not $Force -and (Test-Path -LiteralPath $modelDirectory)) {
    try {
        Invoke-CheckedPython $python @(
            (Join-Path $PSScriptRoot 'verify-models.py'),
            '--model-dir', $modelDirectory,
            '--lock', $lockPath
        )
        $modelVerified = $true
        Write-Host "KOKORO_MODEL_CACHE_OK $modelDirectory"
    } catch {
        Write-Warning "Cached Kokoro payload failed verification and will be restored from the verified archive: $($_.Exception.Message)"
        Remove-Item -LiteralPath $modelDirectory -Recurse -Force
    }
}
if (-not (Test-Path -LiteralPath $modelDirectory)) {
    Invoke-CheckedPython $python @(
        (Join-Path $PSScriptRoot 'extract-kokoro-model.py'),
        '--archive', $archivePath,
        '--output', $modelDirectory,
        '--expected-root', $lock.modelId
    )
}

if (-not $modelVerified) {
    Invoke-CheckedPython $python @(
        (Join-Path $PSScriptRoot 'verify-models.py'),
        '--model-dir', $modelDirectory,
        '--lock', $lockPath
    )
}
Invoke-CheckedPython $python @(
    (Join-Path $PSScriptRoot 'generate-model-manifest.py'),
    '--model-dir', $modelDirectory,
    '--lock', $lockPath,
    '--output', (Join-Path $modelDirectory 'models-manifest.json')
)

$assetDirectory = Join-Path $generatedAssetsRoot $lock.assetDirectory
Assert-StrictChildPath $assetDirectory $generatedAssetsRoot
if (Test-Path -LiteralPath $assetDirectory) {
    Remove-Item -LiteralPath $assetDirectory -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $assetDirectory | Out-Null
Get-ChildItem -LiteralPath $modelDirectory -Force | Copy-Item -Destination $assetDirectory -Recurse -Force

Invoke-CheckedPython $python @(
    (Join-Path $PSScriptRoot 'verify-models.py'),
    '--model-dir', $assetDirectory,
    '--lock', $lockPath
)

Write-Host "KOKORO_ARCHIVE_VERIFIED $archivePath"
Write-Host "KOKORO_MODEL_VERIFIED $modelDirectory"
Write-Host "KOKORO_ASSETS_READY $assetDirectory"
