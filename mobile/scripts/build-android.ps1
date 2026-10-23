param([switch]$SkipWeb, [switch]$Install)
$ErrorActionPreference = "Stop"
$mobileRoot = Split-Path -Parent $PSScriptRoot
$toolsRoot = Join-Path $mobileRoot ".tools"
$portableJava = Get-ChildItem -Directory -Path "$toolsRoot/jdk" -ErrorAction SilentlyContinue | Select-Object -First 1
if ($portableJava) { $env:JAVA_HOME = $portableJava.FullName }
if (Test-Path "$toolsRoot/sdk") { $env:ANDROID_HOME = "$toolsRoot/sdk" }
if (!$env:JAVA_HOME -or !(Test-Path "$env:JAVA_HOME/bin/java.exe")) { throw "JDK 17 required. Install Android Studio or set JAVA_HOME for this process." }
if (!$env:ANDROID_HOME) { throw "Android SDK required. Set ANDROID_HOME for this process." }
$env:GRADLE_USER_HOME = "$mobileRoot/.gradle-home"
Push-Location $mobileRoot
try {
    if (!$SkipWeb) {
        & npm.cmd run build
        if ($LASTEXITCODE -ne 0) { throw "Web build failed" }
    }
    $sdkPath = $env:ANDROID_HOME.Replace('\', '/')
    "sdk.dir=$sdkPath" | Set-Content -LiteralPath "$mobileRoot/android/local.properties" -Encoding utf8
    Push-Location "$mobileRoot/android"
    try {
        $gradleCommand = "$mobileRoot/android/gradlew.bat"
        if (Test-Path "$toolsRoot/gradle-8.11.1/bin/gradle.bat") { $gradleCommand = "$toolsRoot/gradle-8.11.1/bin/gradle.bat" }
        & $gradleCommand --no-daemon assembleDebug testDebugUnitTest lintDebug
        if ($LASTEXITCODE -ne 0) { throw "Android build or checks failed" }
    } finally { Pop-Location }
    New-Item -ItemType Directory -Force -Path "$mobileRoot/artifacts" | Out-Null
    Copy-Item -LiteralPath "$mobileRoot/android/app/build/outputs/apk/debug/app-debug.apk" -Destination "$mobileRoot/artifacts/shiping-android-1.1.2-debug.apk"
    if ($Install) {
        & "$env:ANDROID_HOME/platform-tools/adb.exe" install -r "$mobileRoot/artifacts/shiping-android-1.1.2-debug.apk"
        if ($LASTEXITCODE -ne 0) { throw "APK install failed" }
    }
    Write-Output "APK: $mobileRoot/artifacts/shiping-android-1.1.2-debug.apk"
} finally { Pop-Location }
