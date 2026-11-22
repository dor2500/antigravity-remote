# Builds the Android app on Windows and copies it to dist\AntigravityRemote-latest.apk.
# Signs with android\keystore.properties when present, otherwise with the debug key.
param(
    [switch]$Install
)

$ErrorActionPreference = "Stop"
$RootDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$AndroidDir = Join-Path $RootDir "android"
$DistDir = Join-Path $RootDir "dist"

function Test-Jdk([string]$JdkPath) {
    if (-not $JdkPath -or -not (Test-Path (Join-Path $JdkPath "bin\java.exe"))) { return $false }
    $version = (& cmd.exe /c "`"$(Join-Path $JdkPath 'bin\java.exe')`" -version 2>&1" | Select-Object -First 1)
    return "$version" -match '"(17|21)\.'
}

if (-not (Test-Jdk $env:JAVA_HOME)) {
    $candidates = @()
    $candidates += Get-ChildItem "C:\Program Files\Microsoft\jdk-17*", "C:\Program Files\Eclipse Adoptium\jdk-17*", "C:\Program Files\Java\jdk-17*" -Directory -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName }
    $candidates += "C:\Program Files\Android\Android Studio\jbr"
    $found = $candidates | Where-Object { Test-Jdk $_ } | Select-Object -First 1
    if (-not $found) {
        Write-Host "[build] JDK 17 not found. Install it with: winget install Microsoft.OpenJDK.17" -ForegroundColor Red
        exit 1
    }
    $env:JAVA_HOME = $found
}

if (-not $env:ANDROID_HOME) {
    $sdk = @("$env:LOCALAPPDATA\Android\Sdk", "C:\Android\Sdk") | Where-Object { Test-Path $_ } | Select-Object -First 1
    if (-not $sdk) {
        Write-Host "[build] Android SDK not found. Install Android Studio or set ANDROID_HOME." -ForegroundColor Red
        exit 1
    }
    $env:ANDROID_HOME = $sdk
}

$localProperties = Join-Path $AndroidDir "local.properties"
if (-not (Test-Path $localProperties)) {
    "sdk.dir=$($env:ANDROID_HOME.Replace('\', '/'))" | Set-Content -Encoding ASCII $localProperties
}

Write-Host "[build] JAVA_HOME=$env:JAVA_HOME" -ForegroundColor Cyan
Write-Host "[build] ANDROID_HOME=$env:ANDROID_HOME" -ForegroundColor Cyan
if (-not (Test-Path (Join-Path $AndroidDir "keystore.properties")) -and -not $env:ANDROID_KEYSTORE_PATH) {
    Write-Host "[build] No keystore.properties found: the APK will be signed with the debug key." -ForegroundColor Yellow
}

Push-Location $AndroidDir
try {
    & .\gradlew.bat assembleRelease --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed" }
} finally {
    Pop-Location
}

$apk = Join-Path $AndroidDir "app\build\outputs\apk\release\app-release.apk"
New-Item -ItemType Directory -Force -Path $DistDir | Out-Null
$output = Join-Path $DistDir "AntigravityRemote-latest.apk"
Copy-Item -Force $apk $output
$sizeMb = [math]::Round((Get-Item $output).Length / 1MB, 1)
Write-Host "[build] APK ready: $output ($sizeMb MB)" -ForegroundColor Green

if ($Install) {
    $adb = Join-Path $env:ANDROID_HOME "platform-tools\adb.exe"
    & $adb install -r $output
}
