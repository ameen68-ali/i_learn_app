param([string]$Serial)

$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$androidRoot = Join-Path $taskRoot 'android'
$artifactRoot = Join-Path $taskRoot 'artifacts'
$adbCommand = (Get-Command adb -ErrorAction Stop).Source
if (-not $env:ANDROID_HOME) {
    $sdkCandidate = Split-Path -Parent (Split-Path -Parent $adbCommand)
    if (Test-Path -LiteralPath (Join-Path $sdkCandidate 'platforms')) { $env:ANDROID_HOME = $sdkCandidate }
}
$deviceLines = @(& $adbCommand devices)
$availableDevices = @($deviceLines | Where-Object { $_ -match '^\S+\s+device$' } | ForEach-Object { ($_ -split '\s+')[0] })
if (-not $Serial) {
    if ($availableDevices.Count -ne 1) { throw 'Connect exactly one authorized Android device, or pass -Serial.' }
    $Serial = $availableDevices[0]
}
if ($Serial -notin $availableDevices) { throw 'The requested device is not connected and authorized.' }
$lockState = (& $adbCommand -s $Serial shell dumpsys window policy) -join "`n"
if ($lockState -match '(?m)^\s*showing=true\s*$') { throw 'Unlock the Android device before UI validation. The script does not dismiss the lock screen.' }

New-Item -ItemType Directory -Path $artifactRoot -Force | Out-Null
$previousSerial = $env:ANDROID_SERIAL
$env:ANDROID_SERIAL = $Serial
Push-Location -LiteralPath $androidRoot
try {
    $validationArguments = @(':app:testDebugUnitTest', ':app:lintDebug', ':app:assembleDebug', ':app:assembleDebugAndroidTest',
        ':app:connectedDebugAndroidTest', '-PvalidationBuild=true', '--console=plain', '-Pkotlin.compiler.execution.strategy=in-process',
        '-Dorg.gradle.internal.http.socketTimeout=30000', '-Dorg.gradle.internal.http.connectionTimeout=15000')
    & (Join-Path $androidRoot 'gradlew.bat') @validationArguments 2>&1 | Tee-Object -FilePath (Join-Path $artifactRoot 'device-validation.log')
    $validationExitCode = $LASTEXITCODE
    Copy-Item -LiteralPath (Join-Path $androidRoot 'app/build/outputs/apk/debug/app-debug.apk') -Destination (Join-Path $artifactRoot 'iLearn-validation.apk')
    if ($validationExitCode -ne 0) { throw 'Validation did not pass. Review artifacts/device-validation.log and Android test reports.' }
    Write-Output 'Validation passed. The production application ID and database were not replaced.'
} finally {
    Pop-Location
    $env:ANDROID_SERIAL = $previousSerial
}
