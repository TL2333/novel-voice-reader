[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot

function Resolve-Java17 {
    $java = Get-Command java.exe -ErrorAction SilentlyContinue
    if (-not $java) {
        throw 'JDK 17 is required, but java.exe was not found.'
    }
    # java -version writes its successful version banner to stderr. Capture it
    # through Process so Windows PowerShell cannot promote that stream to a
    # terminating ErrorRecord under the script-wide Stop preference.
    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $java.Source
    $startInfo.Arguments = '-version'
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process = [System.Diagnostics.Process]::Start($startInfo)
    $standardOutput = $process.StandardOutput.ReadToEnd()
    $standardError = $process.StandardError.ReadToEnd()
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) {
        throw "java -version failed with exit code $($process.ExitCode)."
    }
    $version = (($standardError, $standardOutput) -join "`n" -split "`r?`n" |
        Where-Object { $_ } | Select-Object -First 1) -join ''
    if ($version -notmatch 'version "17[\.]') {
        throw "JDK 17 is required. Detected: $version"
    }
    Write-Host "JDK_OK $version"
}

function Resolve-AndroidSdk {
    $candidates = @(
        $env:ANDROID_SDK_ROOT,
        $env:ANDROID_HOME,
        (Join-Path $env:LOCALAPPDATA 'Android\Sdk'),
        'C:\devtools\android-sdk'
    ) | Where-Object { $_ } | Select-Object -Unique

    foreach ($candidate in $candidates) {
        $resolved = Resolve-Path -LiteralPath $candidate -ErrorAction SilentlyContinue
        if ($resolved -and (Test-Path -LiteralPath (Join-Path $resolved 'cmdline-tools'))) {
            return $resolved.Path
        }
    }
    throw 'Android SDK was not found in ANDROID_SDK_ROOT, ANDROID_HOME, LocalAppData, or C:\devtools\android-sdk.'
}

function Resolve-SdkTool([string]$sdkRoot, [string]$name) {
    $match = Get-ChildItem -LiteralPath $sdkRoot -Filter $name -File -Recurse -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending |
        Select-Object -First 1
    if (-not $match) {
        throw "Android SDK tool missing: $name"
    }
    return $match.FullName
}

function Resolve-Python {
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
    throw 'Python 3 is required by the deterministic asset and APK verification scripts.'
}

Resolve-Java17
$python = Resolve-Python
$env:NVR_PYTHON = $python
$env:PATH = "$(Split-Path -Parent $python);$env:PATH"
$sdkRoot = Resolve-AndroidSdk
$env:NVR_ANDROID_SDK_ROOT = $sdkRoot
$sdkManager = Resolve-SdkTool $sdkRoot 'sdkmanager.bat'

$answers = 1..100 | ForEach-Object { 'y' }
$answers | & $sdkManager --sdk_root=$sdkRoot --licenses | Out-Host
if ($LASTEXITCODE -ne 0) {
    throw "Android SDK license acceptance failed with exit code $LASTEXITCODE."
}

& $sdkManager --sdk_root=$sdkRoot 'platforms;android-36' 'build-tools;36.0.0' 'platform-tools' | Out-Host
if ($LASTEXITCODE -ne 0) {
    throw "Android SDK component installation failed with exit code $LASTEXITCODE."
}

$requiredTools = @(
    (Join-Path $sdkRoot 'platform-tools\adb.exe'),
    (Join-Path $sdkRoot 'build-tools\36.0.0\aapt2.exe'),
    (Join-Path $sdkRoot 'build-tools\36.0.0\apksigner.bat'),
    (Join-Path $sdkRoot 'build-tools\36.0.0\zipalign.exe')
)
foreach ($tool in $requiredTools) {
    if (-not (Test-Path -LiteralPath $tool)) {
        throw "Required Android SDK tool was not installed: $tool"
    }
}

$sdkProperty = $sdkRoot.Replace('\', '/').Replace(':', '\:')
$localProperties = Join-Path $projectRoot 'local.properties'
[System.IO.File]::WriteAllText($localProperties, "sdk.dir=$sdkProperty`n", [System.Text.UTF8Encoding]::new($false))

Write-Host "ANDROID_SDK_OK $sdkRoot"
Write-Host "PYTHON_OK $python"
Write-Host "SDK_PLATFORM_OK android-36"
Write-Host "SDK_BUILD_TOOLS_OK 36.0.0"
