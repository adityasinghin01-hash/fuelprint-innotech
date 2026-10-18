# End to end, headless: starts the server jar in fake mode (fast replay, temporary data, free port), then renders
# every app skeleton that has an e2e test against it and checks it sees live ticks with the drone flying.
. "$PSScriptRoot\lib.ps1"

if (-not (Test-Path $Jar)) { Build-Server }
Install-Apps
$Port = Get-FreePort
$Data = Join-Path ([System.IO.Path]::GetTempPath()) ("fp-e2e-" + [guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Path $Data | Out-Null
$Speed = if ($env:FP_E2E_SPEED) { $env:FP_E2E_SPEED } else { '20' }
Say "fake server for the end-to-end test on port $Port"
$proc = Start-Process -FilePath 'java' -PassThru -WindowStyle Hidden -WorkingDirectory $Server `
    -RedirectStandardOutput (Join-Path $Data 'server.log') -RedirectStandardError (Join-Path $Data 'server.err') `
    -ArgumentList @('-jar', $Jar, '--fp.mode=replay', "--fp.replay.speed=$Speed", "--fp.data-dir=$Data",
        "--server.port=$Port", '--server.address=127.0.0.1')
try {
    Wait-ForServer "http://127.0.0.1:$Port"
    Push-Location $Apps
    try {
        foreach ($app in 'operations', 'cases', 'field', 'alerts') {
            if (Test-Path (Join-Path $app 'e2e')) {
                Say "end-to-end: $app"
                $env:FP_E2E_URL = "http://127.0.0.1:$Port"
                Run 'npm' @('run', 'test:e2e', '--workspace', $app)
            }
        }
    } finally { Pop-Location; Remove-Item Env:\FP_E2E_URL -ErrorAction SilentlyContinue }
} finally {
    if (-not $proc.HasExited) { Stop-Process -Id $proc.Id -Force }
    Remove-Item -Recurse -Force $Data -ErrorAction SilentlyContinue
}
