#!/usr/bin/env bash
# End to end, headless: starts the server jar in fake mode (fast replay, temporary data, free port), then renders
# every app skeleton that has an e2e test against it and checks it sees live ticks with the drone flying.
. "$(dirname "$0")/lib.sh"

[ -f "$JAR" ] || build_server
install_apps
PORT="$(free_port)"
DATA="$(mktemp -d "${TMPDIR:-/tmp}/fp-e2e-XXXXXX")"
LOG="$DATA/server.log"
say "fake server for the end-to-end test on port $PORT"
(cd "$SERVER" && java -jar "$JAR" --fp.mode=replay --fp.replay.speed="${FP_E2E_SPEED:-20}" --fp.data-dir="$DATA" \
  --server.port="$PORT" --server.address=127.0.0.1 > "$LOG" 2>&1) &
PID=$!
cleanup() {
  [ -n "${FP_KEEP_LOG:-}" ] && cp "$LOG" "$FP_KEEP_LOG" 2>/dev/null || true
  kill "$PID" 2>/dev/null || true
  wait "$PID" 2>/dev/null || true
  rm -rf "$DATA"
}
trap cleanup EXIT
if ! wait_for_server "http://127.0.0.1:$PORT"; then
  tail -50 "$LOG" >&2
  exit 1
fi
cd "$APPS"
for app in operations cases field alerts; do
  if [ -d "$app/e2e" ]; then
    say "end-to-end: $app"
    FP_E2E_URL="http://127.0.0.1:$PORT" npm run test:e2e --workspace "$app"
  fi
done
