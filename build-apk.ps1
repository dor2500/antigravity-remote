# Local Android APK build script for Windows
$ErrorActionPreference = "Stop"
$RootDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$AndroidDir = Join-Path $RootDir "android"
$DistDir = Join-Path $RootDir "dist"

# Auto-detect JDK 17 if JAVA_HOME is not set or invalid
if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
    $jdkCandidates = @()
    $jdkCandidates += Get-ChildItem "C:\Program Files\Microsoft\jdk-17*" -Directory -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName }
    $jdkCandidates += Get-ChildItem "C:\Program Files\Eclipse Adoptium\jdk-17*" -Directory -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName }
    $jdkCandidates += "C:\Program Files\Android\Android Studio\jbr"
    foreach ($candidate in $jdkCandidates) {
        if ($candidate -and (Test-Path (Join-Path $candidate "bin\java.exe"))) {
            $env:JAVA_HOME = $candidate
            break
        }
    }
}

if (-not $env:ANDROID_HOME -and (Test-Path "C:\Android\Sdk")) {
    $env:ANDROID_HOME = "C:\Android\Sdk"
}

if (-not $env:JAVA_HOME) {
    Write-Host "[ERROR] JAVA_HOME is not set and JDK 17 was not found." -ForegroundColor Red
    exit 1
}

if (-not $env:ANDROID_HOME) {
    Write-Host "[ERROR] ANDROID_HOME is not set and C:\Android\Sdk was not found." -ForegroundColor Red
    exit 1
}

$GradlewBat = Join-Path $AndroidDir "gradlew.bat"
if (-not (Test-Path $GradlewBat)) {
    Write-Host "[ERROR] gradlew.bat not found in $AndroidDir" -ForegroundColor Red
    exit 1
}

Write-Host "[Build] Using JAVA_HOME=$($env:JAVA_HOME)" -ForegroundColor Cyan
Write-Host "[Build] Using ANDROID_HOME=$($env:ANDROID_HOME)" -ForegroundColor Cyan

Set-Location $AndroidDir
& $GradlewBat assembleDebug --no-daemon

$ApkSource = Join-Path $AndroidDir "app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path $ApkSource)) {
    Write-Host "[ERROR] APK build output not found at $ApkSource" -ForegroundColor Red
    exit 1
}

New-Item -ItemType Directory -Force -Path $DistDir | Out-Null
$ApkDest = Join-Path $DistDir "antigravity-remote.apk"
Copy-Item -Force $ApkSource $ApkDest

Write-Host "[Build] APK ready: $ApkDest" -ForegroundColor Green
