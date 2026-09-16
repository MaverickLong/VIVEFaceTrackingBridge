# Builds the Rust core for arm64 and stages it, together with the Khronos
# OpenXR loader, into app/src/main/jniLibs. Run before `gradlew assembleDebug`.
#
# Requirements: rustup target aarch64-linux-android, cargo-ndk, Android NDK.

param(
    [string]$Profile = "release",
    [string]$OpenXrVersion = "1.1.36"
)

$ErrorActionPreference = "Stop"
$clientDir = $PSScriptRoot
$repoDir = Split-Path $clientDir -Parent
$jniLibsDir = Join-Path $clientDir "app\src\main\jniLibs"
$abiDir = Join-Path $jniLibsDir "arm64-v8a"

if (-not $env:ANDROID_NDK_HOME) {
    $sdkDir = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
    $ndkDirs = Get-ChildItem (Join-Path $sdkDir "ndk") -Directory | Sort-Object Name -Descending
    if (-not $ndkDirs) { throw "No NDK found under $sdkDir\ndk, install one with the SDK Manager" }
    $env:ANDROID_NDK_HOME = $ndkDirs[0].FullName
}
Write-Host "Using NDK: $env:ANDROID_NDK_HOME"

New-Item -ItemType Directory -Force $abiDir | Out-Null

$loaderPath = Join-Path $abiDir "libopenxr_loader.so"
if (-not (Test-Path $loaderPath)) {
    $downloadDir = Join-Path $clientDir "build\openxr_loader"
    New-Item -ItemType Directory -Force $downloadDir | Out-Null
    $zipPath = Join-Path $downloadDir "openxr_loader.zip"
    $url = "https://github.com/KhronosGroup/OpenXR-SDK-Source/releases/download/release-$OpenXrVersion/openxr_loader_for_android-$OpenXrVersion.aar"

    Write-Host "Downloading OpenXR loader $OpenXrVersion ..."
    Invoke-WebRequest -Uri $url -OutFile $zipPath
    Expand-Archive -Path $zipPath -DestinationPath $downloadDir -Force
    Copy-Item (Join-Path $downloadDir "prefab\modules\openxr_loader\libs\android.arm64-v8a\libopenxr_loader.so") $loaderPath
}

Push-Location $repoDir
try {
    if ($Profile -eq "release") {
        & cargo ndk -t arm64-v8a -o $jniLibsDir build -p ftbridge_core --release
    } else {
        & cargo ndk -t arm64-v8a -o $jniLibsDir build -p ftbridge_core
    }
    if ($LASTEXITCODE -ne 0) { throw "cargo ndk failed" }
} finally {
    Pop-Location
}

Write-Host "Native libraries staged in $abiDir"
