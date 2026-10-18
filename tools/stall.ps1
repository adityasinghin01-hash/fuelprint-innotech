# The stall: builds every app, then starts the real server on 0.0.0.0:8080 serving /operations/, /cases/, /field/,
# /alerts/. Needs server\config\stations.json with the real PINs (never committed); the example PINs are refused.
. "$PSScriptRoot\lib.ps1"

if (-not (Test-Path (Join-Path $Server 'config\stations.json'))) {
    throw 'Missing server\config\stations.json. Copy server\config\stations.example.json and set real PINs.'
}
Install-Apps
Say 'building the apps'
Push-Location $Apps
try { Run 'npm' @('run', 'build', '--silent') } finally { Pop-Location }
Build-Server
$ip = (Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.*' } |
    Select-Object -First 1).IPAddress
Say "stall server: http://${ip}:8080/operations/ (cases, field, alerts the same way). Stop with Ctrl+C"
Push-Location $Server
try { Run 'java' (@('-jar', $Jar, '--spring.profiles.active=real', '--fp.stations.allow-example=false') + $args) } finally { Pop-Location }
