param([switch]$SmokeTest)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
[Windows.Forms.Application]::EnableVisualStyles()
$script:Root = $PSScriptRoot
$script:Operation = $null
$script:TrainerProcess = $null
$script:PendingUpdate = $false
$script:StartAfterSetup = $false
$script:Closing = $false
$script:SmokePhase = 0
$script:StartedAt = Get-Date
$Data = Join-Path $script:Root 'data'
New-Item -ItemType Directory -Path $Data -Force | Out-Null
$Log = Join-Path $Data 'control-window.log'
function Start-Operation([string]$File, [string]$Arguments = '') {
    $script:OperationName = $File
    $launched = Start-Process powershell.exe -ArgumentList ('-NoProfile -ExecutionPolicy Bypass -File "' + (Join-Path $script:Root $File) + '" -NoPause ' + $Arguments) -WindowStyle Hidden -PassThru -RedirectStandardOutput $(if ($File -eq 'Run.ps1') { Join-Path $Data 'training-launch.log' } else { $Log }) -RedirectStandardError (Join-Path $Data ($File + '-errors.log'))
    if ($File -eq 'Run.ps1') { $script:TrainerProcess = $launched } else { $script:Operation = $launched }
}
function Get-Running {
    $ConnectionFile = Join-Path $Data 'connection.json'
    if (-not (Test-Path $ConnectionFile)) { return $false }
    try {
        $c = Get-Content $ConnectionFile -Raw | ConvertFrom-Json
        if ($c.url -notmatch '^http://127\.0\.0\.1:[0-9]+$') { return $false }
        $script:Live = Invoke-RestMethod ($c.url + '/status') -TimeoutSec 1
        return [bool]$script:Live.running
    } catch { return $false }
}
function Stop-Training {
    if (Get-Running) {
        $c = Get-Content (Join-Path $Data 'connection.json') -Raw | ConvertFrom-Json
        Invoke-RestMethod ($c.url + '/stop') -Method Post -Headers @{ 'X-Trainer-Token' = $c.token } -TimeoutSec 5 | Out-Null
    }
}
$form = New-Object Windows.Forms.Form
$form.Text = 'Site Brain Trainer'; $form.Size = New-Object Drawing.Size(820,540); $form.StartPosition = 'CenterScreen'; $form.MinimumSize = $form.Size
$form.Font = New-Object Drawing.Font('Segoe UI',11)
$title = New-Object Windows.Forms.Label; $title.Text = 'Site Brain Training Station'; $title.Location = New-Object Drawing.Point(24,20); $title.Size = New-Object Drawing.Size(750,42); $title.Font = New-Object Drawing.Font('Segoe UI',22,[Drawing.FontStyle]::Bold); $form.Controls.Add($title)
$version = New-Object Windows.Forms.Label; $version.Location = New-Object Drawing.Point(26,65); $version.Size = New-Object Drawing.Size(740,25); $form.Controls.Add($version)
$script:Status = New-Object Windows.Forms.Label; $script:Status.Location = New-Object Drawing.Point(26,104); $script:Status.Size = New-Object Drawing.Size(740,54); $script:Status.Text = 'Ready. Start resumes saved training.'; $form.Controls.Add($script:Status)
$buttons = @{}
$i = 0
foreach ($name in @('Start','Stop','Download Results','Update')) {
    $b = New-Object Windows.Forms.Button; $b.Text = $name; $b.Location = New-Object Drawing.Point((26 + $i * 190),166); $b.Size = New-Object Drawing.Size(178,45); $buttons[$name] = $b; $form.Controls.Add($b); $i++
}
$summary = New-Object Windows.Forms.Label; $summary.Location = New-Object Drawing.Point(26,232); $summary.Size = New-Object Drawing.Size(740,155); $form.Controls.Add($summary)
$import = New-Object Windows.Forms.Button; $import.Text = 'Import Previous Training'; $import.Location = New-Object Drawing.Point(26,395); $import.Size = New-Object Drawing.Size(240,38); $form.Controls.Add($import)
$note = New-Object Windows.Forms.Label; $note.Text = 'Updates keep saved memories and batches. Results go to Downloads.' + [Environment]::NewLine + 'Practice websites only. No paid AI calls. Keep this window open while training.'; $note.Location = New-Object Drawing.Point(26,448); $note.Size = New-Object Drawing.Size(750,52); $form.Controls.Add($note)
$buttons['Start'].Add_Click({
    try {
        if ((Get-Running) -or $script:Operation -or $script:TrainerProcess) { return }
        if (-not (Test-Path (Join-Path $script:Root '.runtime\ready.txt'))) { $script:StartAfterSetup = $true; Start-Operation 'Setup.ps1'; $script:Status.Text = 'Setting up the browser tools. This can take several minutes.' }
        else { Start-Operation 'Run.ps1' ('-NoBrowser' + $(if ($SmokeTest) { ' -Workers 1 -TrainingCount 2 -EvaluationCount 2' } else { '' })); $script:Status.Text = 'Starting training from saved memories...' }
    } catch { $script:Status.Text = $_.Exception.Message }
})
$buttons['Stop'].Add_Click({ try { Stop-Training; $script:Status.Text = 'Stopping and keeping saved training...' } catch { $script:Status.Text = $_.Exception.Message } })
$buttons['Download Results'].Add_Click({ try { Start-Operation 'Control.ps1' '-Action Results'; $script:Status.Text = 'Saving the results ZIP to Downloads...' } catch { $script:Status.Text = $_.Exception.Message } })
$buttons['Update'].Add_Click({
    try { $script:PendingUpdate = $true; Stop-Training; $script:Status.Text = 'Stopping training, then checking for an update...' }
    catch { $script:PendingUpdate = $false; $script:Status.Text = $_.Exception.Message }
})
$import.Add_Click({
    try {
        if (Get-Running) { throw 'Stop training before importing.' }
        if (@(Get-ChildItem $Data -Directory -Filter 'worker-*').Count -gt 0) { throw 'This program already has saved worker memories. Import into a new trainer folder to avoid overwriting them.' }
        $picker = New-Object Windows.Forms.FolderBrowserDialog; $picker.Description = 'Select your previous Site-Brain-Trainer folder (the one with data inside).'
        if ($picker.ShowDialog() -eq [Windows.Forms.DialogResult]::OK) {
            $previous = Join-Path $picker.SelectedPath 'data'
            $workers = @(Get-ChildItem $previous -Directory -Filter 'worker-*' -ErrorAction Stop)
            if ($workers.Count -eq 0) { throw 'No saved training was found in that folder.' }
            foreach ($worker in $workers) { Copy-Item -LiteralPath $worker.FullName -Destination $Data -Recurse }
            $script:Status.Text = 'Previous training imported. Press Start to continue.'
        }
    } catch { $script:Status.Text = $_.Exception.Message }
})
$timer = New-Object Windows.Forms.Timer; $timer.Interval = 1000
$timer.Add_Tick({
    try {
        $running = Get-Running
        if ($script:Closing -and $running) { Stop-Training }
        if ($script:TrainerProcess -and $script:TrainerProcess.HasExited) {
            $launchCode = $script:TrainerProcess.ExitCode; $script:TrainerProcess.Dispose(); $script:TrainerProcess = $null
            if (-not $script:Operation) {
                if ($launchCode -ne 0) {
                    $failure = @(Get-Content (Join-Path $Data 'training-launch.log') -Tail 3 -ErrorAction SilentlyContinue) + @(Get-Content (Join-Path $Data 'Run.ps1-errors.log') -Tail 3 -ErrorAction SilentlyContinue)
                    $script:Status.Text = 'Training could not finish: ' + ($failure -join ' ')
                } else { $script:Status.Text = 'Training stopped. Saved training remains.' }
            }
        }
        $m = Get-Content (Join-Path $script:Root 'bundle-manifest.json') -Raw | ConvertFrom-Json
        $version.Text = 'Trainer ' + $m.version + ' | Search, price filters, listing details, and pagination'
        if ($script:Operation -and $script:Operation.HasExited) {
            $code = $script:Operation.ExitCode; $name = $script:OperationName; $script:Operation.Dispose(); $script:Operation = $null
            $script:Status.Text = (Get-Content $Log -Raw).Trim()
            if ($script:Status.Text.Length -gt 240) { $script:Status.Text = $script:Status.Text.Substring([Math]::Max(0,$script:Status.Text.Length - 240)) }
            if ($name -eq 'Setup.ps1' -and $script:StartAfterSetup -and $code -eq 0 -and -not $script:Closing) { $script:StartAfterSetup = $false; $buttons['Start'].Enabled = $true; $buttons['Start'].PerformClick() }
        }
        if ($script:PendingUpdate -and -not $running -and -not $script:Operation -and -not $script:TrainerProcess) {
            $script:PendingUpdate = $false
            if (-not (Test-Path (Join-Path $script:Root '.runtime\ready.txt'))) { $script:Status.Text = 'Press Start once to install browser tools before updating.' }
            else { Start-Operation 'Update.ps1'; $script:Status.Text = 'Checking and installing the update. Saved training stays here...' }
        }
        $busy = [bool]$script:Operation -or $script:PendingUpdate
        $buttons['Start'].Enabled = -not $running -and -not $busy -and -not $script:TrainerProcess
        $buttons['Stop'].Enabled = $running
        $buttons['Download Results'].Enabled = -not $busy
        # Run.ps1 is the running operation; updating queues until it exits.
        $buttons['Update'].Enabled = -not $script:PendingUpdate -and -not $script:Operation
        $import.Enabled = -not $running -and -not $busy
        if ($running) {
            if (-not $script:PendingUpdate -and -not $script:Closing) { $script:Status.Text = 'Training is running. Stop saves your progress.' }
            $rows = @($script:Live.workers | ForEach-Object { 'Worker ' + $_.number + ': ' + $_.phase + ' | ' + $_.completedRounds + ' saved batches | ' + $_.verifiedPractice + ' verified lessons this session' })
            $summary.Text = 'CPU: ' + $script:Live.resources.cpuPercent + '% | Memory: ' + $script:Live.resources.memoryUsedGB + ' GB' + [Environment]::NewLine + [Environment]::NewLine + ($rows -join [Environment]::NewLine)
        } elseif (Test-Path (Join-Path $Data 'status.json')) {
            $saved = Get-Content (Join-Path $Data 'status.json') -Raw | ConvertFrom-Json
            $summary.Text = 'Saved batches: ' + (($saved.workers | Measure-Object completedRounds -Sum).Sum) + '. Start continues from saved memories.'
        }
        if ($script:Closing -and -not $running -and -not $script:Operation -and -not $script:TrainerProcess) { if ($SmokeTest) { $script:SmokePhase = 4 }; $form.Close() }
        if ($SmokeTest) {
            if (((Get-Date) - $script:StartedAt).TotalSeconds -gt 90) { throw 'Control window smoke timed out.' }
            if ($script:SmokePhase -eq 0 -and $running) {
                $bitmap = New-Object Drawing.Bitmap($form.Width,$form.Height); $form.DrawToBitmap($bitmap, $form.ClientRectangle); $bitmap.Save((Join-Path $Data 'control-window-smoke.png')); $bitmap.Dispose()
                $script:SmokePhase = 1; $buttons['Download Results'].PerformClick() }
            elseif ($script:SmokePhase -eq 1 -and $running -and -not $script:Operation) { $script:SmokePhase = 2; $buttons['Stop'].PerformClick() }
            elseif ($script:SmokePhase -eq 2 -and -not $running -and -not $script:Operation -and -not $script:TrainerProcess) { $script:SmokePhase = 3; $buttons['Start'].Enabled = $true; $buttons['Start'].PerformClick(); $form.Close() }
        }
    } catch { $script:Status.Text = $_.Exception.Message; if ($SmokeTest) { $script:SmokeFailure = $_.Exception.Message; $form.Close() } }
})
$form.Add_FormClosing({
    param($sender,$eventArgs)
    if ((-not $SmokeTest -or $script:SmokePhase -eq 3) -and ((Get-Running) -or $script:Operation -or $script:TrainerProcess)) { $eventArgs.Cancel = $true; $script:Closing = $true; Stop-Training; $script:Status.Text = 'Stopping safely before closing...' }
})
$form.Add_Shown({ $timer.Start(); if ($SmokeTest) { $buttons['Start'].PerformClick() } })
[void]$form.ShowDialog(); $timer.Stop(); $timer.Dispose()
if ($SmokeTest -and ($script:SmokeFailure -or $script:SmokePhase -ne 4)) { throw ('Control window failed: ' + $script:SmokeFailure) }
