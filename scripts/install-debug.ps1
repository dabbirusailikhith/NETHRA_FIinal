<#
.SYNOPSIS
  Installs the NETHRA debug APK on a USB-connected phone with ADB, then opens the app.

.EXAMPLE
  .\scripts\install-debug.ps1
  .\scripts\install-debug.ps1 -Build        # build first
  .\scripts\install-debug.ps1 -NoLaunch
#>
param(
    [switch]$Build,
    [switch]$NoLaunch
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\adb-common.ps1"
$root = Split-Path -Parent $PSScriptRoot

if ($Build) { & "$PSScriptRoot\build-debug.ps1" }

$apk = Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk'
if (-not (Test-Path $apk)) { throw "APK not found at $apk. Run .\scripts\build-debug.ps1 first." }

$adb = Find-Adb
Write-Host "Using adb: $adb"
Assert-OneDevice $adb

$model = (& $adb shell getprop ro.product.model).Trim()
$sdk = (& $adb shell getprop ro.build.version.sdk).Trim()
Write-Host "Device: $model (API $sdk)"
if ([int]$sdk -lt 31) { throw "NETHRA needs Android 12 (API 31) or newer." }

Write-Host "Installing $apk ..."
# -r keeps app data (drafts, imported Gemma model) when updating.
& $adb install -r $apk
if ($LASTEXITCODE -ne 0) {
    throw "Install failed. If it says INSTALL_FAILED_UPDATE_INCOMPATIBLE, run: adb uninstall com.nethra.app (this deletes the imported model and drafts). On iQOO/vivo phones also enable 'Install via USB' in Developer options."
}

if (-not $NoLaunch) {
    & $adb shell am start -n com.nethra.app/.MainActivity | Out-Null
    Write-Host "NETHRA launched." -ForegroundColor Green
}
