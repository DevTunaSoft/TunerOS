# TunerOS Windows build helper (not the standard Gradle wrapper).
# Downloads Gradle 8.9 to the current user's cache and verifies the official SHA-256.
param()
$ErrorActionPreference = 'Stop'
$version = '8.9'
$project = Split-Path -Parent $PSScriptRoot
$cacheRoot = Join-Path $env:LOCALAPPDATA 'TunerOS\tools'
$installation = Join-Path $cacheRoot "gradle-$version"
$gradleBat = Join-Path $installation 'bin\gradle.bat'

if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    throw 'Java is missing. Install JDK 17 before building TunerOS.'
}
if (-not (Test-Path $gradleBat)) {
    New-Item -ItemType Directory -Force -Path $cacheRoot | Out-Null
    $url = "https://services.gradle.org/distributions/gradle-$version-bin.zip"
    $archive = Join-Path $cacheRoot ("gradle-$version-$PID.zip")
    $staging = Join-Path $cacheRoot ("unpack-$PID")
    try {
        Write-Host "Downloading Gradle $version from its official distribution..."
        $checksum = ([string](Invoke-RestMethod -Uri "$url.sha256" -TimeoutSec 30) -split '\s+')[0].ToLowerInvariant()
        if ($checksum -notmatch '^[a-f0-9]{64}$') { throw 'Invalid official Gradle SHA-256 response' }
        Invoke-WebRequest -Uri $url -OutFile $archive -TimeoutSec 600
        $actual = (Get-FileHash -Algorithm SHA256 -Path $archive).Hash.ToLowerInvariant()
        if ($actual -ne $checksum) { throw 'Gradle archive SHA-256 mismatch; refusing to execute' }
        Expand-Archive -Path $archive -DestinationPath $staging -Force
        if (-not (Test-Path $gradleBat)) {
            Move-Item -Path (Join-Path $staging "gradle-$version") -Destination $installation
        }
    } finally {
        if (Test-Path $archive) { Remove-Item -Force $archive }
        if (Test-Path $staging) { Remove-Item -Recurse -Force $staging }
    }
}
if (-not (Test-Path $gradleBat)) { throw 'Gradle installation failed' }
Push-Location $project
try {
    & $gradleBat --no-daemon :core:test :app:assembleDebug
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed (exit $LASTEXITCODE)" }
    Write-Host 'PASS: Debug APK at app\build\outputs\apk\debug\app-debug.apk'
} finally {
    Pop-Location
}
