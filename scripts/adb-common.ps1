# Shared helpers for the install / push scripts. Dot-source it: . "$PSScriptRoot\adb-common.ps1"

function Find-Adb {
    $root = Split-Path -Parent $PSScriptRoot
    $sdkDirs = @()
    $props = Join-Path $root 'local.properties'
    if (Test-Path $props) {
        $line = Get-Content $props | Where-Object { $_ -match '^\s*sdk\.dir\s*=' } | Select-Object -First 1
        if ($line) {
            # local.properties escapes ':' and '\' (C\:\\Users\\...).
            $sdkDirs += (($line -replace '^\s*sdk\.dir\s*=\s*', '') -replace '\\:', ':' -replace '\\\\', '\').Trim()
        }
    }
    if ($env:ANDROID_HOME) { $sdkDirs += $env:ANDROID_HOME }
    if ($env:ANDROID_SDK_ROOT) { $sdkDirs += $env:ANDROID_SDK_ROOT }
    $sdkDirs += "$env:LOCALAPPDATA\Android\Sdk"

    foreach ($d in $sdkDirs) {
        $adb = Join-Path $d 'platform-tools\adb.exe'
        if (Test-Path $adb) { return $adb }
    }
    $onPath = Get-Command adb -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }
    throw "adb not found. Install 'Android SDK Platform-Tools' from Android Studio's SDK Manager."
}

function Assert-OneDevice([string]$adb) {
    $lines = & $adb devices | Select-Object -Skip 1 | Where-Object { $_.Trim() }
    $ready = @($lines | Where-Object { $_ -match "\tdevice$" })
    $unauth = @($lines | Where-Object { $_ -match "\tunauthorized$" })
    if ($unauth.Count -gt 0) { throw "Phone is 'unauthorized': unlock it and accept the USB debugging prompt, then run again." }
    if ($ready.Count -eq 0) { throw "No device found. Connect the phone by USB with USB debugging on (SETUP_GUIDE.md section 6)." }
    if ($ready.Count -gt 1 -and -not $env:ANDROID_SERIAL) {
        throw "More than one device is connected. Set `$env:ANDROID_SERIAL to one of: $(($ready | ForEach-Object { ($_ -split "`t")[0] }) -join ', ')"
    }
}
