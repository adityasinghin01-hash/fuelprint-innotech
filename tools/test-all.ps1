# Runs every test in the repository (Windows). P1: the server tests. P3 adds the apps.
$ErrorActionPreference = 'Stop'

$Root = Split-Path -Parent $PSScriptRoot

Write-Host '== server tests'
Push-Location (Join-Path $Root 'server')
try {
    & .\mvnw.cmd -q test
    if ($LASTEXITCODE -ne 0) {
        throw "server tests failed (exit code $LASTEXITCODE)"
    }
} finally {
    Pop-Location
}

Write-Host '== all tests passed'
