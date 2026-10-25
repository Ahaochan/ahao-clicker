[CmdletBinding()]
param(
    [ValidateSet('check', 'test', 'assemble', 'version')]
    [string]$Action = 'check'
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot

if ([string]::IsNullOrWhiteSpace($env:GRADLE_USER_HOME)) {
    $env:GRADLE_USER_HOME = Join-Path $projectRoot '.gradle-user-home'
}

$cacheRoot = [System.IO.Path]::GetFullPath($env:GRADLE_USER_HOME)
if ($cacheRoot -match '^[Cc]:[\\/]') {
    throw 'Gradle downloads must not use drive C. Set GRADLE_USER_HOME to a directory on D or E.'
}
$env:GRADLE_USER_HOME = $cacheRoot

$tasks = @(switch ($Action) {
    'check' { @(':core:test', ':app:testDebugUnitTest', ':app:lintDebug', ':app:assembleDebug', ':app:assembleRelease') }
    'test' { @(':core:test') }
    'assemble' { @(':app:assembleDebug', ':app:assembleRelease') }
    'version' { @('--version') }
})

Write-Host "Gradle cache: $cacheRoot"
Push-Location $projectRoot
try {
    & (Join-Path $projectRoot 'gradlew.bat') @tasks
    $buildExitCode = $LASTEXITCODE
} finally {
    Pop-Location
}
exit $buildExitCode
