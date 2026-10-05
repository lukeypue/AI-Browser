param([ValidateSet('Stop','Results')][string]$Action='Stop', [switch]$NoPause, [string]$DestinationDirectory)
$ErrorActionPreference = 'Stop'
$Data = Join-Path $PSScriptRoot 'data'
try {
    if ($Action -eq 'Stop') {
        $Connection = Get-Content -LiteralPath (Join-Path $Data 'connection.json') -Raw | ConvertFrom-Json
        $Url = [Uri]$Connection.url
        if ($Url.Scheme -ne 'http' -or $Url.Host -ne '127.0.0.1' -or $Connection.token -notmatch '^[a-f0-9]{48}$') { throw 'Invalid trainer connection record.' }
        Invoke-RestMethod -Uri ($Connection.url + '/stop') -Method Post -Headers @{ 'X-Trainer-Token' = $Connection.token } -TimeoutSec 5 | Out-Null
        Write-Host 'Stopping training. Previous saved practice will remain.'
    } else {
        if (-not (Test-Path -LiteralPath $Data)) { throw 'There are no results yet. Start the trainer first.' }
        if (-not $DestinationDirectory) {
            $DestinationDirectory = (New-Object -ComObject Shell.Application).NameSpace('shell:Downloads').Self.Path
        }
        New-Item -ItemType Directory -Path $DestinationDirectory -Force | Out-Null
        $Results = Join-Path $DestinationDirectory 'Site-Brain-Training-Results.zip'
        $Items = @(Get-ChildItem -LiteralPath $Data | Where-Object { $_.Name -ne 'connection.json' -and $_.Name -ne 'runner.lock' -and $_.Name -ne 'stop-requested' -and $_.Extension -ne '.log' })
        if ($Items.Count -eq 0) { throw 'No completed practice results are available yet.' }
        $ExportPaths = @($Items.FullName)
        $BuildManifest = Join-Path $PSScriptRoot 'bundle-manifest.json'
        if (Test-Path -LiteralPath $BuildManifest) { $ExportPaths += $BuildManifest }
        Compress-Archive -LiteralPath $ExportPaths -DestinationPath $Results -Force
        Write-Host 'Send this ZIP in our AI Browser chat:'
        Write-Host $Results -ForegroundColor Green
        if (-not $NoPause) { Invoke-Item $DestinationDirectory }
    }
} catch {
    Write-Host $_.Exception.Message -ForegroundColor Red
    if ($Action -eq 'Stop') { Write-Host 'If the launcher has already closed, the trainer is already stopped.' }
    if (-not $NoPause) { Read-Host 'Press Enter to close' | Out-Null }
    exit 1
}
if (-not $NoPause) { Read-Host 'Press Enter to close' | Out-Null }
