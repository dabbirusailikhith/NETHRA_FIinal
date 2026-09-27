<#
.SYNOPSIS
  Copies an optional Gemma .litertlm model to the phone for NETHRA's on-device publish kit.

.DESCRIPTION
  Pushes to /sdcard/Android/data/com.nethra.app/files/model/ (the app's own folder).
  The app must be installed. It is opened once first so Android creates that folder.
  See model/README.md.

.EXAMPLE
  .\scripts\push-model.ps1 -ModelPath "C:\Downloads\gemma-model.litertlm"
#>
param(
    [Parameter(Mandatory = $true)][string]$ModelPath
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\adb-common.ps1"

$pkg = 'com.nethra.app'
$dest = "/sdcard/Android/data/$pkg/files/model/"

if (-not (Test-Path $ModelPath -PathType Leaf)) { throw "File not found: $ModelPath" }
$file = Get-Item $ModelPath
if ($file.Extension -ne '.litertlm') {
    throw "NETHRA only loads LiteRT-LM '.litertlm' files (got '$($file.Extension)'). See model/README.md."
}
$gb = [math]::Round($file.Length / 1GB, 2)

$adb = Find-Adb
Assert-OneDevice $adb

$installed = & $adb shell pm list packages $pkg
if (-not ($installed -match "package:$pkg$")) { throw "NETHRA is not installed. Run .\scripts\install-debug.ps1 first." }

# Opening the app makes Android create its external files folder with the right owner.
& $adb shell am start -n "$pkg/.MainActivity" | Out-Null
Start-Sleep -Seconds 3
& $adb shell mkdir -p $dest | Out-Null

# Check free space on the phone's shared storage (df -k: 1K blocks, 'Available' column).
$df = (& $adb shell df -k /sdcard | Select-Object -Last 1) -split '\s+'
$availKb = 0L
if ($df.Count -ge 4 -and [long]::TryParse($df[3], [ref]$availKb)) {
    $freeGb = [math]::Round($availKb / 1MB, 1)
    if ($availKb * 1KB -lt $file.Length + 500MB) { throw "Not enough space on the phone: $freeGb GB free, model is $gb GB." }
}

Write-Host "Pushing $($file.Name) ($gb GB) to $dest ... this can take several minutes."
& $adb push $file.FullName $dest
if ($LASTEXITCODE -ne 0) {
    throw "adb push failed. Some Android builds block adb from writing to Android/data. Use the in-app 'Import Gemma model' button instead (model/README.md, option A)."
}

$listing = & $adb shell ls -l "$dest$($file.Name)"
Write-Host $listing
Write-Host "Done. In NETHRA the home screen should now show 'Installed: $($file.Name)'. Restart the app if it was open." -ForegroundColor Green
