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
        $Paths = Get-Content (Join-Path $PSScriptRoot '.runtime\paths.json') -Raw | ConvertFrom-Json
        $Node = Join-Path $PSScriptRoot ('.runtime\' + $Paths.node)
        $Snapshot = Join-Path ([IO.Path]::GetTempPath()) ('Site-Brain-Results-' + [guid]::NewGuid().ToString('N'))
        try {
            & $Node (Join-Path $PSScriptRoot 'export.mjs') $PSScriptRoot $Snapshot
            if ($LASTEXITCODE -ne 0) { throw 'Results could not be captured. Saved training remains; try Download Results again.' }
            $Items = @(Get-ChildItem -LiteralPath $Snapshot)
            Compress-Archive -LiteralPath @($Items.FullName) -DestinationPath $Results -Force
        } finally { if (Test-Path $Snapshot) { Remove-Item -LiteralPath $Snapshot -Recurse -Force } }
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
