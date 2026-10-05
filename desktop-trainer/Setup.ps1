param([switch]$NoPause)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
Set-StrictMode -Version Latest
$TrainerRoot = $PSScriptRoot
$RuntimeRoot = Join-Path $TrainerRoot '.runtime'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
New-Item -ItemType Directory -Path $RuntimeRoot -Force | Out-Null
$SetupHandle = $null
function Get-CheckedArchive([string]$Url, [string]$Hash, [string]$Destination) {
    if ($Hash -notmatch '^[a-fA-F0-9]{64}$') { throw 'Official download checksum is missing.' }
    Invoke-WebRequest -Uri $Url -OutFile $Destination -UseBasicParsing -TimeoutSec 300
    if ((Get-FileHash -Path $Destination -Algorithm SHA256).Hash.ToLowerInvariant() -ne $Hash.ToLowerInvariant()) {
        Remove-Item -LiteralPath $Destination -Force
        throw 'A runtime download failed its integrity check. Run setup again.'
    }
}
try {
    if (-not [Environment]::Is64BitOperatingSystem) { throw 'This trainer requires 64-bit Windows.' }
    Remove-Item -LiteralPath (Join-Path $RuntimeRoot 'ready.txt') -Force -ErrorAction SilentlyContinue
    $SetupHandle = [IO.File]::Open((Join-Path $RuntimeRoot 'setup.lock'), [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
    $Manifest = Get-Content -LiteralPath (Join-Path $TrainerRoot 'bundle-manifest.json') -Raw | ConvertFrom-Json
    foreach ($Entry in $Manifest.files.PSObject.Properties) {
        $FilePath = Join-Path $TrainerRoot $Entry.Name
        if ((Get-FileHash -LiteralPath $FilePath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $Entry.Value) { throw "Package file is damaged: $($Entry.Name). Extract a fresh copy." }
    }
    Write-Host 'Setting up your Site Brain training station. No administrator access is needed.'
    $PathsFile = Join-Path $RuntimeRoot 'paths.json'
    $Paths = $null
    if (Test-Path -LiteralPath $PathsFile) {
        try {
            $Existing = Get-Content -LiteralPath $PathsFile -Raw | ConvertFrom-Json
            if ((Test-Path -LiteralPath (Join-Path $RuntimeRoot $Existing.node)) -and (Test-Path -LiteralPath (Join-Path $RuntimeRoot $Existing.java))) { $Paths = $Existing }
        } catch { $Paths = $null }
    }
    if ($null -eq $Paths) {
        Write-Host 'Downloading the portable browser tools...'
        $Checksums = (Invoke-WebRequest -Uri 'https://nodejs.org/download/release/latest-v24.x/SHASUMS256.txt' -UseBasicParsing -TimeoutSec 60).Content
        $NodeMatch = [regex]::Match($Checksums, '(?m)^([a-f0-9]{64})\s+(node-v24\.[0-9]+\.[0-9]+-win-x64\.zip)\s*$')
        if (-not $NodeMatch.Success) { throw 'Could not resolve the official Node 24 download.' }
        $NodeZip = Join-Path $RuntimeRoot 'node-download.zip'
        Get-CheckedArchive ('https://nodejs.org/download/release/latest-v24.x/' + $NodeMatch.Groups[2].Value) $NodeMatch.Groups[1].Value $NodeZip
        Expand-Archive -LiteralPath $NodeZip -DestinationPath $RuntimeRoot -Force
        $NodeFolder = $NodeMatch.Groups[2].Value.Substring(0, $NodeMatch.Groups[2].Value.Length - 4)
        $Assets = @(Invoke-RestMethod -Uri 'https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture=x64&heap_size=normal&image_type=jre&os=windows&vendor=eclipse' -TimeoutSec 60)
        if ($Assets.Count -lt 1) { throw 'Could not resolve the official Java runtime download.' }
        $JavaPackage = $Assets[0].binary.package
        if ($JavaPackage.link -notmatch '^https://github\.com/adoptium/temurin17-binaries/releases/download/.+\.zip$') { throw 'Unexpected Java download source.' }
        $JavaZip = Join-Path $RuntimeRoot 'java-download.zip'
        Get-CheckedArchive $JavaPackage.link $JavaPackage.checksum $JavaZip
        $JavaRoot = Join-Path $RuntimeRoot 'java'
        New-Item -ItemType Directory -Path $JavaRoot -Force | Out-Null
        Expand-Archive -LiteralPath $JavaZip -DestinationPath $JavaRoot -Force
        $JavaExe = @(Get-ChildItem -LiteralPath $JavaRoot -Filter java.exe -Recurse)[0].FullName
        $Paths = @{ node = "$NodeFolder\node.exe"; java = $JavaExe.Substring($RuntimeRoot.Length + 1); nodeVersion = $NodeFolder; javaVersion = $Assets[0].version.openjdk_version }
        $Paths | ConvertTo-Json | Set-Content -LiteralPath $PathsFile -Encoding UTF8
        Remove-Item -LiteralPath $NodeZip, $JavaZip -Force
    }
    $NodeExe = Join-Path $RuntimeRoot $Paths.node
    $NpmCmd = Join-Path (Split-Path -Parent $NodeExe) 'npm.cmd'
    $PreviousPath = $env:PATH
    $env:PATH = (Split-Path -Parent $NodeExe) + ';' + $env:PATH
    $env:PLAYWRIGHT_BROWSERS_PATH = Join-Path $RuntimeRoot 'browsers'
    Push-Location $TrainerRoot
    try {
        Write-Host 'Installing the practice browser. This can take several minutes the first time.'
        & $NpmCmd ci --ignore-scripts --no-audit --no-fund
        if ($LASTEXITCODE -ne 0) { throw 'Browser tools could not be installed. Check the internet connection and run setup again.' }
        & $NodeExe (Join-Path $TrainerRoot 'node_modules\playwright\cli.js') install chromium
        if ($LASTEXITCODE -ne 0) { throw 'The practice browser could not be downloaded. Run setup again.' }
    } finally { Pop-Location; $env:PATH = $PreviousPath }
    Set-Content -LiteralPath (Join-Path $RuntimeRoot 'ready.txt') -Value 'Setup complete' -Encoding ASCII
    Write-Host ''
    Write-Host 'READY. Open Trainer.cmd and press Start.' -ForegroundColor Green
} catch {
    Write-Host ('SETUP STOPPED: ' + $_.Exception.Message) -ForegroundColor Red
    Write-Host 'Your existing app and its learning were not changed.'
    if (-not $NoPause) { Read-Host 'Press Enter to close' | Out-Null }
    exit 1
} finally { if ($null -ne $SetupHandle) { $SetupHandle.Dispose() } }
if (-not $NoPause) { Read-Host 'Press Enter to close' | Out-Null }
