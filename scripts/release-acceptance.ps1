[CmdletBinding()]
param([Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9]+$')][string]$Serial)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$homeLayout = Get-Content -LiteralPath (Join-Path $projectRoot 'app/src/main/res/layout/activity_main.xml') -Raw
if ($homeLayout -notmatch '@\+id/mode_classic') {
    throw 'The legacy multi-point release benchmark requires the removed classic form. No device changes were made. See docs/PERFORMANCE.md.'
}
if (-not $env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME = Join-Path $projectRoot '.gradle-user-home' }
if ([IO.Path]::GetFullPath($env:GRADLE_USER_HOME) -match '^[Cc]:[\\/]') { throw 'Gradle cache must not use C.' }
if (-not $env:JAVA_HOME -or ((& (Join-Path $env:JAVA_HOME 'bin/java.exe') -version 2>&1 | Out-String) -notmatch 'version "17\.')) { throw 'Set JAVA_HOME to JDK 17.' }
$sdk = $env:ANDROID_HOME
if (-not $sdk) {
    $sdkLine = Get-Content (Join-Path $projectRoot 'local.properties') | Where-Object { $_ -match '^sdk.dir=' } | Select-Object -First 1
    $sdk = $sdkLine.Substring(8).Replace('\:', ':').Replace('\\', '\')
}
$adb = Join-Path $sdk 'platform-tools/adb.exe'
$work = Join-Path $projectRoot '.tools/release-acceptance'
New-Item -ItemType Directory -Force $work | Out-Null
function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments)][string[]]$AdbArgs)
    $result = & $adb -s $Serial @AdbArgs 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0 -or $result -match 'SecurityException|Permission denial') { throw "ADB failed: $result" }
    $result.Trim()
}
function Save-AdbFile([string]$Package, [string]$RemotePath, [string]$Destination) {
    $info = [Diagnostics.ProcessStartInfo]::new($adb)
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    # Arguments here are fixed package/file names and the validated alphanumeric serial.
    $info.Arguments = "-s $Serial exec-out run-as $Package cat $RemotePath"
    $process = [Diagnostics.Process]::Start($info)
    try {
        $file = [IO.File]::Create($Destination)
        try { $process.StandardOutput.BaseStream.CopyTo($file) } finally { $file.Dispose() }
        $process.WaitForExit()
        if ($process.ExitCode -ne 0) { throw 'Could not back up/read the app-owned file. Start the Debug app once before running this test.' }
    } finally { $process.Dispose() }
}
$component = 'com.ahao.clicker/com.ahao.clicker.ClickService'
$services = Invoke-Adb shell settings get secure enabled_accessibility_services
if ($component -notin $services.Split(':')) { throw 'Enable the clicker accessibility service first.' }
if ((Invoke-Adb shell getprop ro.kernel.qemu) -eq '1') { throw 'Use a physical device for this Release acceptance.' }
function Reconnect-Clicker {
    try {
        $others = ($services.Split(':') | Where-Object { $_ -ne $component }) -join ':'
        if ($others) { Invoke-Adb shell settings put secure enabled_accessibility_services $others | Out-Null }
        else { Invoke-Adb shell settings delete secure enabled_accessibility_services | Out-Null }
        Start-Sleep -Milliseconds 500
    } finally { Invoke-Adb shell settings put secure enabled_accessibility_services $services | Out-Null }
    Start-Sleep -Seconds 1
}
$savedPreferences = Join-Path $work 'saved-preferences.xml'
Save-AdbFile 'com.ahao.clicker' 'shared_prefs/clicker.xml' $savedPreferences
$targetInstalled = [bool](Invoke-Adb shell pm path com.ahao.clicker.acceptance.target)
$changedApp = $false
Push-Location $projectRoot
try {
    & ./gradlew.bat -Pacceptance=true :app:assembleDebug :app:assembleRelease :acceptance-target:assembleDebug --offline --no-watch-fs --quiet
    if ($LASTEXITCODE -ne 0) { throw 'Build failed.' }
    $signed = Join-Path $work 'release-test.apk'
    & (Join-Path $sdk 'build-tools/36.0.0/apksigner.bat') sign --ks (Join-Path $env:USERPROFILE '.android/debug.keystore') --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out $signed app/build/outputs/apk/release/app-release-unsigned.apk
    if ($LASTEXITCODE -ne 0) { throw 'Local test signing failed.' }
    $changedApp = $true
    Invoke-Adb install -r $signed | Out-Host
    Invoke-Adb install -r -t --force-queryable tools/acceptance-target/build/outputs/apk/debug/acceptance-target-debug.apk | Out-Host
    if ((Invoke-Adb shell dumpsys package com.ahao.clicker) -match 'flags=\[\s*DEBUGGABLE') { throw 'Expected a non-debuggable optimized app.' }
    Invoke-Adb shell am start -n com.ahao.clicker/.MainActivity | Out-Null
    Reconnect-Clicker
    Invoke-Adb -AdbArgs @('shell','am','instrument','-w','-r','com.ahao.clicker.acceptance.target/.ReleaseAcceptance') | Set-Content -Encoding utf8 (Join-Path $work 'instrumentation.txt')
    Save-AdbFile 'com.ahao.clicker.acceptance.target' 'files/release-acceptance.json' (Join-Path $work 'result.json')
    $report = Get-Content (Join-Path $work 'result.json') -Raw | ConvertFrom-Json
    if (-not $report.passed) { throw "Release UI acceptance failed; see $work/result.json" }
    Write-Host "Release acceptance passed: $($report.count) real target receipts."
} finally {
    if ($changedApp) {
        Invoke-Adb shell am force-stop com.ahao.clicker | Out-Null
        Invoke-Adb install -r -t app/build/outputs/apk/debug/app-debug.apk | Out-Null
        Invoke-Adb shell am force-stop com.ahao.clicker | Out-Null
        Invoke-Adb push $savedPreferences /data/local/tmp/clicker-saved-preferences.xml | Out-Null
        try {
            Invoke-Adb shell chmod 644 /data/local/tmp/clicker-saved-preferences.xml | Out-Null
            Invoke-Adb shell run-as com.ahao.clicker cp /data/local/tmp/clicker-saved-preferences.xml shared_prefs/clicker.xml | Out-Null
        } finally { Invoke-Adb shell rm -f /data/local/tmp/clicker-saved-preferences.xml | Out-Null }
        Reconnect-Clicker
        Invoke-Adb shell am start -n com.ahao.clicker/.MainActivity | Out-Null
    }
    if (-not $targetInstalled) { Invoke-Adb uninstall com.ahao.clicker.acceptance.target | Out-Null }
    Pop-Location
}
