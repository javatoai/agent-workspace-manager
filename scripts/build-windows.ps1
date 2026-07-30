$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    & .\gradlew.bat clean test :cli:distZip :desktop:packageExe :desktop:packageMsi --no-daemon
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle build failed with exit code $LASTEXITCODE"
    }
}
finally {
    Pop-Location
}
