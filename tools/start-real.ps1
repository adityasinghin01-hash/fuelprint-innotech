# Starts the real server on port 8080 with the Spring profile "real": the real science, field, ai and alerts beans
# take over from the stubs. Data in data\runtime. Before join day, use start-fake.ps1.
. "$PSScriptRoot\lib.ps1"

Build-Server
Say 'real server on http://localhost:8080 (stop with Ctrl+C)'
Push-Location $Server
try { Run 'java' (@('-jar', $Jar, '--spring.profiles.active=real') + $args) } finally { Pop-Location }
