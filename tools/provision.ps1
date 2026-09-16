# One-time setup of the bridge on a headset connected over adb: installs the APK, exempts
# it from battery optimization, stores the PC address and starts the service.
#
#   tools\provision.ps1 -PcAddress 192.168.1.10 [-Autostart] [-Port 41463] [-Rate 60] [-FrameRate 10]
#                       [-GateApp VirtualDesktop.Android]   ("" runs the bridge always)

param(
    [Parameter(Mandatory = $true)][string]$PcAddress,
    [int]$Port = 41463,
    [float]$Rate = 60,
    [float]$FrameRate = 10,
    [string]$GateApp = "VirtualDesktop.Android",
    [switch]$Autostart,
    [string]$Apk = "",
    [string]$Adb = ""
)

$ErrorActionPreference = "Stop"
$repoDir = Split-Path $PSScriptRoot -Parent
$package = "dev.maverick.ftbridge"

if (-not $Adb) {
    $bundled = Join-Path $repoDir "platform-tools\adb.exe"
    $Adb = if (Test-Path $bundled) { $bundled } else { "adb" }
}
if (-not $Apk) {
    $Apk = Join-Path $repoDir "client\app\build\outputs\apk\release\app-release.apk"
    if (-not (Test-Path $Apk)) {
        $Apk = Join-Path $repoDir "client\app\build\outputs\apk\debug\app-debug.apk"
    }
}
if (-not (Test-Path $Apk)) { throw "APK not found, build it first (see README)" }

Write-Host "Installing $Apk ..."
& $Adb install -r $Apk
if ($LASTEXITCODE -ne 0) { throw "adb install failed" }

Write-Host "Exempting from battery optimization ..."
& $Adb shell dumpsys deviceidle whitelist "+$package" | Out-Null

Write-Host "Granting usage access (foreground app detection for gating) ..."
& $Adb shell appops set $package android:get_usage_stats allow | Out-Null

$autostartValue = if ($Autostart) { "true" } else { "false" }
$gateText = if ($GateApp) { "only while $GateApp runs" } else { "always" }
Write-Host "Starting the service -> ${PcAddress}:$Port at $Rate Hz (frames $FrameRate Hz, $gateText, autostart $autostartValue) ..."
if ($GateApp) {
    & $Adb shell am start-foreground-service -n "$package/.TrackingService" -a "$package.START" `
        --es host $PcAddress --ei port $Port --ef rate $Rate --ef framerate $FrameRate `
        --ez autostart $autostartValue --es gate $GateApp | Out-Null
} else {
    & $Adb shell am start-foreground-service -n "$package/.TrackingService" -a "$package.START" `
        --es host $PcAddress --ei port $Port --ef rate $Rate --ef framerate $FrameRate `
        --ez autostart $autostartValue --esn gate | Out-Null
}

Start-Sleep -Seconds 8
$heartbeat = & $Adb logcat -d -s "ftbridge:*" | Select-String -Pattern "heartbeat|error" | Select-Object -Last 1
if ($heartbeat) {
    Write-Host "Status: $($heartbeat.Line)"
} else {
    Write-Host "No status yet; check with: adb logcat -s ftbridge:*"
}
