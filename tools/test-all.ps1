# Runs every test: the Java server tests, every app's type check and Vitest tests, and the headless end-to-end test
# against a fake-mode server. Usage: tools\test-all.ps1 [-NoE2e]
param([switch]$NoE2e)
. "$PSScriptRoot\lib.ps1"

Say 'server tests'
Push-Location $Server
try { Run '.\mvnw.cmd' @('-q', 'package') } finally { Pop-Location }

if (Test-Path (Join-Path $Apps 'package.json')) {
    Install-Apps
    Push-Location $Apps
    try {
        Say 'screen type checks'
        Run 'npm' @('run', 'typecheck', '--silent')
        Say 'screen tests'
        Run 'npm' @('test', '--silent')
    } finally { Pop-Location }
    if (-not $NoE2e) { & "$PSScriptRoot\test-e2e.ps1" }
}

Say 'all tests passed'
