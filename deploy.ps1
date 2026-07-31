<#
Builds the debug APK and installs/launches it on a connected device.

Usage:
  .\deploy.ps1              # build + install + launch on the only attached device
  .\deploy.ps1 -Serial ABC123  # target a specific device (see `adb devices -l`)
  .\deploy.ps1 -SkipBuild   # reinstall/launch the existing APK without rebuilding
#>
param(
    [string]$Serial,
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"

function Resolve-Adb {
    # Only match real executables here (not aliases/functions named "adb"),
    # since those command types don't reliably expose a usable .Source path.
    $onPath = Get-Command adb -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($onPath -and $onPath.Source) {
        return $onPath.Source
    }

    $localProps = Join-Path $PSScriptRoot "local.properties"
    if (-not (Test-Path $localProps)) {
        throw "adb not found on PATH and local.properties is missing; can't locate sdk.dir."
    }

    $sdkLine = Get-Content $localProps | Where-Object { $_ -match '^sdk\.dir=' }
    if (-not $sdkLine) {
        throw "adb not found on PATH and no sdk.dir entry in local.properties."
    }

    $sdkDir = ($sdkLine -replace '^sdk\.dir=', '').Trim()
    $adbPath = Join-Path $sdkDir "platform-tools\adb.exe"
    if (-not (Test-Path $adbPath)) {
        throw "adb.exe not found at expected path: $adbPath"
    }

    return $adbPath
}

$adb = Resolve-Adb
if ([string]::IsNullOrWhiteSpace($adb)) {
    throw "Failed to resolve a usable adb path."
}
Write-Host "Using adb: $adb"

$adbArgs = @()
if ($Serial) {
    $adbArgs += @("-s", $Serial)
}

if (-not $SkipBuild) {
    Write-Host "Building debug APK..."
    & (Join-Path $PSScriptRoot "gradlew.bat") assembleDebug
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle build failed with exit code $LASTEXITCODE"
    }
}

$apkPath = Join-Path $PSScriptRoot "app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path $apkPath)) {
    throw "APK not found at $apkPath (build may have failed or -SkipBuild was passed before any build ran)."
}

Write-Host "Installing APK..."
& $adb @adbArgs install -r $apkPath
if ($LASTEXITCODE -ne 0) {
    throw "adb install failed with exit code $LASTEXITCODE"
}

Write-Host "Launching app..."
& $adb @adbArgs shell am start -n com.schmodcast/.MainActivity
if ($LASTEXITCODE -ne 0) {
    throw "adb shell am start failed with exit code $LASTEXITCODE"
}

Write-Host "Done."
