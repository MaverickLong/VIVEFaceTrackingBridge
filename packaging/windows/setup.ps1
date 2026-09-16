# FT Bridge - headset setup for Windows.
#
# Installs the FT Bridge app on a VIVE headset connected over USB and points it at this PC.
# Double-click Setup.cmd to run it; no arguments are needed. Works with Windows PowerShell 5.1.

param(
    [string]$PcAddress = "",
    [string]$GateApp = "VirtualDesktop.Android",
    [switch]$NoAutostart
)

$ErrorActionPreference = "Stop"
# The progress bar makes Invoke-WebRequest extremely slow on Windows PowerShell 5.1
$ProgressPreference = "SilentlyContinue"
$here = $PSScriptRoot
$platformToolsUrl = "https://dl.google.com/android/repository/platform-tools-latest-windows.zip"
$deviceWaitSeconds = 600
$waitReportSeconds = 15

function Write-Step($text) {
    Write-Host ""
    Write-Host "==> $text" -ForegroundColor Cyan
}

function Write-Hint($text) {
    Write-Host "    $text" -ForegroundColor Yellow
}

function Get-Adb {
    $adb = Join-Path $here "platform-tools\adb.exe"
    if (-not (Test-Path $adb)) {
        Write-Step "Downloading the Android USB tools (adb, about 10 MB) from Google, one time only ..."
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        $zip = Join-Path $here "platform-tools.zip"
        Invoke-WebRequest -Uri $platformToolsUrl -OutFile $zip -UseBasicParsing
        Expand-Archive -Path $zip -DestinationPath $here -Force
        Remove-Item $zip
        Write-Host "    Done."
    }

    # The adb server daemon inherits the console handles of whoever starts it; started
    # from a captured pipeline it would keep that pipeline open forever. Start it
    # detached once so later adb calls only talk to the running server.
    Start-Process -FilePath $adb -ArgumentList "start-server" -WindowStyle Hidden -Wait

    return $adb
}

function Wait-Headset($adb) {
    Write-Step "Looking for the headset over USB ..."
    Write-Hint "Connect the headset with a USB cable. USB debugging must be enabled:"
    Write-Hint "VIVE Manager app > your headset > Developer mode, or headset Settings > Advanced."

    $started = Get-Date
    $deadline = $started.AddSeconds($deviceWaitSeconds)
    $nextReport = $started.AddSeconds($waitReportSeconds)
    $lastState = ""
    while ((Get-Date) -lt $deadline) {
        if ((Get-Date) -ge $nextReport) {
            $elapsed = [int]((Get-Date) - $started).TotalSeconds
            Write-Host "    ... still waiting ($elapsed s, giving up after $deviceWaitSeconds s)"
            $nextReport = (Get-Date).AddSeconds($waitReportSeconds)
        }

        # @() keeps a single match an array; a lone string would be indexed by character
        $lines = @((& $adb devices | Out-String) -split "`r?`n" |
            Where-Object { $_ -match '^\S+\s+(device|unauthorized|offline)\s*$' })
        $state = "none"
        if ($lines.Count -gt 0) {
            $state = ($lines[0] -split '\s+')[1]
        }

        if ($state -eq "device") {
            Write-Host "    Headset found."
            return
        }
        if ($state -ne $lastState) {
            switch ($state) {
                "unauthorized" { Write-Hint "Put the headset on and tap 'Allow' on the USB debugging prompt." }
                "offline" { Write-Hint "The headset shows as offline; unplug and replug the cable." }
                default { Write-Hint "No headset found yet, waiting ..." }
            }
            $lastState = $state
        }
        Start-Sleep -Seconds 3
    }

    throw "No headset found after $($deviceWaitSeconds / 60) minutes."
}

function ConvertTo-AddressNumber($ip) {
    $bytes = [System.Net.IPAddress]::Parse($ip).GetAddressBytes()
    [Array]::Reverse($bytes)

    return [uint64][BitConverter]::ToUInt32($bytes, 0)
}

function Test-SameNetwork($a, $b, $prefix) {
    # 0xFFFFFFFF is an Int32 (-1) literal in PowerShell, so build the mask arithmetically
    $allOnes = [uint64]4294967295
    $hostBits = [uint64][math]::Pow(2, 32 - $prefix)
    $mask = $allOnes - ($hostBits - 1)

    return ((ConvertTo-AddressNumber $a) -band $mask) -eq ((ConvertTo-AddressNumber $b) -band $mask)
}

function Get-PcAddress($adb) {
    $pcAddresses = Get-NetIPAddress -AddressFamily IPv4 |
        Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' }

    # Prefer the address on the same network as the headset's Wi-Fi
    $wlan = & $adb shell ip -4 addr show wlan0 2>$null | Out-String
    if ($wlan -match 'inet (\d+\.\d+\.\d+\.\d+)/(\d+)') {
        $headsetIp = $Matches[1]
        $prefix = [int]$Matches[2]
        $match = $pcAddresses | Where-Object { Test-SameNetwork $_.IPAddress $headsetIp $prefix } | Select-Object -First 1
        if ($match) {
            Write-Host "    Headset Wi-Fi: $headsetIp, this PC: $($match.IPAddress)"
            return $match.IPAddress
        }
        Write-Hint "The headset ($headsetIp) and this PC do not seem to share a network."
    } else {
        Write-Hint "Could not read the headset's Wi-Fi address (is its Wi-Fi on?)."
    }

    Write-Host "    Addresses of this PC:"
    $pcAddresses | ForEach-Object { Write-Host "      $($_.IPAddress)  ($($_.InterfaceAlias))" }
    $answer = (Read-Host "    Type the address of this PC that the headset can reach").Trim()
    if (-not ($answer -match '^\d+\.\d+\.\d+\.\d+$')) {
        throw "'$answer' is not an IPv4 address"
    }

    return $answer
}

try {
    Write-Host "FT Bridge setup" -ForegroundColor Green
    $adb = Get-Adb
    Wait-Headset $adb

    if (-not $PcAddress) {
        Write-Step "Finding the address of this PC ..."
        $PcAddress = Get-PcAddress $adb
    }

    Write-Step "Installing and configuring FT Bridge on the headset ..."
    $provisionArgs = @{
        PcAddress = $PcAddress
        GateApp = $GateApp
        Apk = (Join-Path $here "FTBridge.apk")
        Adb = $adb
    }
    if (-not $NoAutostart) {
        $provisionArgs.Autostart = $true
    }
    & (Join-Path $here "provision.ps1") @provisionArgs

    Write-Host ""
    Write-Host "All done." -ForegroundColor Green
    Write-Host "  1. Unplug the headset, put it on and start Virtual Desktop as usual."
    Write-Host "  2. Keep VRCFaceTracking (with the ALVR module) running on this PC."
    Write-Host "  The bridge starts by itself whenever Virtual Desktop runs, also after a reboot."
    Write-Host "  Run this setup again if this PC gets a different network address."
} catch {
    Write-Host ""
    Write-Host "Setup failed: $($_.Exception.Message)" -ForegroundColor Red
}

Write-Host ""
Read-Host "Press Enter to close" | Out-Null
