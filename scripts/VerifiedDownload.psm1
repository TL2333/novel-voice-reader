Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-LowerSha256 {
    param([Parameter(Mandatory)][string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Assert-VerifiedFile {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][long]$ExpectedSize,
        [Parameter(Mandatory)][string]$ExpectedSha256
    )
    $item = Get-Item -LiteralPath $Path -ErrorAction Stop
    if ($item.Length -ne $ExpectedSize) {
        throw "Unexpected file size for $Path. Expected $ExpectedSize, got $($item.Length)."
    }
    $actualSha256 = Get-LowerSha256 -Path $Path
    if ($actualSha256 -ne $ExpectedSha256.ToLowerInvariant()) {
        throw "SHA-256 mismatch for $Path. Expected $ExpectedSha256, got $actualSha256."
    }
}

function Invoke-VerifiedDownload {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][uri]$Url,
        [Parameter(Mandatory)][string]$Destination,
        [Parameter(Mandatory)][long]$ExpectedSize,
        [Parameter(Mandatory)][string]$ExpectedSha256,
        [ValidateRange(1MB, 64MB)][long]$ChunkSize = 8MB,
        [ValidateRange(1, 12)][int]$Parallelism = 4,
        [switch]$Force
    )

    if ($ExpectedSize -le 0) { throw 'ExpectedSize must be positive.' }
    if ($ExpectedSha256 -notmatch '^[0-9a-fA-F]{64}$') { throw 'ExpectedSha256 must contain 64 hexadecimal characters.' }

    $destinationFull = [System.IO.Path]::GetFullPath($Destination)
    $destinationParent = Split-Path -Parent $destinationFull
    if ([string]::IsNullOrWhiteSpace($destinationParent)) { throw "Destination needs a parent directory: $Destination" }
    New-Item -ItemType Directory -Force -Path $destinationParent | Out-Null

    if (Test-Path -LiteralPath $destinationFull) {
        try {
            Assert-VerifiedFile -Path $destinationFull -ExpectedSize $ExpectedSize -ExpectedSha256 $ExpectedSha256
            Write-Host "DOWNLOAD_CACHE_OK $destinationFull"
            return $destinationFull
        } catch {
            if (-not $Force) { throw }
            Remove-Item -LiteralPath $destinationFull -Force
        }
    }

    $curl = (Get-Command curl.exe -ErrorAction Stop).Source
    $partsDirectory = "$destinationFull.parts"
    New-Item -ItemType Directory -Force -Path $partsDirectory | Out-Null

    $parts = @()
    $index = 0
    for ($start = 0L; $start -lt $ExpectedSize; $start += $ChunkSize) {
        $end = [Math]::Min($ExpectedSize - 1L, $start + $ChunkSize - 1L)
        $path = Join-Path $partsDirectory ('{0:D6}.part' -f $index)
        $parts += [pscustomobject]@{
            Index = $index
            Start = $start
            End = $end
            ExpectedLength = $end - $start + 1L
            Path = $path
        }
        $index++
    }

    $pending = [System.Collections.Generic.Queue[object]]::new()
    foreach ($part in $parts) {
        if (Test-Path -LiteralPath $part.Path) {
            if ((Get-Item -LiteralPath $part.Path).Length -eq $part.ExpectedLength) {
                Write-Host "DOWNLOAD_PART_CACHE_OK $($part.Index) $($part.Path)"
                continue
            }
            Remove-Item -LiteralPath $part.Path -Force
        }
        $pending.Enqueue($part)
    }

    $jobs = @{}
    try {
        while ($pending.Count -gt 0 -or $jobs.Count -gt 0) {
            while ($pending.Count -gt 0 -and $jobs.Count -lt $Parallelism) {
                $part = $pending.Dequeue()
                $job = Start-Job -ScriptBlock {
                    param($Curl, $SourceUrl, $Start, $End, $OutputPath, $ExpectedLength)
                    $ErrorActionPreference = 'Stop'
                    & $Curl --location --fail --silent --show-error --retry 5 --retry-all-errors `
                        --connect-timeout 30 --range "$Start-$End" --output $OutputPath $SourceUrl
                    if ($LASTEXITCODE -ne 0) { throw "curl failed with exit code $LASTEXITCODE for range $Start-$End" }
                    $actualLength = (Get-Item -LiteralPath $OutputPath -ErrorAction Stop).Length
                    if ($actualLength -ne $ExpectedLength) {
                        throw "Range $Start-$End returned $actualLength bytes; expected $ExpectedLength."
                    }
                    return $OutputPath
                } -ArgumentList $curl, $Url.AbsoluteUri, $part.Start, $part.End, $part.Path, $part.ExpectedLength
                $jobs[$job.Id] = $job
            }

            $completed = Wait-Job -Job @($jobs.Values) -Any
            try {
                Receive-Job -Job $completed -ErrorAction Stop | ForEach-Object {
                    Write-Host "DOWNLOAD_PART_OK $_"
                }
            } catch {
                foreach ($running in $jobs.Values) { Stop-Job -Job $running -ErrorAction SilentlyContinue }
                throw
            } finally {
                Remove-Job -Job $completed -Force -ErrorAction SilentlyContinue
                $jobs.Remove($completed.Id)
            }
        }
    } finally {
        foreach ($job in $jobs.Values) {
            Stop-Job -Job $job -ErrorAction SilentlyContinue
            Remove-Job -Job $job -Force -ErrorAction SilentlyContinue
        }
    }

    foreach ($part in $parts) {
        $actualLength = (Get-Item -LiteralPath $part.Path -ErrorAction Stop).Length
        if ($actualLength -ne $part.ExpectedLength) {
            throw "Part $($part.Index) has $actualLength bytes; expected $($part.ExpectedLength)."
        }
    }

    $assembling = "$destinationFull.assembling.$([Guid]::NewGuid().ToString('N'))"
    try {
        $output = [System.IO.File]::Open($assembling, [System.IO.FileMode]::CreateNew, [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
        try {
            foreach ($part in $parts | Sort-Object Index) {
                $input = [System.IO.File]::OpenRead($part.Path)
                try { $input.CopyTo($output, 1MB) } finally { $input.Dispose() }
            }
            $output.Flush($true)
        } finally {
            $output.Dispose()
        }

        Assert-VerifiedFile -Path $assembling -ExpectedSize $ExpectedSize -ExpectedSha256 $ExpectedSha256
        if (Test-Path -LiteralPath $destinationFull) { Remove-Item -LiteralPath $destinationFull -Force }
        Move-Item -LiteralPath $assembling -Destination $destinationFull
        Assert-VerifiedFile -Path $destinationFull -ExpectedSize $ExpectedSize -ExpectedSha256 $ExpectedSha256

        foreach ($part in $parts) { Remove-Item -LiteralPath $part.Path -Force }
        Remove-Item -LiteralPath $partsDirectory -Force
        Write-Host "DOWNLOAD_VERIFIED $destinationFull"
        return $destinationFull
    } finally {
        if (Test-Path -LiteralPath $assembling) { Remove-Item -LiteralPath $assembling -Force }
    }
}

Export-ModuleMember -Function Assert-VerifiedFile, Invoke-VerifiedDownload
