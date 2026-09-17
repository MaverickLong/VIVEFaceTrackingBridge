# Records raw XR_HTC_eye_tracker samples (per-eye gaze pose, pupil diameter and position,
# eye openness / wide / squeeze) next to the HTC eye expressions of the same poll, as a CSV
# file. For evaluating the extension; the service goes back to its normal gated operation
# afterwards.
#
#   tools\probe-eye-tracker.ps1 [-Seconds 30] [-Out eye-probe.csv]
#
# Put the headset on before starting and keep it on until the countdown ends: look around,
# blink, squint, open your eyes wide. Eye tracking must be enabled in the bridge settings: the
# extension is only polled while the headset is worn and the eye expressions are active (the
# runtime aborts the process when it is polled with the eye tracker stopped).

param(
    [int]$Seconds = 30,
    [string]$Out = "eye-probe.csv",
    [string]$Adb = ""
)

$ErrorActionPreference = "Stop"
$repoDir = Split-Path $PSScriptRoot -Parent
$package = "dev.maverick.ftbridge"
$rawLog = Join-Path ([IO.Path]::GetTempPath()) "ftbridge-eye-probe.log"

if (-not $Adb) {
    $bundled = Join-Path $repoDir "platform-tools\adb.exe"
    $Adb = if (Test-Path $bundled) { $bundled } else { "adb" }
}

# Start the adb server detached (see provision.ps1)
Start-Process -FilePath $Adb -ArgumentList "start-server" -WindowStyle Hidden -Wait

& $Adb logcat -c
& $Adb shell am start-foreground-service -n "$package/.TrackingService" -a "$package.START" --ez eyeprobe true | Out-Null

if (Test-Path $rawLog) { Remove-Item $rawLog }
$logcat = Start-Process -FilePath $Adb -ArgumentList "logcat -v raw -s ftbridge:*" `
    -RedirectStandardOutput $rawLog -WindowStyle Hidden -PassThru

Write-Host "Recording for $Seconds s. Keep the headset on: look around, blink, squint, open your eyes wide ..."
$remaining = $Seconds
while ($remaining -gt 0) {
    Write-Host "  $remaining s left"
    $step = [Math]::Min(5, $remaining)
    Start-Sleep -Seconds $step
    $remaining -= $step
}

Stop-Process -Id $logcat.Id -Force
# Back to normal operation (the probe flag is not persisted)
& $Adb shell am start-foreground-service -n "$package/.TrackingService" -a "$package.START" | Out-Null

$lines = @(Get-Content $rawLog | Where-Object { $_ -match "EYEPROBE" })
$header = @($lines | Where-Object { $_ -match "EYEPROBE_HEADER," } | Select-Object -First 1)
$rows = @($lines | Where-Object { $_ -match "EYEPROBE," -and $_ -notmatch "EYEPROBE_HEADER" } |
    ForEach-Object { $_ -replace "^.*EYEPROBE,", "" })

if ($header.Count -eq 0) {
    Write-Host "The eye tracker was not created. Last log lines:"
    Get-Content $rawLog | Select-String -Pattern "probe|EyeTracker|source|error|phase" | Select-Object -Last 8 | ForEach-Object { "  " + $_.Line }
    throw "No XR_HTC_eye_tracker data; see above"
}
if ($rows.Count -eq 0) {
    throw "The eye tracker was created but produced no samples (was the headset worn?)"
}

$headerLine = $header[0] -replace "^.*EYEPROBE_HEADER,", ""
[IO.File]::WriteAllLines((Resolve-Path -LiteralPath (Split-Path -Parent ([IO.Path]::GetFullPath($Out)))).Path + "\" + (Split-Path -Leaf $Out), @($headerLine) + $rows)
Write-Host "$($rows.Count) samples written to $Out"

# Quick look: how much of it was valid, and the value ranges
$columns = $headerLine -split ","
$index = @{}
for ($i = 0; $i -lt $columns.Count; $i++) { $index[$columns[$i]] = $i }
$parsed = $rows | ForEach-Object { , ($_ -split ",") }
function Count-Valid($column) {
    @($parsed | Where-Object { $_[$index[$column]] -eq "1" }).Count
}
function Range($column, $validColumn) {
    $values = $parsed | Where-Object { $_[$index[$validColumn]] -eq "1" } | ForEach-Object { [double]$_[$index[$column]] }
    if (-not $values) { return "n/a" }
    $stats = $values | Measure-Object -Minimum -Maximum
    "{0:0.###} .. {1:0.###}" -f $stats.Minimum, $stats.Maximum
}
Write-Host ("  gaze valid:       L {0}, R {1} of {2}" -f (Count-Valid "l_gaze_valid"), (Count-Valid "r_gaze_valid"), $rows.Count)
Write-Host ("  pupil diameter:   L {0} valid, {1} mm; R {2} valid, {3} mm" -f (Count-Valid "l_diameter_valid"), (Range "l_diameter_mm" "l_diameter_valid"), (Count-Valid "r_diameter_valid"), (Range "r_diameter_mm" "r_diameter_valid"))
Write-Host ("  geometric valid:  L {0}, R {1}; openness L {2}, wide L {3}, squeeze L {4}" -f (Count-Valid "l_geometric_valid"), (Count-Valid "r_geometric_valid"), (Range "l_openness" "l_geometric_valid"), (Range "l_wide" "l_geometric_valid"), (Range "l_squeeze" "l_geometric_valid"))
Write-Host ("  eye expressions:  {0} valid; left_blink {1}, left_wide {2}" -f (Count-Valid "expressions_valid"), (Range "expr_left_blink" "expressions_valid"), (Range "expr_left_wide" "expressions_valid"))
