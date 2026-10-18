# Shared helpers for the FuelPrint PowerShell scripts. Dot-source it: . "$PSScriptRoot\lib.ps1"
$ErrorActionPreference = 'Stop'

$Root = Split-Path -Parent $PSScriptRoot
$Server = Join-Path $Root 'server'
$Apps = Join-Path $Root 'apps'
$Jar = Join-Path $Server 'target\fuelprint-server-0.1.0-SNAPSHOT.jar'

function Say($text) { Write-Host "== $text" }

# Runs a native command and stops on a non-zero exit code.
function Run($file, [string[]]$arguments) {
    & $file @arguments
    if ($LASTEXITCODE -ne 0) { throw "$file $($arguments -join ' ') failed (exit code $LASTEXITCODE)" }
}

function Build-Server {
    Say 'building the server (first time downloads Maven libraries)'
    Push-Location $Server
    try { Run '.\mvnw.cmd' @('-q', '-DskipTests', 'package') } finally { Pop-Location }
}

# Installs the screens' npm workspace once (one node_modules in apps\).
function Install-Apps {
    if (-not (Test-Path (Join-Path $Apps 'node_modules'))) {
        Say "installing the screens' npm packages (once)"
        Push-Location $Apps
        try {
            if (Test-Path 'package-lock.json') { Run 'npm' @('ci', '--no-audit', '--no-fund') }
            else { Run 'npm' @('install', '--no-audit', '--no-fund') }
        } finally { Pop-Location }
    }
}

function Get-FreePort {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    $listener.Start()
    $port = $listener.LocalEndpoint.Port
    $listener.Stop()
    return $port
}

# Waits until the server at $url answers /api/runs/current (up to 120 s).
function Wait-ForServer($url) {
    for ($i = 0; $i -lt 240; $i++) {
        try {
            Invoke-WebRequest -UseBasicParsing -Uri "$url/api/runs/current" -TimeoutSec 2 | Out-Null
            return
        } catch { Start-Sleep -Milliseconds 500 }
    }
    throw "The server at $url did not start."
}
