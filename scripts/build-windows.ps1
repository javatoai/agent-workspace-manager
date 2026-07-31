$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    & .\gradlew.bat clean test :cli:distZip :desktop:createDistributable :desktop:packageExe :desktop:packageMsi --no-daemon
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle build failed with exit code $LASTEXITCODE"
    }

    $version = (Select-String -Path 'desktop\build.gradle.kts' -Pattern 'packageVersion\s*=\s*"([^"]+)"').Matches[0].Groups[1].Value
    $portableSrc = 'desktop\build\compose\binaries\main\app\Task Worktree Manager'
    $zipDir = 'desktop\build\compose\binaries\main\zip'
    New-Item -ItemType Directory -Force -Path $zipDir | Out-Null
    $zipPath = Join-Path $zipDir "Task-Worktree-Manager-$version-portable.zip"
    if (Test-Path $zipPath) {
        Remove-Item $zipPath -Force
    }
    Compress-Archive -Path $portableSrc -DestinationPath $zipPath -CompressionLevel Optimal

    Write-Host ""
    Write-Host "Portable (green) package:"
    Write-Host "  Folder: $portableSrc"
    Write-Host "  Zip:    $zipPath"
    Write-Host "  Launch: $portableSrc\Task Worktree Manager.exe"
}
finally {
    Pop-Location
}
