# One-time setup of the bridge on a headset connected over adb: installs the service app and
# the settings app, exempts the service from battery optimization, stores the PC address and
# the other settings, and starts the service.
#
#   tools\provision.ps1 -PcAddress 192.168.1.10 [-Autostart] [-Port 41463] [-Rate 60] [-FrameRate 10]
#                       [-GateApps VirtualDesktop.Android,com.valvesoftware.steamlinkvr] [-Always]
#                       [-NoEye] [-NoFace]
#
# Everything set here can be changed later in the FT Bridge Settings app on the headset.

param(
    [Parameter(Mandatory = $true)][string]$PcAddress,
    [int]$Port = 41463,
    [float]$Rate = 60,
    [float]$FrameRate = 10,
    # Run the bridge only while one of these apps is in the foreground (empty: always)
    [string[]]$GateApps = @("VirtualDesktop.Android"),
    # Forward regardless of the foreground app
    [switch]$Always,
    [switch]$NoEye,
    [switch]$NoFace,
    [switch]$Autostart,
    [string]$Apk = "",
    [string]$SettingsApk = "",
    [string]$Adb = ""
)

$ErrorActionPreference = "Stop"
$repoDir = Split-Path $PSScriptRoot -Parent
$package = "dev.maverick.ftbridge"
$settingsPackage = "dev.maverick.ftbridge.settings"
# Runtime permissions of Meta and Pico headsets; granting fails harmlessly elsewhere
$trackingPermissions = @(
    "com.oculus.permission.EYE_TRACKING", "com.oculus.permission.FACE_TRACKING",
    "com.picovr.permission.EYE_TRACKING", "com.picovr.permission.FACE_TRACKING",
    "android.permission.RECORD_AUDIO"
)

function Find-Apk($module, $name) {
    $release = Join-Path $repoDir "client\$module\build\outputs\apk\release\$name-release.apk"
    if (Test-Path $release) { return $release }
    return Join-Path $repoDir "client\$module\build\outputs\apk\debug\$name-debug.apk"
}

function Install-Apk($path, $packageName) {
    Write-Host "Installing $path ..."
    # cmd merges the streams; PowerShell would turn adb's stderr into errors
    $installOutput = cmd /c "`"$Adb`" install -r `"$path`" 2>&1" | Out-String
    if ($LASTEXITCODE -ne 0) {
        if ($installOutput -match "INSTALL_FAILED_UPDATE_INCOMPATIBLE") {
            # Installed build was signed with a different key (e.g. a debug-signed CI build);
            # settings are re-applied below, so a reinstall loses nothing
            Write-Host "Existing installation has a different signature, reinstalling ..."
            cmd /c "`"$Adb`" uninstall $packageName >nul 2>&1"
            cmd /c "`"$Adb`" install `"$path`" >nul 2>&1"
            if ($LASTEXITCODE -ne 0) { throw "adb install failed after uninstall" }
        } else {
            throw "adb install failed: $installOutput"
        }
    }
}

if (-not $Adb) {
    $bundled = Join-Path $repoDir "platform-tools\adb.exe"
    $Adb = if (Test-Path $bundled) { $bundled } else { "adb" }
}
if (-not $Apk) { $Apk = Find-Apk "app" "app" }
if (-not $SettingsApk) { $SettingsApk = Find-Apk "panel" "panel" }
if (-not (Test-Path $Apk)) { throw "APK not found at $Apk, build it first (see README)" }
if (-not (Test-Path $SettingsApk)) { throw "Settings APK not found at $SettingsApk, build it first (see README)" }

# Start the adb server detached: spawned from a captured pipeline it would inherit the
# pipeline's handles and keep it open until the server exits
Start-Process -FilePath $Adb -ArgumentList "start-server" -WindowStyle Hidden -Wait

# The service app first: it defines the permission the settings app is granted at install
Install-Apk $Apk $package
Install-Apk $SettingsApk $settingsPackage

Write-Host "Exempting from battery optimization ..."
& $Adb shell dumpsys deviceidle whitelist "+$package" | Out-Null

Write-Host "Granting usage access (foreground app detection for gating) ..."
& $Adb shell appops set $package android:get_usage_stats allow | Out-Null

foreach ($permission in $trackingPermissions) {
    cmd /c "`"$Adb`" shell pm grant $package $permission >nul 2>&1"
}

$autostartValue = if ($Autostart) { "true" } else { "false" }
$alwaysValue = if ($Always) { "true" } else { "false" }
$eyeValue = if ($NoEye) { "false" } else { "true" }
$faceValue = if ($NoFace) { "false" } else { "true" }
$gateList = @($GateApps | Where-Object { $_ }) -join ","
$gateText = if ($Always -or -not $gateList) { "always" } else { "only while $gateList runs" }
Write-Host "Starting the service -> ${PcAddress}:$Port at $Rate Hz (frames $FrameRate Hz, eye $eyeValue, face $faceValue, $gateText, autostart $autostartValue) ..."
& $Adb logcat -c
if ($gateList) {
    & $Adb shell am start-foreground-service -n "$package/.TrackingService" -a "$package.START" `
        --es host $PcAddress --ei port $Port --ef rate $Rate --ef framerate $FrameRate `
        --ez autostart $autostartValue --ez always $alwaysValue --ez eye $eyeValue --ez face $faceValue `
        --es gate $gateList | Out-Null
} else {
    & $Adb shell am start-foreground-service -n "$package/.TrackingService" -a "$package.START" `
        --es host $PcAddress --ei port $Port --ef rate $Rate --ef framerate $FrameRate `
        --ez autostart $autostartValue --ez always $alwaysValue --ez eye $eyeValue --ez face $faceValue `
        --esn gate | Out-Null
}

Start-Sleep -Seconds 8
$processId = (& $Adb shell pidof $package | Out-String).Trim()
if (-not $processId) {
    throw "The service is not running; check with: adb logcat -s ftbridge:*"
}
Write-Host "Service is running."
$heartbeat = & $Adb logcat -d -s "ftbridge:*" | Select-String -Pattern "heartbeat|error" | Select-Object -Last 1
if ($heartbeat) {
    Write-Host "Status: $($heartbeat.Line)"
} elseif ($gateText -ne "always") {
    Write-Host "The bridge will start as soon as one of these apps is in the foreground on the headset: $gateList"
} else {
    Write-Host "No status yet; check with: adb logcat -s ftbridge:*"
}
