# Starts the server in FAKE mode on port 8080: it plays the recorded scenarios SC1, SC2, SC3 again and again, as
# if live. Ops and cases commands still run. Data goes to data\runtime\fake (wiped at each start; -Keep keeps it).
# Usage: tools\start-fake.ps1 [-Keep] [-Speed N]
param([switch]$Keep, [double]$Speed = 1)
. "$PSScriptRoot\lib.ps1"

$Data = Join-Path $Root 'data\runtime\fake'
if (-not $Keep -and (Test-Path $Data)) {
    Say 'starting with fresh fake data (use -Keep to keep it)'
    Remove-Item -Recurse -Force $Data
}
Build-Server
Say 'fake server on http://localhost:8080 (stop with Ctrl+C). Station PINs: server\config\stations.example.json'
Push-Location $Server
try { Run 'java' @('-jar', $Jar, '--fp.mode=replay', "--fp.replay.speed=$Speed", "--fp.data-dir=$Data") } finally { Pop-Location }
