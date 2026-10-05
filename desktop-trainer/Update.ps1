param([switch]$NoPause, [string]$ArchivePath, [string]$ExpectedHash)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$Stage = Join-Path ([IO.Path]::GetTempPath()) ('Site-Brain-Update-' + [guid]::NewGuid().ToString('N'))
try {
    $Paths = Get-Content (Join-Path $PSScriptRoot '.runtime\paths.json') -Raw | ConvertFrom-Json
    $Node = Join-Path $PSScriptRoot ('.runtime\' + $Paths.node)
    New-Item -ItemType Directory -Path $Stage | Out-Null
    $Zip = Join-Path $Stage 'update.zip'
    if ($ArchivePath) {
        if ($ExpectedHash -notmatch '^[a-f0-9]{64}$') { throw 'The local update checksum is required.' }
        Copy-Item -LiteralPath $ArchivePath -Destination $Zip
    } else {
        $Release = Invoke-RestMethod 'https://api.github.com/repos/lukeypue/AI-Browser/releases/tags/site-brain-trainer-latest' -Headers @{ 'User-Agent' = 'Site-Brain-Trainer' } -TimeoutSec 30
        $Asset = @($Release.assets | Where-Object name -eq 'Site-Brain-Trainer.zip')
        if ($Asset.Count -ne 1 -or $Asset[0].digest -notmatch '^sha256:[a-f0-9]{64}$') { throw 'No verified trainer update is available yet. Your saved training remains.' }
        $Current = Get-Content (Join-Path $PSScriptRoot 'bundle-manifest.json') -Raw | ConvertFrom-Json
        if ($Release.body -match ('Source: ' + [regex]::Escape($Current.source_commit) + '\b')) { Write-Host ('You already have trainer ' + $Current.version); exit 0 }
        $Url = $Asset[0].browser_download_url
        if ($Url -notmatch '^https://github\.com/lukeypue/AI-Browser/releases/download/site-brain-trainer-latest/Site-Brain-Trainer\.zip$') { throw 'Unexpected update source.' }
        $ExpectedHash = $Asset[0].digest.Substring(7)
        Invoke-WebRequest $Url -OutFile $Zip -UseBasicParsing -TimeoutSec 300
    }
    if ((Get-FileHash $Zip -Algorithm SHA256).Hash.ToLowerInvariant() -ne $ExpectedHash) { throw 'Download failed its integrity check. Try Update again.' }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $Archive = [IO.Compression.ZipFile]::OpenRead($Zip)
    try {
        foreach ($Entry in $Archive.Entries) {
            if ($Entry.FullName -notmatch '^Site-Brain-Trainer/(?:[A-Za-z0-9_.-]+|tests/[A-Za-z0-9_.-]+)$') { throw 'Unexpected update archive entry.' }
        }
    } finally { $Archive.Dispose() }
    Expand-Archive -LiteralPath $Zip -DestinationPath $Stage
    & $Node (Join-Path $PSScriptRoot 'update.mjs') (Join-Path $Stage 'Site-Brain-Trainer') $PSScriptRoot --refresh
    if ($LASTEXITCODE -ne 0) { throw 'Update did not finish. Saved training remains. If browser setup failed, press Start to retry.' }
} catch {
    Write-Host $_.Exception.Message -ForegroundColor Red
    exit 1
} finally { if (Test-Path $Stage) { Remove-Item -LiteralPath $Stage -Recurse -Force } }
