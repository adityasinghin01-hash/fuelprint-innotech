# Runs one app with live reload on http://localhost:5173/<app>/, talking to the server on port 8080
# (start it first with tools\start-fake.ps1). Usage: tools\dev-app.ps1 operations|cases|field|alerts
param([Parameter(Mandatory = $true)][ValidateSet('operations', 'cases', 'field', 'alerts')][string]$App)
. "$PSScriptRoot\lib.ps1"

if (-not (Test-Path (Join-Path $Apps "$App\package.json"))) {
    throw "apps\$App has no app yet (its job creates it)."
}
Install-Apps
Say "open http://localhost:5173/$App/"
Push-Location $Apps
try { Run 'npm' @('run', 'dev', '--workspace', $App) } finally { Pop-Location }
