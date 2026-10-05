param([int]$Workers=4, [int]$Rounds=0, [int]$TrainingCount=12, [int]$EvaluationCount=4, [switch]$NoBrowser, [switch]$NoPause)
$ErrorActionPreference = 'Stop'
$TrainerRoot = $PSScriptRoot
$RuntimeRoot = Join-Path $TrainerRoot '.runtime'
$PreviousJava = $env:BRAIN_JAVA
$Awake = $false
try {
    if (-not (Test-Path -LiteralPath (Join-Path $RuntimeRoot 'ready.txt'))) { throw 'Run 1-Setup.cmd first.' }
    $Paths = Get-Content -LiteralPath (Join-Path $RuntimeRoot 'paths.json') -Raw | ConvertFrom-Json
    $NodeExe = Join-Path $RuntimeRoot $Paths.node
    $env:BRAIN_JAVA = Join-Path $RuntimeRoot $Paths.java
    $env:PLAYWRIGHT_BROWSERS_PATH = Join-Path $RuntimeRoot 'browsers'
    Add-Type -TypeDefinition 'using System; using System.Runtime.InteropServices; public static class BrainAwake { [DllImport("kernel32.dll")] public static extern uint SetThreadExecutionState(uint flags); }'
    $Awake = [BrainAwake]::SetThreadExecutionState([uint32]2147483649) -ne 0
    Write-Host 'Keep this window open while training. The screen may turn off.'
    Write-Host 'Use the dashboard Stop button or 3-Stop.cmd to stop and keep saved practice.'
    $TrainerArguments = @((Join-Path $TrainerRoot 'runner.mjs'), '--workers', "$Workers", '--rounds', "$Rounds", '--training-count', "$TrainingCount", '--evaluation-count', "$EvaluationCount")
    if ($NoBrowser) { $TrainerArguments += '--no-browser' }
    & $NodeExe @TrainerArguments
    if ($LASTEXITCODE -ne 0) { throw 'The trainer stopped with an error. Run 4-Results.cmd and send the results ZIP for review.' }
} catch {
    Write-Host $_.Exception.Message -ForegroundColor Red
    if (-not $NoPause) { Read-Host 'Press Enter to close' | Out-Null }
    exit 1
} finally {
    if ($Awake) { [BrainAwake]::SetThreadExecutionState([uint32]2147483648) | Out-Null }
    $env:BRAIN_JAVA = $PreviousJava
}
if (-not $NoPause) { Read-Host 'Training stopped. Press Enter to close' | Out-Null }
