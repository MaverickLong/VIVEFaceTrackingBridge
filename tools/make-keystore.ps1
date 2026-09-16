# Creates a release signing keystore and prints the GitHub Actions secrets to set so that
# CI builds are signed consistently (otherwise every build gets a new debug key and the
# headset requires a reinstall on each update).
#
#   tools\make-keystore.ps1 [-Path ftbridge-release.keystore] [-Alias ftbridge]

param(
    [string]$Path = "ftbridge-release.keystore",
    [string]$Alias = "ftbridge"
)

$ErrorActionPreference = "Stop"

$keytool = "keytool"
if ($env:JAVA_HOME) {
    $keytool = Join-Path $env:JAVA_HOME "bin/keytool"
}
if (Test-Path $Path) {
    throw "$Path already exists"
}

$password = Read-Host "Choose a keystore password (also used for the key)" -AsSecureString
$plain = [Runtime.InteropServices.Marshal]::PtrToStringAuto(
    [Runtime.InteropServices.Marshal]::SecureStringToBSTR($password))

& $keytool -genkeypair -v -keystore $Path -alias $Alias -keyalg RSA -keysize 2048 -validity 10000 `
    -storepass $plain -keypass $plain -dname "CN=FT Bridge, O=FT Bridge"
if ($LASTEXITCODE -ne 0) {
    throw "keytool failed"
}

$base64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes((Resolve-Path $Path)))

Write-Host ""
Write-Host "Keystore written to $Path. Keep it and the password safe; never commit it."
Write-Host "Set these repository secrets (Settings > Secrets and variables > Actions):"
Write-Host "  ANDROID_KEYSTORE_BASE64   = <contents of $Path.base64>"
Write-Host "  ANDROID_KEYSTORE_PASSWORD = <the password>"
Write-Host "  ANDROID_KEY_ALIAS         = $Alias"
Write-Host "  ANDROID_KEY_PASSWORD      = <the password>"
Set-Content -Path "$Path.base64" -Value $base64 -Encoding ascii
Write-Host "The base64 text was saved to $Path.base64 for copy-pasting; delete it afterwards."
