param([string]$ProjectRoot = (Split-Path $PSScriptRoot -Parent))

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$runtime = Join-Path $ProjectRoot '.runtime'
New-Item -ItemType Directory -Force -Path $runtime | Out-Null

function Test-Jdk([string]$JdkHome) {
    $java = Join-Path $JdkHome 'bin\java.exe'
    $javac = Join-Path $JdkHome 'bin\javac.exe'
    if (!(Test-Path -LiteralPath $java) -or !(Test-Path -LiteralPath $javac)) { return $false }
    try {
        # --version writes to stdout, avoiding PowerShell 5 native stderr errors.
        $version = (& $javac -version 2>$null | Out-String)
        if ($LASTEXITCODE -ne 0 -or $version -notmatch 'javac\s+(\d+)') { return $false }
        if ([int]$Matches[1] -lt 17) { return $false }
        & $java --version *> $null
        return ($LASTEXITCODE -eq 0)
    } catch { return $false }
}

try {
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
    $javacCommand = Get-Command javac.exe -CommandType Application -ErrorAction SilentlyContinue
    if ($javacCommand) { $candidates += Split-Path (Split-Path $javacCommand.Source -Parent) -Parent }
    Get-ChildItem -LiteralPath $runtime -Directory -Filter 'jdk-*' | ForEach-Object { $candidates += $_.FullName }
    $jdk = $null
    foreach ($candidate in $candidates) {
        if (Test-Jdk $candidate) { $jdk = (Resolve-Path -LiteralPath $candidate).Path; break }
    }
    if (!$jdk) {
        if (![Environment]::Is64BitOperatingSystem) { throw 'Automatic installation requires 64-bit Windows.' }
        Write-Host 'Downloading Eclipse Temurin JDK 17 (first run only)...'
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        $assets = Invoke-RestMethod -Uri 'https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse' -TimeoutSec 60
        $package = @($assets)[0].binary.package
        if (!$package.link -or !$package.checksum -or $package.link -notlike 'https://*') {
            throw 'JDK download metadata is incomplete.'
        }
        $staging = Join-Path $runtime ('java-download-' + [guid]::NewGuid().ToString('N'))
        New-Item -ItemType Directory -Path $staging | Out-Null
        $archive = Join-Path $staging 'jdk.zip'
        Invoke-WebRequest -UseBasicParsing -Uri 'https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse' -OutFile $archive -TimeoutSec 180
        if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $package.checksum) {
            throw 'JDK download checksum mismatch. Please retry.'
        }
        Expand-Archive -LiteralPath $archive -DestinationPath $staging
        $extracted = Get-ChildItem -LiteralPath $staging -Directory -Filter 'jdk-*' | Select-Object -First 1
        if (!$extracted -or !(Test-Jdk $extracted.FullName)) { throw 'Downloaded JDK cannot run on this computer.' }
        $jdk = Join-Path $runtime ('jdk-17-' + [guid]::NewGuid().ToString('N'))
        Move-Item -LiteralPath $extracted.FullName -Destination $jdk
    }
    $settings = @('@echo off', ('set "JAVA_HOME={0}"' -f $jdk), 'set "PATH=%JAVA_HOME%\bin;%PATH%"')
    [IO.File]::WriteAllLines((Join-Path $runtime 'java-env.cmd'), $settings, [Text.Encoding]::Default)
    Write-Host 'JDK is ready.'
} catch {
    Write-Host ('[ERROR] Java setup failed: ' + $_.Exception.Message)
    Write-Host 'Check your network and retry, or install JDK 17+ and configure JAVA_HOME.'
    exit 1
} finally {
    if ($staging -and (Test-Path -LiteralPath $staging)) {
        $resolvedStaging = [IO.Path]::GetFullPath($staging)
        if ((Split-Path $resolvedStaging -Parent) -eq [IO.Path]::GetFullPath($runtime) -and
            (Split-Path $resolvedStaging -Leaf) -match '^java-download-[a-f0-9]{32}$') {
            Remove-Item -LiteralPath $resolvedStaging -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
}
