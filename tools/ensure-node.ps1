param([string]$ProjectRoot = (Split-Path $PSScriptRoot -Parent))

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$runtime = Join-Path $ProjectRoot '.runtime'
New-Item -ItemType Directory -Force -Path $runtime | Out-Null

function Test-Node([string]$NodeHome) {
    $node = Join-Path $NodeHome 'node.exe'
    $npm = Join-Path $NodeHome 'npm.cmd'
    if (!(Test-Path -LiteralPath $node) -or !(Test-Path -LiteralPath $npm)) { return $false }
    $savedPath = $env:PATH
    try {
        $env:PATH = "$NodeHome;$savedPath"
        $version = (& $node --version | Out-String).Trim()
        if ($LASTEXITCODE -ne 0 -or $version -notmatch '^v(\d+)\.') { return $false }
        if ([int]$Matches[1] -lt 24) { return $false }
        $npmVersion = (& $npm --version | Out-String).Trim()
        return ($LASTEXITCODE -eq 0 -and $npmVersion -match '^\d+\.\d+\.\d+')
    } catch { return $false }
    finally { $env:PATH = $savedPath }
}

try {
    $candidates = @()
    $nodeCommand = Get-Command node.exe -CommandType Application -ErrorAction SilentlyContinue
    if ($nodeCommand) { $candidates += Split-Path $nodeCommand.Source -Parent }
    Get-ChildItem -LiteralPath $runtime -Directory -Filter 'node-*-win-x64' | ForEach-Object { $candidates += $_.FullName }
    $nodeHome = $null
    foreach ($candidate in $candidates) {
        if (Test-Node $candidate) { $nodeHome = (Resolve-Path -LiteralPath $candidate).Path; break }
    }
    if (!$nodeHome) {
        if (![Environment]::Is64BitOperatingSystem) { throw 'Automatic installation requires 64-bit Windows.' }
        Write-Host 'Downloading Node.js 24 with npm (first run only)...'
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        $baseUrl = 'https://nodejs.org/dist/latest-v24.x'
        $manifest = (Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/SHASUMS256.txt" -TimeoutSec 60).Content
        $entry = [regex]::Match($manifest, '(?m)^([a-fA-F0-9]{64})\s+(node-v24\.\d+\.\d+-win-x64\.zip)\s*$')
        if (!$entry.Success) { throw 'Node.js download metadata is incomplete.' }
        $archiveName = $entry.Groups[2].Value
        $staging = Join-Path $runtime ('node-download-' + [guid]::NewGuid().ToString('N'))
        New-Item -ItemType Directory -Path $staging | Out-Null
        $archive = Join-Path $staging $archiveName
        Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/$archiveName" -OutFile $archive -TimeoutSec 180
        if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $entry.Groups[1].Value) {
            throw 'Node.js download checksum mismatch. Please retry.'
        }
        Expand-Archive -LiteralPath $archive -DestinationPath $staging
        $extracted = Join-Path $staging ([IO.Path]::GetFileNameWithoutExtension($archiveName))
        if (!(Test-Node $extracted)) { throw 'Downloaded Node.js or npm cannot run on this computer.' }
        # A unique name avoids replacing a running Node process or a partial older installation.
        $nodeHome = Join-Path $runtime ('node-v24-' + [guid]::NewGuid().ToString('N') + '-win-x64')
        Move-Item -LiteralPath $extracted -Destination $nodeHome
    }
    $settings = @('@echo off', ('set "PATH={0};%PATH%"' -f $nodeHome))
    [IO.File]::WriteAllLines((Join-Path $runtime 'node-env.cmd'), $settings, [Text.Encoding]::Default)
    Write-Host 'Node.js and npm are ready.'
} catch {
    Write-Host ('[ERROR] Node.js setup failed: ' + $_.Exception.Message)
    Write-Host 'Check your network and retry, or install Node.js 24+ with npm from https://nodejs.org/'
    exit 1
} finally {
    if ($staging -and (Test-Path -LiteralPath $staging)) {
        $resolvedStaging = [IO.Path]::GetFullPath($staging)
        if ((Split-Path $resolvedStaging -Parent) -eq [IO.Path]::GetFullPath($runtime) -and
            (Split-Path $resolvedStaging -Leaf) -match '^node-download-[a-f0-9]{32}$') {
            Remove-Item -LiteralPath $resolvedStaging -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
}
