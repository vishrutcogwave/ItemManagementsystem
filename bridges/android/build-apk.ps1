$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$android = Join-Path $root 'android'
if (-not $env:ANDROID_HOME) {
    $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
}

Push-Location $android
try {
    & .\gradlew.bat assembleRelease
    if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed.' }

    $apk = Join-Path $android 'app\build\outputs\apk\release\app-release.apk'
    if (-not (Test-Path $apk)) { throw "APK not found at $apk." }
    New-Item -ItemType Directory -Force -Path (Join-Path $android 'dist') | Out-Null
    Copy-Item $apk (Join-Path $android 'dist\ItemManagementPrintBridge.apk') -Force

    $apksigner = Join-Path $env:ANDROID_HOME 'build-tools\35.0.1\apksigner.bat'
    if (Test-Path $apksigner) {
        & $apksigner verify --print-certs (Join-Path $android 'dist\ItemManagementPrintBridge.apk')
    }
    Write-Host 'APK ready: bridges\android\dist\ItemManagementPrintBridge.apk'
}
finally {
    Pop-Location
}
