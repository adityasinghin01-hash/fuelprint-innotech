# Shared helpers for the FuelPrint scripts. Source it: . "$(dirname "$0")/lib.sh"
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVER="$ROOT/server"
APPS="$ROOT/apps"
JAR="$SERVER/target/fuelprint-server-0.1.0-SNAPSHOT.jar"

say() { printf '== %s\n' "$*"; }

# Builds the server jar (tests are run by tools/test-all.sh, not here).
build_server() {
  say "building the server (first time downloads Maven libraries)"
  (cd "$SERVER" && ./mvnw -q -DskipTests package)
}

# Installs the screens' npm workspace once (one node_modules in apps/).
install_apps() {
  if [ ! -d "$APPS/node_modules" ]; then
    say "installing the screens' npm packages (once)"
    if [ -f "$APPS/package-lock.json" ]; then
      (cd "$APPS" && npm ci --no-audit --no-fund)
    else
      (cd "$APPS" && npm install --no-audit --no-fund)
    fi
  fi
}

# A free TCP port on this machine.
free_port() {
  python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1]); s.close()'
}

# Waits until the server at $1 answers /api/runs/current (up to 120 s).
wait_for_server() {
  local url="$1" i
  for i in $(seq 1 240); do
    if curl -fsS -o /dev/null "$url/api/runs/current" 2>/dev/null; then
      return 0
    fi
    sleep 0.5
  done
  echo "The server at $url did not start." >&2
  return 1
}
