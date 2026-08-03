[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$vendorRoot = Join-Path $projectRoot '.codex\vendor'

function Resolve-Git {
    $command = Get-Command git.exe -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    $portable = 'C:\code\.tools\portablegit\cmd\git.exe'
    if (Test-Path -LiteralPath $portable) { return $portable }
    throw 'git.exe is required to prepare the official source reference area.'
}

function Assert-VendorTarget([string]$path) {
    $absoluteRoot = [System.IO.Path]::GetFullPath($vendorRoot).TrimEnd('\') + '\'
    $absolutePath = [System.IO.Path]::GetFullPath($path)
    if (-not $absolutePath.StartsWith($absoluteRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to modify a path outside the vendor reference area: $absolutePath"
    }
}

function Prepare-Reference(
    [string]$name,
    [string]$url,
    [string]$tag,
    [string]$expectedCommit,
    [string]$destination
) {
    Assert-VendorTarget $destination
    $isCorrect = $false
    if (Test-Path -LiteralPath (Join-Path $destination '.git')) {
        $actual = (& $script:git -C $destination rev-parse HEAD).Trim()
        $isCorrect = $LASTEXITCODE -eq 0 -and $actual -eq $expectedCommit
    }
    if (-not $isCorrect) {
        if (Test-Path -LiteralPath $destination) {
            Remove-Item -LiteralPath $destination -Recurse -Force
        }
        & $script:git clone --depth 1 --branch $tag $url $destination | Out-Host
        if ($LASTEXITCODE -ne 0) { throw "Failed to clone $name tag $tag." }
    }
    $actual = (& $script:git -C $destination rev-parse HEAD).Trim()
    if ($actual -ne $expectedCommit) {
        throw "$name commit mismatch: $actual; expected $expectedCommit"
    }
    Write-Host "OFFICIAL_SOURCE_OK $name $tag $actual"
}

New-Item -ItemType Directory -Path $vendorRoot -Force | Out-Null
$script:git = Resolve-Git
Prepare-Reference `
    'Readium Kotlin Toolkit' `
    'https://github.com/readium/kotlin-toolkit.git' `
    '3.2.0' `
    'fe4c32b97f4745971facfbdfbde31520553c770e' `
    (Join-Path $vendorRoot 'readium-kotlin-3.2.0')
Prepare-Reference `
    'sherpa-onnx' `
    'https://github.com/k2-fsa/sherpa-onnx.git' `
    'v1.13.4' `
    '142807252687d81b40d6315f23470a1512a00de3' `
    (Join-Path $vendorRoot 'sherpa-onnx')
