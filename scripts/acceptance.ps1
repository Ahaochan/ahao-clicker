[CmdletBinding()]
param([string]$Serial = 'emulator-5554', [ValidatePattern('^[a-z_]*$')][string]$Case = '')

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if ($Serial -notmatch '^emulator-\d+$') { throw 'This acceptance script only operates on an Android emulator.' }
if (-not $env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME = Join-Path $projectRoot '.gradle-user-home' }
if ([IO.Path]::GetFullPath($env:GRADLE_USER_HOME) -match '^[Cc]:[\\/]') { throw 'Gradle cache must not be on C.' }
if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to JDK 17.' }
$java = Join-Path $env:JAVA_HOME 'bin/java.exe'
$javac = Join-Path $env:JAVA_HOME 'bin/javac.exe'
$keytool = Join-Path $env:JAVA_HOME 'bin/keytool.exe'
if ((& $java -version 2>&1 | Out-String) -notmatch 'version "17\.') { throw 'JDK 17 is required.' }
$sdk = $env:ANDROID_HOME
if (-not $sdk) {
    $line = Get-Content (Join-Path $projectRoot 'local.properties') | Where-Object { $_ -match '^sdk.dir=' } | Select-Object -First 1
    $sdk = $line.Substring(8).Replace('\:', ':').Replace('\\', '\')
}
$adb = Join-Path $sdk 'platform-tools/adb.exe'
$env:ANDROID_HOME = $sdk
$work = Join-Path $projectRoot '.tools/mvp-acceptance'
$assets = Join-Path $projectRoot 'app/build/generated/acceptance-assets'
New-Item -ItemType Directory -Force -Path $work,$assets | Out-Null
function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments)] [string[]]$AdbArgs)
    $output = & $adb -s $Serial @AdbArgs
    if ($LASTEXITCODE -ne 0) { throw "ADB failed: $($AdbArgs -join ' ')" }
    return $output
}
if ((Invoke-Adb shell getprop sys.boot_completed | Out-String).Trim() -ne '1') { throw 'Boot the selected emulator before running acceptance.' }
$deviceApi = [int](Invoke-Adb shell getprop ro.build.version.sdk | Out-String).Trim()
if ($deviceApi -lt 29) { throw 'This test harness requires an API 29+ emulator; production minSdk stays 26.' }
$previousServices = (Invoke-Adb shell settings get secure enabled_accessibility_services | Out-String).Trim()
$previousEnabled = (Invoke-Adb shell settings get secure accessibility_enabled | Out-String).Trim()
$previousRotation = (Invoke-Adb shell settings get system user_rotation | Out-String).Trim()
$previousAutoRotation = (Invoke-Adb shell settings get system accelerometer_rotation | Out-String).Trim()
$server = $null
$port = $null
Push-Location $projectRoot
try {
    $passwordFile = Join-Path $work 'tls-password.txt'
    $storeFile = Join-Path $work ('tls-' + [Guid]::NewGuid().ToString('N') + '.p12')
    # Fresh local test key, never a production credential or a checked-in signing key.
    if (-not (Test-Path $storeFile)) {
        [IO.File]::WriteAllText($passwordFile, [Guid]::NewGuid().ToString('N'))
        & $keytool -genkeypair -alias acceptance -keyalg RSA -keysize 2048 -sigalg SHA256withRSA -validity 2 -dname 'CN=localhost' -ext 'SAN=ip:127.0.0.1,dns:localhost' -storetype PKCS12 -keystore $storeFile -storepass:file $passwordFile -noprompt
        if ($LASTEXITCODE -ne 0) { throw 'Test certificate generation failed.' }
    }
    & $keytool -exportcert -alias acceptance -keystore $storeFile -storepass:file $passwordFile -file (Join-Path $assets 'acceptance.crt')
    if ($LASTEXITCODE -ne 0) { throw 'Test certificate export failed.' }
    & $javac -d $work tools/acceptance/TimeServer.java
    if ($LASTEXITCODE -ne 0) { throw 'HTTPS fixture compilation failed.' }
    $portFile = Join-Path $work 'server-port.txt'
    if (Test-Path $portFile) { Remove-Item -LiteralPath $portFile }
    $serverArguments = @('-cp', ('"' + $work + '"'), 'TimeServer', ('"' + $storeFile + '"'), ('"' + $passwordFile + '"'), ('"' + $portFile + '"'))
    $server = Start-Process -FilePath $java -ArgumentList $serverArguments -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $work 'time-server.log') -RedirectStandardError (Join-Path $work 'time-server-error.log')
    for ($i = 0; $i -lt 60 -and -not (Test-Path $portFile); $i++) {
        if ($server.HasExited) { throw 'Local HTTPS server exited unexpectedly.' }
        Start-Sleep -Milliseconds 100
    }
    $port = [int](Get-Content $portFile)
    Invoke-Adb reverse "tcp:$port" "tcp:$port"
    & ./gradlew.bat -Pacceptance=true :app:assembleDebug :app:assembleDebugAndroidTest :acceptance-target:assembleDebug --offline --no-watch-fs --quiet
    if ($LASTEXITCODE -ne 0) { throw 'Acceptance APK build failed.' }
    Invoke-Adb shell am force-stop com.ahao.clicker
    Invoke-Adb shell am force-stop com.ahao.clicker.acceptance.target
    Invoke-Adb install -r -t app/build/outputs/apk/debug/app-debug.apk
    $targetInstall = @('install','-r','-t')
    if ($deviceApi -ge 30) { $targetInstall += '--force-queryable' }
    $targetInstall += 'tools/acceptance-target/build/outputs/apk/debug/acceptance-target-debug.apk'
    Invoke-Adb @targetInstall
    Invoke-Adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
    Invoke-Adb shell run-as com.ahao.clicker rm -f files/mvp-acceptance.json files/mvp-death-checkpoint.json
    Invoke-Adb shell input keyevent KEYCODE_WAKEUP
    Invoke-Adb shell wm dismiss-keyguard
    $runnerArguments = @('shell','am','instrument','-w','-r','-e','timePort',"$port")
    if ($Case) { $runnerArguments += @('-e','only',$Case) }
    $runnerArguments += 'com.ahao.clicker.test/com.ahao.clicker.acceptance.MvpAcceptance'
    & $adb -s $Serial @runnerArguments 2>&1 | Tee-Object -FilePath (Join-Path $work 'instrumentation.txt')
    if ($Case -eq 'process_death') {
        $checkpoint = (Invoke-Adb shell run-as com.ahao.clicker cat files/mvp-death-checkpoint.json | Out-String) | ConvertFrom-Json
        if ($checkpoint.receivedBeforeDeath -lt 2) { throw 'The process-death task never received clicks.' }
        Start-Sleep -Milliseconds 350
        $before = Invoke-Adb shell content call --uri content://com.ahao.clicker.acceptance.target --method state | Out-String
        if ($before -notmatch 'count=(\d+)') { throw 'Missing target counter after process death.' }
        $received = [int]$Matches[1]
        Invoke-Adb shell am start -n com.ahao.clicker/.MainActivity
        Start-Sleep -Seconds 3
        $newPid = (Invoke-Adb shell pidof com.ahao.clicker | Out-String).Trim()
        $after = Invoke-Adb shell content call --uri content://com.ahao.clicker.acceptance.target --method state | Out-String
        if (-not $newPid -or $newPid -eq "$($checkpoint.oldPid)" -or $after -notmatch 'count=(\d+)' -or [int]$Matches[1] -ne $received) {
            throw 'Process recreation resumed clicks or did not create a new process.'
        }
        @{ passed=$true; oldPid=$checkpoint.oldPid; newPid=$newPid; receivedBeforeDeath=$checkpoint.receivedBeforeDeath; stableReceivedAfterDeath=$received; observationMillis=3000 } |
            ConvertTo-Json | Set-Content -LiteralPath (Join-Path $work 'process-recreation.json')
        return
    }
    $report = Invoke-Adb shell run-as com.ahao.clicker cat files/mvp-acceptance.json | Out-String
    [IO.File]::WriteAllText((Join-Path $work 'result.json'), $report)
    if (-not ($report | ConvertFrom-Json).passed) { throw 'Device acceptance failed; inspect .tools/mvp-acceptance/result.json.' }
} finally {
    if ($port) { & $adb -s $Serial reverse --remove "tcp:$port" | Out-Null }
    if ($server -and -not $server.HasExited) { Stop-Process -Id $server.Id }
    if ($storeFile -and (Test-Path -LiteralPath $storeFile)) { Remove-Item -LiteralPath $storeFile }
    if ($passwordFile -and (Test-Path -LiteralPath $passwordFile)) { Remove-Item -LiteralPath $passwordFile }
    foreach ($setting in @(@('secure','enabled_accessibility_services',$previousServices), @('secure','accessibility_enabled',$previousEnabled), @('system','user_rotation',$previousRotation), @('system','accelerometer_rotation',$previousAutoRotation))) {
        if ($setting[2] -eq 'null' -or $setting[2] -eq '') { & $adb -s $Serial shell settings delete $setting[0] $setting[1] | Out-Null }
        else { & $adb -s $Serial shell settings put $setting[0] $setting[1] $setting[2] | Out-Null }
    }
    & $adb -s $Serial shell input keyevent KEYCODE_WAKEUP | Out-Null
    & $adb -s $Serial shell wm dismiss-keyguard | Out-Null
    & $adb -s $Serial shell am start -n com.ahao.clicker/.MainActivity | Out-Null
    Pop-Location
}
