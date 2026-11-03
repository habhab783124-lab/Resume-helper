param([string]$ProjectRoot = (Split-Path $PSScriptRoot -Parent))

$ErrorActionPreference = 'Stop'
$runtime = Join-Path $ProjectRoot '.runtime'
New-Item -ItemType Directory -Force -Path $runtime | Out-Null

function Find-Tool([string]$Name, [string]$Configured) {
    $candidates = @()
    if ($Configured) { $candidates += $Configured }
    $command = Get-Command "$Name.exe" -CommandType Application -ErrorAction SilentlyContinue
    if ($command) { $candidates += $command.Source }
    Get-ChildItem -LiteralPath $runtime -Directory -Filter 'ffmpeg-*' | ForEach-Object {
        $candidates += Join-Path $_.FullName "bin\$Name.exe"
    }
    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            try {
                & $candidate -version *> $null
                if ($LASTEXITCODE -eq 0) { return (Resolve-Path -LiteralPath $candidate).Path }
            } catch { }
        }
    }
    return $null
}

try {
    $ffmpeg = Find-Tool 'ffmpeg' $env:FFMPEG_PATH
    $ffprobe = Find-Tool 'ffprobe' $env:FFPROBE_PATH
    if (!$ffmpeg -or !$ffprobe) {
        if (![Environment]::Is64BitOperatingSystem) {
            throw 'Automatic installation requires 64-bit Windows.'
        }
        Write-Host 'Downloading FFmpeg and FFprobe (first run only)...'
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        $staging = Join-Path $runtime ('speech-download-' + [guid]::NewGuid().ToString('N'))
        New-Item -ItemType Directory -Path $staging | Out-Null
        $archive = Join-Path $staging 'ffmpeg.zip'
        Invoke-WebRequest -UseBasicParsing -Uri 'https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip' -OutFile $archive -TimeoutSec 600
        Expand-Archive -LiteralPath $archive -DestinationPath $staging
        $package = Get-ChildItem -LiteralPath $staging -Directory -Filter 'ffmpeg-*' | Select-Object -First 1
        if (!$package) { throw 'Downloaded archive does not contain FFmpeg.' }
        foreach ($tool in @('ffmpeg', 'ffprobe')) {
            $executable = Join-Path $package.FullName "bin\$tool.exe"
            if (!(Test-Path -LiteralPath $executable)) { throw "Missing $tool.exe in download." }
            & $executable -version *> $null
            if ($LASTEXITCODE -ne 0) { throw "$tool.exe cannot run on this computer." }
        }
        # Keep the extracted version in a unique directory; never replace a running executable.
        $destination = Join-Path $runtime ('ffmpeg-' + [guid]::NewGuid().ToString('N'))
        Move-Item -LiteralPath $package.FullName -Destination $destination
        $ffmpeg = Join-Path $destination 'bin\ffmpeg.exe'
        $ffprobe = Join-Path $destination 'bin\ffprobe.exe'
    }
    # cmd.exe reads this file using the current Windows code page.
    $settings = @('@echo off', ('set "FFMPEG_PATH={0}"' -f $ffmpeg), ('set "FFPROBE_PATH={0}"' -f $ffprobe))
    [IO.File]::WriteAllLines((Join-Path $runtime 'speech-tools.cmd'), $settings, [Text.Encoding]::Default)
    Write-Host 'FFmpeg and FFprobe are ready.'
} catch {
    Write-Host ('[ERROR] Speech tools setup failed: ' + $_.Exception.Message)
    Write-Host 'Check your network and retry, or install FFmpeg from https://www.gyan.dev/ffmpeg/builds/'
    exit 1
} finally {
    if ($staging -and (Test-Path -LiteralPath $staging)) {
        # Only clean the unique staging directory created by this invocation.
        $resolvedStaging = [IO.Path]::GetFullPath($staging)
        if ((Split-Path $resolvedStaging -Parent) -eq [IO.Path]::GetFullPath($runtime) -and
            (Split-Path $resolvedStaging -Leaf) -match '^speech-download-[a-f0-9]{32}$') {
            Remove-Item -LiteralPath $resolvedStaging -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
}
