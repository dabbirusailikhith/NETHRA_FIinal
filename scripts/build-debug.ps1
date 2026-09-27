<#
.SYNOPSIS
  Builds the NETHRA debug APK (and optionally runs the JVM unit tests).

.EXAMPLE
  .\scripts\build-debug.ps1
  .\scripts\build-debug.ps1 -Test
  .\scripts\build-debug.ps1 -Clean -Test
#>
param(
    [switch]$Test,
    [switch]$Clean
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

# ---- JDK: use JAVA_HOME if set, otherwise Android Studio's bundled JBR, otherwise a JDK 21/17 install.
if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    $candidates = @(
        "$env:ProgramFiles\Android\Android Studio\jbr",
        "$env:ProgramFiles\Java\jdk-21",
        "$env:ProgramFiles\Java\jdk-17",
        "$env:LOCALAPPDATA\Programs\Android Studio\jbr"
    )
    $jdk = $candidates | Where-Object { Test-Path (Join-Path $_ 'bin\java.exe') } | Select-Object -First 1
    if (-not $jdk) { throw "No JDK found. Install Android Studio (it bundles one) or JDK 17+, or set JAVA_HOME." }
    $env:JAVA_HOME = $jdk
}
Write-Host "Using JDK: $env:JAVA_HOME"

# ---- local.properties: check the SDK path and whether a key is set. Never print the key.
$props = Join-Path $root 'local.properties'
if (-not (Test-Path $props)) {
    throw "local.properties is missing. Open the project once in Android Studio (it writes sdk.dir), then add OPENROUTER_API_KEY=... (SETUP_GUIDE.md section 4)."
}
$keyLine = Get-Content $props | Where-Object { $_ -match '^\s*OPENROUTER_API_KEY\s*[=:]' } | Select-Object -First 1
$hasKey = $keyLine -and (($keyLine -replace '^\s*OPENROUTER_API_KEY\s*[=:]\s*', '').Trim().Length -gt 0)
if ($hasKey) {
    Write-Host "OpenRouter key: set in local.properties (value not shown)."
} else {
    Write-Warning "OPENROUTER_API_KEY is empty. The app will build, but scripts, transcripts and cloud publish kits will show a 'missing key' error."
}

# ---- Build
$tasks = @()
if ($Clean) { $tasks += 'clean' }
$tasks += 'assembleDebug'
if ($Test) { $tasks += 'testDebugUnitTest' }

& .\gradlew.bat @tasks --console=plain
if ($LASTEXITCODE -ne 0) { throw "Gradle failed (exit $LASTEXITCODE). See the output above and SETUP_GUIDE.md section 9." }

$apk = Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk'
if (-not (Test-Path $apk)) { throw "Build reported success but $apk is missing." }
$mb = [math]::Round((Get-Item $apk).Length / 1MB, 1)
Write-Host ""
Write-Host "APK: $apk ($mb MB)" -ForegroundColor Green
if ($Test) { Write-Host "Test report: $(Join-Path $root 'app\build\reports\tests\testDebugUnitTest\index.html')" }
if ($hasKey) { Write-Warning "This APK contains your (obfuscated, not encrypted) OpenRouter key. Do not share it." }
Write-Host "Install: .\scripts\install-debug.ps1"
