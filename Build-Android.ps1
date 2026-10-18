param(
    [string]$JdkPath = $env:JAVA_HOME,
    [string]$SdkPath = $env:ANDROID_HOME,
    [string]$GradlePath = ''
)
$ErrorActionPreference = 'Stop'
if (-not $JdkPath -or -not (Test-Path -LiteralPath (Join-Path $JdkPath 'bin/java.exe'))) { throw '请安装 JDK 17，并提供 -JdkPath 或 JAVA_HOME。' }
if (-not $SdkPath -or -not (Test-Path -LiteralPath (Join-Path $SdkPath 'platforms/android-35/android.jar'))) { throw '请通过 Android Studio 安装 SDK Platform 35，审阅 SDK 许可后提供 -SdkPath。' }
if (-not $GradlePath) { $GradlePath = Join-Path $PSScriptRoot 'android/gradlew.bat' }
if (-not $GradlePath -or -not (Test-Path -LiteralPath $GradlePath)) { throw '请安装 Gradle 8.11.1 并提供 -GradlePath（gradle.bat 的路径）。' }
$env:JAVA_HOME = $JdkPath
$env:ANDROID_HOME = $SdkPath
$env:GRADLE_USER_HOME = Join-Path $PSScriptRoot 'prototype/runtime/gradle-home'
Push-Location (Join-Path $PSScriptRoot 'android')
try { & $GradlePath ':app:assembleDebug'; if ($LASTEXITCODE -ne 0) { throw 'Android 构建失败，请保留诊断日志。' } }
finally { Pop-Location }
Write-Output 'APK：android/app/build/outputs/apk/debug/app-debug.apk（工程调试签名）'
