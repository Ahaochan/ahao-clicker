[CmdletBinding()]
param([string]$Serial='emulator-5554', [ValidateRange(1,100)][int]$Samples=30,
    [ValidateRange(0,3600000)][long]$SoakMillis=0, [switch]$PhysicalDevice,
    [ValidateRange(0,7200000)][long]$ClockDurationMillis=0,
    [ValidateRange(1000,60000)][long]$ClockSampleMillis=30000,
    [ValidateRange(1,1000)][long]$ClockErrorLimitMillis=1000,
    [ValidateRange(500,120000)][long]$ReadinessTimeoutMillis=120000,
    [ValidateRange(0,4000)][long]$AfterPlayDelayMillis=0,
    [ValidateRange(0,4000)][long]$BeforePlayDelayMillis=150,
    [ValidateRange(500,30000)][long]$LeadMillis=1800,
    [ValidatePattern('^[a-z0-9-]+$')][string]$Label='current',
    [string]$TraceConfig='', [switch]$TraceAfterReady, [switch]$TraceAtProcessReady)

$ErrorActionPreference='Stop'
if ($ClockDurationMillis -gt 0 -and $SoakMillis -gt 0) { throw 'Choose clock observation or timed click observation.' }
if (($AfterPlayDelayMillis -gt 0 -or $BeforePlayDelayMillis -ne 150) -and ($ClockDurationMillis -gt 0 -or $PhysicalDevice)) { throw 'Timing delay diagnostics are emulator click-only.' }
$deferredTrace=$TraceAfterReady -or $TraceAtProcessReady
if ($TraceAfterReady -and $TraceAtProcessReady) { throw 'Choose a single trace barrier stage.' }
if ($deferredTrace -and (-not $TraceConfig -or $ClockDurationMillis -gt 0)) { throw 'Deferred tracing requires a click trace configuration.' }
if ($SoakMillis -gt 0 -and $Samples -ne 1) { throw 'Long-wait observation uses exactly one task.' }
if ($LeadMillis -ne 1800 -and ($ClockDurationMillis -gt 0 -or $SoakMillis -gt 0)) { throw 'LeadMillis is for short click observations only.' }
if ($PhysicalDevice) {
    if ($Serial -notmatch '^[A-Za-z0-9]+$') { throw 'Specify a physical serial.' }
} elseif ($Serial -notmatch '^emulator-\d+$') { throw 'Physical devices require -PhysicalDevice.' }
$projectRoot=Split-Path -Parent $PSScriptRoot
if (-not $env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME=Join-Path $projectRoot '.gradle-user-home' }
if ([IO.Path]::GetFullPath($env:GRADLE_USER_HOME) -match '^[Cc]:[\\/]') { throw 'Gradle cache must not be on C.' }
if (-not $env:JAVA_HOME -or ((& (Join-Path $env:JAVA_HOME 'bin/java.exe') -version 2>&1 | Out-String) -notmatch 'version "17\.')) { throw 'JDK 17 is required.' }
$sdk=$env:ANDROID_HOME
if (-not $sdk) {
    $line=Get-Content (Join-Path $projectRoot 'local.properties') | Where-Object { $_ -match '^sdk.dir=' } | Select-Object -First 1
    $sdk=$line.Substring(8).Replace('\:',':').Replace('\\','\')
}
$adb=Join-Path $sdk 'platform-tools/adb.exe'
$work=Join-Path $projectRoot ".tools/timing/$Label"
New-Item -ItemType Directory -Force $work | Out-Null
foreach ($file in @('resource-samples.jsonl','resources.txt','result.json')) {
    $path=Join-Path $work $file
    if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path }
}
function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments)][string[]]$AdbArgs)
    $output=& $adb -s $Serial @AdbArgs 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0 -or $output -match 'SecurityException|Permission denial') { throw "ADB failed: $output" }
    $output.Trim()
}
function Save-AppFile([string]$Package,[string]$File,[string]$Destination) {
    $info=[Diagnostics.ProcessStartInfo]::new($adb)
    $info.UseShellExecute=$false; $info.CreateNoWindow=$true; $info.RedirectStandardOutput=$true
    $info.Arguments="-s $Serial exec-out run-as $Package cat $File"
    $process=[Diagnostics.Process]::Start($info)
    try {
        $stream=[IO.File]::Create($Destination)
        try { $process.StandardOutput.BaseStream.CopyTo($stream) } finally { $stream.Dispose() }
        $process.WaitForExit()
        if ($process.ExitCode -ne 0) { throw 'Could not read app-owned file; start the Debug app once first.' }
    } finally { $process.Dispose() }
}
$services=Invoke-Adb shell settings get secure enabled_accessibility_services
$component='com.ahao.clicker/com.ahao.clicker.ClickService'
if ($component -notin $services.Split(':')) { throw 'Enable the clicker accessibility service first.' }
Save-AppFile 'com.ahao.clicker' 'shared_prefs/clicker.xml' (Join-Path $work 'saved-preferences.xml')
$changed=$false; $instrumentation=$null; $traceKey=$null
function Reconnect-Emulator {
    if (-not $PhysicalDevice) {
        $others=($services.Split(':') | Where-Object { $_ -ne $component }) -join ':'
        if ($others) { Invoke-Adb shell settings put secure enabled_accessibility_services $others | Out-Null }
        else { Invoke-Adb shell settings delete secure enabled_accessibility_services | Out-Null }
        try { Start-Sleep -Milliseconds 400 }
        finally { Invoke-Adb shell settings put secure enabled_accessibility_services $services | Out-Null }
    }
}
function Start-Perfetto {
    $configPath=(Resolve-Path -LiteralPath $TraceConfig).Path
    Copy-Item -LiteralPath $configPath -Destination (Join-Path $work 'trace-config.pbtxt')
    $candidateKey='ahao-'+[Guid]::NewGuid().ToString('N')
    Get-Content -LiteralPath $configPath -Raw | & $adb -s $Serial shell perfetto --txt -c - -o "/data/misc/perfetto-traces/ahao-$Label.perfetto-trace" "--detach=$candidateKey" 2>&1 |
        Set-Content -LiteralPath (Join-Path $work 'trace-start.txt')
    if ($LASTEXITCODE -ne 0) { throw 'Perfetto start failed; inspect trace-start.txt.' }
    return $candidateKey
}
Push-Location $projectRoot
try {
    & ./gradlew.bat -PtimingBenchmark=true -Pacceptance=true :app:assembleDebug :app:assembleBenchmark :acceptance-target:assembleDebug --offline --no-watch-fs --quiet
    if ($LASTEXITCODE -ne 0) { throw 'Benchmark build failed.' }
    Copy-Item -LiteralPath app/build/outputs/apk/benchmark/app-benchmark.apk -Destination (Join-Path $work 'measured.apk')
    Copy-Item -LiteralPath tools/acceptance-target/build/outputs/apk/debug/acceptance-target-debug.apk -Destination (Join-Path $work 'target.apk')
    Copy-Item -LiteralPath app/build/outputs/mapping/benchmark/mapping.txt -Destination (Join-Path $work 'mapping.txt')
    $changed=$true
    Invoke-Adb install -r (Join-Path $work 'measured.apk') | Out-Host
    Invoke-Adb install -r -t --force-queryable tools/acceptance-target/build/outputs/apk/debug/acceptance-target-debug.apk | Out-Host
    Invoke-Adb shell dumpsys package com.ahao.clicker.acceptance.target | Set-Content -LiteralPath (Join-Path $work 'target-package.txt')
    Invoke-Adb shell run-as com.ahao.clicker.acceptance.target rm -f files/timing-acceptance.json | Out-Null
    Invoke-Adb shell run-as com.ahao.clicker.acceptance.target rm -f files/trace-ready | Out-Null
    if ((Invoke-Adb shell dumpsys package com.ahao.clicker) -match '(?m)^\s*(?:pkgFlags|flags)=\[[^\r\n]*\bDEBUGGABLE\b') { throw 'Expected optimized non-debuggable APK.' }
    Invoke-Adb shell am start -n com.ahao.clicker/.MainActivity | Out-Null
    Reconnect-Emulator
    Start-Sleep -Seconds 1
    if ($TraceConfig -and -not $deferredTrace) { $traceKey=Start-Perfetto }
    $runnerArguments=@('-s',$Serial,'shell','am','instrument','-w','-r','-e','samples',"$Samples",'-e','soakMillis',"$SoakMillis",'-e','leadMillis',"$LeadMillis",'-e','readinessTimeoutMillis',"$ReadinessTimeoutMillis",'-e','afterPlayDelayMillis',"$AfterPlayDelayMillis",'-e','beforePlayDelayMillis',"$BeforePlayDelayMillis",'-e','waitForTrace',"$($deferredTrace.ToString().ToLowerInvariant())",'-e','traceBeforeTargetLaunch',"$($TraceAtProcessReady.IsPresent.ToString().ToLowerInvariant())",'com.ahao.clicker.acceptance.target/.TimingAcceptance')
    $resultName='timing-acceptance.json'
    if ($ClockDurationMillis -gt 0) {
        $resultName='clock-drift.json'
        Invoke-Adb shell run-as com.ahao.clicker.acceptance.target rm -f "files/$resultName" | Out-Null
        $runnerArguments=@('-s',$Serial,'shell','am','instrument','-w','-r','-e','durationMillis',"$ClockDurationMillis",'-e','sampleMillis',"$ClockSampleMillis",'-e','maxErrorMillis',"$ClockErrorLimitMillis",'com.ahao.clicker.acceptance.target/.ClockDriftAcceptance')
    }
    $instrumentation=Start-Process -FilePath $adb -ArgumentList $runnerArguments -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $work 'instrumentation.txt') -RedirectStandardError (Join-Path $work 'instrumentation-error.txt')
    $deadline=[DateTime]::UtcNow.AddMilliseconds(($SoakMillis+$LeadMillis+5000)*$Samples+$ClockDurationMillis+180000+$ReadinessTimeoutMillis)
    $nextSample=[DateTime]::UtcNow
    $clockTicks=[long](Invoke-Adb shell getconf CLK_TCK)
    while (-not $instrumentation.HasExited) {
        if ([DateTime]::UtcNow -gt $deadline) { throw 'Benchmark timed out.' }
        $ready=Select-String -LiteralPath (Join-Path $work 'instrumentation.txt') -Pattern 'readiness=READY|clock_sample='  -Quiet
        $traceGate=if ($TraceAtProcessReady) { Select-String -LiteralPath (Join-Path $work 'instrumentation.txt') -Pattern 'readiness=PROCESS_READY' -Quiet } else { $ready }
        if ($traceGate -and $deferredTrace -and -not $traceKey) {
            $traceKey=Start-Perfetto
            Invoke-Adb shell run-as com.ahao.clicker.acceptance.target touch files/trace-ready | Out-Null
        }
        if ($ready -and [DateTime]::UtcNow -ge $nextSample) {
            $stamp=[DateTime]::UtcNow.ToString('o')
            $memory=Invoke-Adb shell dumpsys meminfo com.ahao.clicker
            $appProcessId=(Invoke-Adb shell pidof com.ahao.clicker).Trim()
            if ($appProcessId -notmatch '^\d+$') { throw 'Expected exactly one measured app process.' }
            $stat=Invoke-Adb shell cat "/proc/$appProcessId/stat"
            $fields=($stat -replace '^\d+ \(.*\) ','') -split '\s+'
            $uptime=Invoke-Adb shell cat /proc/uptime
            if ($memory -notmatch 'TOTAL PSS:\s*(\d+)') { throw 'Missing process PSS.' }
            $sample=@{utc=$stamp;pid=$appProcessId;startTicks=[long]$fields[19];cpuTicks=([long]$fields[11]+[long]$fields[12]);clockTicks=$clockTicks;elapsedSeconds=[double]::Parse(($uptime -split '\s+')[0],[Globalization.CultureInfo]::InvariantCulture);pssKiB=[long]$Matches[1]}
            [IO.File]::AppendAllText((Join-Path $work 'resource-samples.jsonl'),($sample | ConvertTo-Json -Compress)+"`n")
            [IO.File]::AppendAllText((Join-Path $work 'resources.txt'),"$stamp`n$memory`n")
            $nextSample=[DateTime]::UtcNow.AddSeconds(30)
        }
        Start-Sleep -Milliseconds 500
    }
    Save-AppFile 'com.ahao.clicker.acceptance.target' "files/$resultName" (Join-Path $work 'result.json')
    $result=Get-Content (Join-Path $work 'result.json') -Raw | ConvertFrom-Json
    if (-not $result.passed) { throw "Timing acceptance failed; inspect $work/result.json" }
    if ($ClockDurationMillis -gt 0) {
        foreach ($image in @('clock-start.png','clock-end.png')) {
            Save-AppFile 'com.ahao.clicker.acceptance.target' "files/$image" (Join-Path $work $image)
        }
        Write-Host "Clock observation passed: $($result.validSamples) independent comparisons."
    } else { Write-Host "Timing acceptance passed: $($result.samples.Count) actual receipts." }
} finally {
    if ($instrumentation -and -not $instrumentation.HasExited) { Invoke-Adb shell am force-stop com.ahao.clicker.acceptance.target | Out-Null }
    if ($traceKey) {
        # Stop only this uniquely named session. Keep raw traces even when strict timing fails.
        $tracePulled=$false
        try {
            & $adb -s $Serial shell perfetto "--attach=$traceKey" --stop 2>&1 |
                Set-Content -LiteralPath (Join-Path $work 'trace-stop.txt')
            Invoke-Adb pull "/data/misc/perfetto-traces/ahao-$Label.perfetto-trace" (Join-Path $work 'capture.perfetto-trace') | Out-Null
            $tracePulled=$true
        } catch { Write-Warning "Trace collection failed: $_" }
        finally {
            if ($tracePulled) { & $adb -s $Serial shell rm -f "/data/misc/perfetto-traces/ahao-$Label.perfetto-trace" | Out-Null }
        }
    }
    if ($changed) {
        Invoke-Adb shell am force-stop com.ahao.clicker | Out-Null
        Invoke-Adb install -r -t app/build/outputs/apk/debug/app-debug.apk | Out-Null
        Invoke-Adb shell am force-stop com.ahao.clicker | Out-Null
        Invoke-Adb push (Join-Path $work 'saved-preferences.xml') /data/local/tmp/clicker-timing-preferences.xml | Out-Null
        try {
            Invoke-Adb shell chmod 644 /data/local/tmp/clicker-timing-preferences.xml | Out-Null
            Invoke-Adb shell run-as com.ahao.clicker cp /data/local/tmp/clicker-timing-preferences.xml shared_prefs/clicker.xml | Out-Null
        } finally { Invoke-Adb shell rm -f /data/local/tmp/clicker-timing-preferences.xml | Out-Null }
        Invoke-Adb shell am start -n com.ahao.clicker/.MainActivity | Out-Null
        Reconnect-Emulator
    }
    Pop-Location
}
